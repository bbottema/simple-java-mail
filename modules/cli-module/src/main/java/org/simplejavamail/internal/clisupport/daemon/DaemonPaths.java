package org.simplejavamail.internal.clisupport.daemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Derives every runtime path from operating-system conventions, CLI major version, user, and instance name.
 * Long Unix-socket paths move to a short hashed temporary directory while discovery, locks, and logs remain in private
 * per-user state. Callers can therefore verify that an endpoint is exactly the path derived for the selected instance.
 */
final class DaemonPaths {
	private static final int MAX_UNIX_SOCKET_PATH_BYTES = 96;
	static final String STATE_DIRECTORY_PROPERTY = "simplejavamail.cli.daemon.state-dir";
	static final String FORCE_TCP_PROPERTY = "simplejavamail.cli.daemon.force-tcp";

	private final String instance;
	private final Path instanceDirectory;
	private final Path socketDirectory;
	private final Path socketPath;

	private DaemonPaths(final String instance, final Path instanceDirectory, final Path socketDirectory,
			final Path socketPath) {
		this.instance = instance;
		this.instanceDirectory = instanceDirectory;
		this.socketDirectory = socketDirectory;
		this.socketPath = socketPath;
	}

	static DaemonPaths forInstance(final String instance) {
		DaemonBootstrapRequest.validateInstance(instance);
		final Path root = configuredRoot();
		final Path instanceDirectory = root.resolve("v" + DaemonVersion.PRODUCT_MAJOR).resolve(instance)
				.toAbsolutePath().normalize();
		final SocketLocation socket = deriveSocketLocation(root, instanceDirectory, instance);
		return new DaemonPaths(instance, instanceDirectory, socket.directory(), socket.path());
	}

	private static SocketLocation deriveSocketLocation(final Path root, final Path instanceDirectory,
			final String instance) {
		final String userAndInstanceHash = shortHash(System.getProperty("user.name", "unknown") + '\0'
				+ DaemonVersion.PRODUCT_MAJOR + '\0' + instance);
		final Path preferredSocket = instanceDirectory.resolve("sjm-" + userAndInstanceHash + ".sock");
		if (preferredSocket.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_UNIX_SOCKET_PATH_BYTES) {
			return new SocketLocation(instanceDirectory, preferredSocket.toAbsolutePath().normalize());
		}
		final Path shortDirectory = Path.of(System.getProperty("java.io.tmpdir"),
				"sjm-" + shortHash(root.toString()).substring(0, 8));
		return new SocketLocation(shortDirectory,
				shortDirectory.resolve("sjm-" + userAndInstanceHash + ".sock").toAbsolutePath().normalize());
	}

	private static Path configuredRoot() {
		final String property = System.getProperty(STATE_DIRECTORY_PROPERTY);
		final String environment = System.getenv("SJM_DAEMON_STATE_DIR");
		if (property != null && !property.isBlank()) {
			return Path.of(property);
		}
		if (environment != null && !environment.isBlank()) {
			return Path.of(environment);
		}
		if (isWindows()) {
			final String localAppData = System.getenv("LOCALAPPDATA");
			return Path.of(localAppData != null ? localAppData : System.getProperty("user.home"),
					"SimpleJavaMail", "daemon");
		}
		if (isMac()) {
			return Path.of(System.getProperty("user.home"), "Library", "Application Support", "SimpleJavaMail",
					"daemon");
		}
		final String xdgRuntime = System.getenv("XDG_RUNTIME_DIR");
		if (xdgRuntime != null && !xdgRuntime.isBlank()) {
			return Path.of(xdgRuntime, "simple-java-mail");
		}
		return Path.of(System.getProperty("user.home"), ".local", "state", "simple-java-mail", "daemon");
	}

	void prepare() throws IOException {
		DaemonStateSecurity.ensurePrivateDirectory(stateRoot());
		DaemonStateSecurity.ensurePrivateDirectory(instanceDirectory.getParent());
		DaemonStateSecurity.ensurePrivateDirectory(instanceDirectory);
		DaemonStateSecurity.ensurePrivateDirectory(socketDirectory);
	}

	String instance() {
		return instance;
	}

	Path instanceDirectory() {
		return instanceDirectory;
	}

	Path stateRoot() {
		return instanceDirectory.getParent().getParent();
	}

	Path discoveryFile() {
		return instanceDirectory.resolve("discovery.state");
	}

	Path lockFile() {
		return instanceDirectory.resolve("instance.lock");
	}

	Path logFile() {
		return instanceDirectory.resolve("daemon.log");
	}

	Path startupLogFile() {
		return instanceDirectory.resolve("daemon-startup.log");
	}

	Path socketPath() {
		return socketPath;
	}

	boolean isDerivedSocket(final Path path) {
		return socketPath.equals(path.toAbsolutePath().normalize());
	}

	private static String shortHash(final String value) {
		try {
			final byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 10);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private static boolean isMac() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
	}

	/** Keeps the directory and fully normalized socket path coupled after path-length fallback. */
	private record SocketLocation(Path directory, Path path) {
	}
}
