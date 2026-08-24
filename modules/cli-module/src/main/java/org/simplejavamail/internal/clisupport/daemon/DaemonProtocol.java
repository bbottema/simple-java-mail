package org.simplejavamail.internal.clisupport.daemon;

import org.simplejavamail.internal.clisupport.CliExitCode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Defines the bounded, authenticated binary contract shared by the daemon client and server.
 * Every frame carries explicit product-protocol metadata, session and request identities, a freshness timestamp, and an
 * HMAC over its header and payload. The protocol deliberately avoids Java serialization and rejects oversized or
 * malformed input before command execution.
 */
final class DaemonProtocol {
	static final int MAX_FRAME_BYTES = 1024 * 1024;
	static final int MAX_ARGUMENTS = 512;
	static final int MAX_ARGUMENT_BYTES = 64 * 1024;
	static final int MAX_WORKING_DIRECTORY_BYTES = 16 * 1024;
	static final int MAX_OUTPUT_BYTES = 256 * 1024;
	static final Duration FRESHNESS = Duration.ofMinutes(2);

	private static final int MAGIC = 0x534a4d44;
	private static final byte REQUEST = 1;
	private static final byte RESPONSE = 2;
	private static final int HEADER_BYTES = Integer.BYTES + Short.BYTES + Short.BYTES + 1 + Integer.BYTES
			+ 4 * Long.BYTES + Long.BYTES;
	private static final int MAC_BYTES = 32;
	private static final int MAX_PAYLOAD_BYTES = MAX_FRAME_BYTES - HEADER_BYTES - MAC_BYTES;

	private DaemonProtocol() {
	}

	static void writeRequest(final WritableByteChannel channel, final DaemonRequest request, final byte[] secret)
			throws IOException {
		final byte[] payload = requestPayload(request);
		writeFrame(channel, REQUEST, request.sessionId(), request.requestId(), request.timestamp(), payload, secret);
	}

	static DaemonRequest readRequest(final ReadableByteChannel channel, final UUID expectedSession, final byte[] secret)
			throws IOException {
		final Frame frame = readFrame(channel, REQUEST, expectedSession, secret);
		validateFreshness(frame.timestamp());
		return decodeRequest(frame);
	}

	private static DaemonRequest decodeRequest(final Frame frame) throws IOException {
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(frame.payload()))) {
			final DaemonOperation operation = DaemonOperation.fromCode(input.readUnsignedByte());
			final String workingDirectory = readString(input, MAX_WORKING_DIRECTORY_BYTES);
			final int count = input.readInt();
			if (count < 0 || count > MAX_ARGUMENTS) {
				throw malformed("Invalid argument count");
			}
			final List<String> arguments = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				arguments.add(readString(input, MAX_ARGUMENT_BYTES));
			}
			ensureExhausted(input);
			return new DaemonRequest(operation, frame.sessionId(), frame.requestId(), frame.timestamp(),
					java.nio.file.Path.of(workingDirectory), arguments);
		}
	}

	static void writeResponse(final WritableByteChannel channel, final DaemonResponse response, final byte[] secret)
			throws IOException {
		writeFrame(channel, RESPONSE, response.sessionId(), response.requestId(), response.timestamp(),
				responsePayload(response), secret);
	}

	private static byte[] responsePayload(final DaemonResponse response) throws IOException {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeInt(response.category().code());
			writeString(output, response.stdout(), MAX_OUTPUT_BYTES);
			writeString(output, response.stderr(), MAX_OUTPUT_BYTES);
			writeString(output, response.daemonVersion(), 256);
		}
		return bytes.toByteArray();
	}

	static DaemonResponse readResponse(final ReadableByteChannel channel, final UUID expectedSession,
			final UUID expectedRequest, final byte[] secret) throws IOException {
		final Frame frame = readFrame(channel, RESPONSE, expectedSession, secret);
		if (!frame.requestId().equals(expectedRequest)) {
			throw new DaemonProtocolException(DaemonProtocolException.Kind.AUTHENTICATION,
					"Response request identity mismatch");
		}
		validateFreshness(frame.timestamp());
		return decodeResponse(frame);
	}

	private static DaemonResponse decodeResponse(final Frame frame) throws IOException {
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(frame.payload()))) {
			final int categoryCode = input.readInt();
			if (!CliExitCode.isKnown(categoryCode)) {
				throw malformed("Unknown daemon result category");
			}
			final CliExitCode category = CliExitCode.fromCode(categoryCode);
			final String stdout = readString(input, MAX_OUTPUT_BYTES);
			final String stderr = readString(input, MAX_OUTPUT_BYTES);
			final String version = readString(input, 256);
			ensureExhausted(input);
			return new DaemonResponse(frame.sessionId(), frame.requestId(), frame.timestamp(), category, stdout, stderr,
					version);
		}
	}

	private static byte[] requestPayload(final DaemonRequest request) throws IOException {
		validateRequestPayload(request.workingDirectory(), request.arguments());
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(request.operation().code());
			writeString(output, request.workingDirectory().toString(), MAX_WORKING_DIRECTORY_BYTES);
			output.writeInt(request.arguments().size());
			ensurePayloadSize(bytes.size());
			for (final String argument : request.arguments()) {
				writeString(output, argument, MAX_ARGUMENT_BYTES);
				ensurePayloadSize(bytes.size());
			}
		}
		return bytes.toByteArray();
	}

	static void validateRequestPayload(final Path workingDirectory, final List<String> arguments) {
		if (arguments.size() > MAX_ARGUMENTS) {
			throw new IllegalArgumentException("Too many CLI arguments");
		}
		final int workingDirectoryBytes = workingDirectory.toString().getBytes(StandardCharsets.UTF_8).length;
		if (workingDirectoryBytes > MAX_WORKING_DIRECTORY_BYTES) {
			throw new IllegalArgumentException("Daemon working directory exceeds its limit");
		}
		long payloadBytes = 1L + Integer.BYTES + workingDirectoryBytes + Integer.BYTES;
		for (final String argument : arguments) {
			final int argumentBytes = argument.getBytes(StandardCharsets.UTF_8).length;
			if (argumentBytes > MAX_ARGUMENT_BYTES) {
				throw new IllegalArgumentException("Command argument exceeds " + MAX_ARGUMENT_BYTES + " bytes");
			}
			payloadBytes += Integer.BYTES + argumentBytes;
		}
		if (payloadBytes > MAX_PAYLOAD_BYTES) {
			throw new IllegalArgumentException("Daemon protocol frame is too large");
		}
	}

	private static void writeFrame(final WritableByteChannel channel, final byte type, final UUID sessionId,
			final UUID requestId, final Instant timestamp, final byte[] payload, final byte[] secret) throws IOException {
		if (payload.length > MAX_PAYLOAD_BYTES) {
			throw new IllegalArgumentException("Daemon protocol frame is too large");
		}
		final ByteBuffer authenticated = ByteBuffer.allocate(HEADER_BYTES + payload.length);
		authenticated.putInt(MAGIC);
		authenticated.putShort(DaemonVersion.PROTOCOL_MAJOR);
		authenticated.putShort(DaemonVersion.PROTOCOL_MINOR);
		authenticated.put(type);
		authenticated.putInt(payload.length);
		putUuid(authenticated, sessionId);
		putUuid(authenticated, requestId);
		authenticated.putLong(timestamp.toEpochMilli());
		authenticated.put(payload);
		final byte[] authenticatedBytes = authenticated.array();
		final ByteBuffer frame = ByteBuffer.allocate(authenticatedBytes.length + MAC_BYTES);
		frame.put(authenticatedBytes).put(hmac(secret, authenticatedBytes)).flip();
		writeFully(channel, frame);
	}

	private static Frame readFrame(final ReadableByteChannel channel, final byte expectedType, final UUID expectedSession,
			final byte[] secret) throws IOException {
		final ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
		readFully(channel, header);
		header.flip();
		if (header.getInt() != MAGIC) {
			throw malformed("Invalid daemon protocol magic");
		}
		final short major = header.getShort();
		final short minor = header.getShort();
		if (major != DaemonVersion.PROTOCOL_MAJOR || minor > DaemonVersion.PROTOCOL_MINOR) {
			throw new DaemonProtocolException(DaemonProtocolException.Kind.INCOMPATIBLE,
					"Incompatible daemon protocol version");
		}
		if (header.get() != expectedType) {
			throw malformed("Unexpected daemon message type");
		}
		final int payloadLength = header.getInt();
		if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) {
			throw malformed("Invalid daemon frame length");
		}
		final UUID sessionId = getUuid(header);
		final UUID requestId = getUuid(header);
		final Instant timestamp = Instant.ofEpochMilli(header.getLong());
		if (!sessionId.equals(expectedSession)) {
			throw new DaemonProtocolException(DaemonProtocolException.Kind.AUTHENTICATION, "Daemon session mismatch");
		}
		final ByteBuffer payloadAndMac = ByteBuffer.allocate(payloadLength + MAC_BYTES);
		readFully(channel, payloadAndMac);
		final byte[] all = payloadAndMac.array();
		final byte[] payload = java.util.Arrays.copyOfRange(all, 0, payloadLength);
		final byte[] receivedMac = java.util.Arrays.copyOfRange(all, payloadLength, all.length);
		final byte[] authenticated = ByteBuffer.allocate(HEADER_BYTES + payloadLength)
				.put(header.array()).put(payload).array();
		if (!MessageDigest.isEqual(receivedMac, hmac(secret, authenticated))) {
			throw new DaemonProtocolException(DaemonProtocolException.Kind.AUTHENTICATION, "Daemon authentication failed");
		}
		return new Frame(sessionId, requestId, timestamp, payload);
	}

	private static void validateFreshness(final Instant timestamp) throws DaemonProtocolException {
		if (Duration.between(timestamp, Instant.now()).abs().compareTo(FRESHNESS) > 0) {
			throw new DaemonProtocolException(DaemonProtocolException.Kind.STALE,
					"Daemon message is outside the freshness window");
		}
	}

	private static void writeString(final DataOutputStream output, final String value, final int maximumBytes)
			throws IOException {
		final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > maximumBytes) {
			throw new IllegalArgumentException("Daemon protocol string exceeds its limit");
		}
		output.writeInt(bytes.length);
		output.write(bytes);
	}

	private static String readString(final DataInputStream input, final int maximumBytes) throws IOException {
		final int length = input.readInt();
		if (length < 0 || length > maximumBytes || length > input.available()) {
			throw malformed("Invalid daemon protocol string length");
		}
		final byte[] bytes = input.readNBytes(length);
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			throw malformed("Invalid UTF-8 in daemon message");
		}
	}

	private static void ensureExhausted(final DataInputStream input) throws IOException {
		if (input.read() != -1) {
			throw malformed("Trailing data in daemon message");
		}
	}

	private static void ensurePayloadSize(final int size) {
		if (size > MAX_PAYLOAD_BYTES) {
			throw new IllegalArgumentException("Daemon protocol frame is too large");
		}
	}

	private static byte[] hmac(final byte[] secret, final byte[] input) {
		try {
			final Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return mac.doFinal(input);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 unavailable", e);
		}
	}

	private static void putUuid(final ByteBuffer buffer, final UUID uuid) {
		buffer.putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits());
	}

	private static UUID getUuid(final ByteBuffer buffer) {
		return new UUID(buffer.getLong(), buffer.getLong());
	}

	private static void readFully(final ReadableByteChannel channel, final ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) {
			if (channel.read(buffer) < 0) {
				throw new EOFException("Truncated daemon protocol frame");
			}
		}
	}

	private static void writeFully(final WritableByteChannel channel, final ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) {
			channel.write(buffer);
		}
	}

	private static DaemonProtocolException malformed(final String message) {
		return new DaemonProtocolException(DaemonProtocolException.Kind.MALFORMED, message);
	}

	private record Frame(UUID sessionId, UUID requestId, Instant timestamp, byte[] payload) {
	}
}
