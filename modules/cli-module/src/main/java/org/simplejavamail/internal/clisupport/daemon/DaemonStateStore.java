package org.simplejavamail.internal.clisupport.daemon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Coordinates exclusive instance ownership and the private discovery record used by short-lived CLI clients.
 * Discovery writes use a strict, bounded field set and atomic replacement because the record contains both process
 * identity and the local authentication secret. Reads fail closed on permissions, links, encoding, unknown fields, or
 * malformed values, and cleanup removes a record only when its session still belongs to the caller.
 */
final class DaemonStateStore {
	private static final int MAX_STATE_BYTES = 16 * 1024;
	private static final Set<String> REQUIRED_KEYS = Set.of("instance", "productVersion", "protocolMajor", "protocolMinor",
			"sessionId", "pid", "processStartMillis", "transport", "address", "transportReason", "secret", "readyAt");

	private final DaemonPaths paths;

	DaemonStateStore(final DaemonPaths paths) {
		this.paths = paths;
	}

	LockHandle tryLock() throws IOException {
		paths.prepare();
		final FileChannel channel = FileChannel.open(paths.lockFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		try {
			DaemonStateSecurity.ensurePrivateFile(paths.lockFile());
			final FileLock lock = channel.tryLock();
			if (lock == null) {
				channel.close();
				return null;
			}
			return new LockHandle(channel, lock);
		} catch (OverlappingFileLockException e) {
			channel.close();
			return null;
		} catch (IOException | RuntimeException e) {
			channel.close();
			throw e;
		}
	}

	void write(final DaemonDiscovery discovery) throws IOException {
		final byte[] serializedDiscovery = serializeDiscovery(discovery);
		final Path temporary = writeTemporaryDiscovery(serializedDiscovery, discovery.sessionId());
		replaceDiscovery(temporary);
	}

	DaemonDiscovery read() throws IOException {
		final Path file = paths.discoveryFile();
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
			return null;
		}
		verifyDiscoveryPath(file);
		return toDiscovery(parseDiscoveryFields(readBoundedAscii(file)));
	}

	void removeIfOwned(final UUID sessionId) throws IOException {
		final DaemonDiscovery current = read();
		if (current != null && current.sessionId().equals(sessionId)) {
			Files.deleteIfExists(paths.discoveryFile());
		}
	}

	private static byte[] serializeDiscovery(final DaemonDiscovery discovery) throws IOException {
		final Map<String, String> values = new LinkedHashMap<>();
		values.put("instance", discovery.instance());
		values.put("productVersion", discovery.productVersion());
		values.put("protocolMajor", Short.toString(discovery.protocolMajor()));
		values.put("protocolMinor", Short.toString(discovery.protocolMinor()));
		values.put("sessionId", discovery.sessionId().toString());
		values.put("pid", Long.toString(discovery.pid()));
		values.put("processStartMillis", Long.toString(discovery.processStartMillis()));
		values.put("transport", discovery.endpoint().kind().name());
		values.put("address", discovery.endpoint().address());
		values.put("transportReason", discovery.endpoint().selectionReason());
		values.put("secret", Base64.getEncoder().encodeToString(discovery.authenticationSecret()));
		values.put("readyAt", Long.toString(discovery.readyAt().toEpochMilli()));
		final StringBuilder serialized = new StringBuilder();
		values.forEach((key, value) -> serialized.append(key).append('=')
				.append(Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8))).append('\n'));
		final byte[] bytes = serialized.toString().getBytes(StandardCharsets.US_ASCII);
		if (bytes.length > MAX_STATE_BYTES) {
			throw new IOException("Daemon discovery state exceeds its limit");
		}
		return bytes;
	}

	private Path writeTemporaryDiscovery(final byte[] bytes, final UUID sessionId) throws IOException {
		final Path temporary = paths.instanceDirectory().resolve("discovery-" + sessionId + ".tmp");
		try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			DaemonStateSecurity.ensurePrivateFile(temporary);
			output.write(ByteBuffer.wrap(bytes));
			output.force(true);
		}
		return temporary;
	}

	private void replaceDiscovery(final Path temporary) throws IOException {
		try {
			Files.move(temporary, paths.discoveryFile(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.deleteIfExists(temporary);
			throw new IOException("Daemon discovery state requires atomic replacement", e);
		}
		DaemonStateSecurity.ensurePrivateFile(paths.discoveryFile());
	}

	private void verifyDiscoveryPath(final Path file) throws IOException {
		DaemonStateSecurity.verifyPrivateDirectory(paths.stateRoot());
		DaemonStateSecurity.verifyPrivateDirectory(paths.instanceDirectory().getParent());
		DaemonStateSecurity.verifyPrivateDirectory(paths.instanceDirectory());
		DaemonStateSecurity.verifyPrivateFile(file);
	}

	private static byte[] readBoundedAscii(final Path file) throws IOException {
		final byte[] bytes;
		try (InputStream input = Files.newInputStream(file)) {
			bytes = input.readNBytes(MAX_STATE_BYTES + 1);
		}
		if (bytes.length < 1 || bytes.length > MAX_STATE_BYTES) {
			throw new IOException("Invalid daemon discovery state size");
		}
		for (byte value : bytes) {
			if ((value & 0x80) != 0) {
				throw new IOException("Daemon discovery state is not ASCII");
			}
		}
		return bytes;
	}

	private static Map<String, String> parseDiscoveryFields(final byte[] bytes) throws IOException {
		final List<String> lines = List.of(new String(bytes, StandardCharsets.US_ASCII).split("\\n"));
		final Map<String, String> values = new LinkedHashMap<>();
		for (String line : lines) {
			final int separator = line.indexOf('=');
			if (separator <= 0) {
				throw new IOException("Malformed daemon discovery state");
			}
			final String key = line.substring(0, separator);
			if (!REQUIRED_KEYS.contains(key) || values.containsKey(key)) {
				throw new IOException("Unknown or duplicate daemon discovery field");
			}
			try {
				values.put(key, decodeValue(line.substring(separator + 1)));
			} catch (IllegalArgumentException e) {
				throw new IOException("Malformed daemon discovery value", e);
			}
		}
		if (!values.keySet().equals(REQUIRED_KEYS)) {
			throw new IOException("Incomplete daemon discovery state");
		}
		return values;
	}

	private static DaemonDiscovery toDiscovery(final Map<String, String> values) throws IOException {
		try {
			return new DaemonDiscovery(
					DaemonBootstrapRequest.validateInstance(values.get("instance")),
					values.get("productVersion"),
					Short.parseShort(values.get("protocolMajor")),
					Short.parseShort(values.get("protocolMinor")),
					UUID.fromString(values.get("sessionId")),
					Long.parseLong(values.get("pid")),
					Long.parseLong(values.get("processStartMillis")),
					new DaemonEndpoint(DaemonEndpoint.Kind.valueOf(values.get("transport")), values.get("address"),
							values.get("transportReason")),
					Base64.getDecoder().decode(values.get("secret")),
					Instant.ofEpochMilli(Long.parseLong(values.get("readyAt"))));
		} catch (IllegalArgumentException e) {
			throw new IOException("Malformed daemon discovery state", e);
		}
	}

	private static String decodeValue(final String encoded) throws IOException {
		final byte[] bytes = Base64.getUrlDecoder().decode(encoded);
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			throw new IOException("Malformed UTF-8 in daemon discovery value", e);
		}
	}

	/** Keeps the channel alive for exactly as long as this process owns the operating-system lock. */
	static final class LockHandle implements AutoCloseable {
		private final FileChannel channel;
		private final FileLock lock;

		private LockHandle(final FileChannel channel, final FileLock lock) {
			this.channel = channel;
			this.lock = lock;
		}

		@Override
		public void close() throws IOException {
			try {
				lock.release();
			} finally {
				channel.close();
			}
		}
	}
}
