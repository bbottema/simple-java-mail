package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.internal.clisupport.CliExitCode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DaemonStateStoreTest {
	@TempDir Path root;
	private String previousRoot;
	private String previousOsName;

	@AfterEach
	void restoreProperty() {
		if (previousRoot == null) {
			System.clearProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY);
		} else {
			System.setProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY, previousRoot);
		}
		if (previousOsName != null) {
			System.setProperty("os.name", previousOsName);
		}
	}

	@Test
	void stateIsAtomicBoundedPrivateAndSingleInstanceLocked() throws Exception {
		useRoot();
		final DaemonPaths paths = DaemonPaths.forInstance("test");
		final DaemonStateStore store = new DaemonStateStore(paths);
		final byte[] secret = new byte[32];
		final UUID session = UUID.randomUUID();
		try (DaemonStateStore.LockHandle lock = store.tryLock()) {
			assertThat(lock).isNotNull();
			assertThat(store.tryLock()).isNull();
			store.write(new DaemonDiscovery("test", DaemonVersion.PRODUCT_VERSION, DaemonVersion.PROTOCOL_MAJOR,
					DaemonVersion.PROTOCOL_MINOR, session, ProcessHandle.current().pid(),
					ProcessHandle.current().info().startInstant().orElse(Instant.now()).toEpochMilli(),
					new DaemonEndpoint(DaemonEndpoint.Kind.TCP, "127.0.0.1:2525"), secret, Instant.now()));

			final DaemonDiscovery read = store.read();
			assertThat(read.sessionId()).isEqualTo(session);
			assertThat(read.authenticationSecret()).containsExactly(secret);
			assertThat(read.endpoint().address()).isEqualTo("127.0.0.1:2525");
			store.removeIfOwned(UUID.randomUUID());
			assertThat(paths.discoveryFile()).exists();
			store.removeIfOwned(session);
			assertThat(paths.discoveryFile()).doesNotExist();
		}
	}

	@Test
	void duplicateOrUnknownDiscoveryFieldsAreRejected() throws Exception {
		useRoot();
		final DaemonPaths paths = DaemonPaths.forInstance("tamper");
		final DaemonStateStore store = new DaemonStateStore(paths);
		try (DaemonStateStore.LockHandle ignored = store.tryLock()) {
			final String encoded = Base64.getUrlEncoder().withoutPadding()
					.encodeToString("tamper".getBytes(StandardCharsets.UTF_8));
			Files.writeString(paths.discoveryFile(), "instance=" + encoded + "\ninstance=" + encoded + "\n",
					StandardCharsets.US_ASCII);
			DaemonStateSecurity.ensurePrivateFile(paths.discoveryFile());
			assertThatThrownBy(store::read).isInstanceOf(java.io.IOException.class);
		}
	}

	@Test
	void oversizedDiscoveryStateIsRejectedWithBoundedInput() throws Exception {
		useRoot();
		final DaemonPaths paths = DaemonPaths.forInstance("oversized");
		final DaemonStateStore store = new DaemonStateStore(paths);
		try (DaemonStateStore.LockHandle ignored = store.tryLock()) {
			Files.write(paths.discoveryFile(), new byte[16 * 1024 + 1]);
			DaemonStateSecurity.ensurePrivateFile(paths.discoveryFile());
			assertThatThrownBy(store::read)
					.isInstanceOf(java.io.IOException.class)
					.hasMessageContaining("size");
		}
	}

	@Test
	void aForeignUnixSocketPathInDiscoveryStateIsRejectedBeforeConnecting() throws Exception {
		useRoot();
		final DaemonPaths paths = DaemonPaths.forInstance("foreign-endpoint");
		final DaemonStateStore store = new DaemonStateStore(paths);
		try (DaemonStateStore.LockHandle ignored = store.tryLock()) {
			store.write(discovery("foreign-endpoint",
					new DaemonEndpoint(DaemonEndpoint.Kind.UNIX, root.resolve("foreign.sock").toString())));

			assertThat(new DaemonClient("foreign-endpoint").status().category()).isEqualTo(CliExitCode.DAEMON_SECURITY);
		}
	}

	@Test
	void tcpDiscoveryEndpointsAreRestrictedToLiteralIpv4Loopback() {
		assertThatThrownBy(() -> new DaemonEndpoint(DaemonEndpoint.Kind.TCP, "localhost:2525"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("TCP endpoint");
		assertThatThrownBy(() -> new DaemonEndpoint(DaemonEndpoint.Kind.TCP, "127.0.0.1:0"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("TCP endpoint");
	}

	@Test
	void longStateRootsUseABoundedDerivedSocketPath() {
		previousRoot = System.getProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY);
		System.setProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY,
				root.resolve("a".repeat(80)).resolve("b".repeat(80)).toString());

		final DaemonPaths paths = DaemonPaths.forInstance("path-length");

		assertThat(paths.socketPath().toString().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(96);
		assertThat(paths.isDerivedSocket(paths.socketPath())).isTrue();
	}

	@Test
	void macDefaultsUseThePerUserApplicationSupportDirectory() {
		previousRoot = System.getProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY);
		System.clearProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY);
		previousOsName = System.getProperty("os.name");
		System.setProperty("os.name", "Mac OS X");

		final DaemonPaths paths = DaemonPaths.forInstance("mac-default");

		assertThat(paths.stateRoot()).isEqualTo(Path.of(System.getProperty("user.home"), "Library",
				"Application Support", "SimpleJavaMail", "daemon").toAbsolutePath().normalize());
	}

	@Test
	void aRegularFileAtTheDerivedSocketPathIsRefusedAndPreserved() throws Exception {
		useRoot();
		final DaemonPaths paths = DaemonPaths.forInstance("occupied-socket");
		paths.prepare();
		Files.writeString(paths.socketPath(), "keep", StandardCharsets.UTF_8);

		assertThatThrownBy(() -> LocalDaemonTransport.openServer(paths))
				.isInstanceOf(DaemonSecurityException.class)
				.hasMessageContaining("non-socket");
		assertThat(Files.readString(paths.socketPath(), StandardCharsets.UTF_8)).isEqualTo("keep");
	}

	@Test
	void discoverySymlinksAreRejectedWhereThePlatformAllowsCreatingOne() throws Exception {
		useRoot();
		final DaemonPaths paths = DaemonPaths.forInstance("symlink");
		final DaemonStateStore store = new DaemonStateStore(paths);
		try (DaemonStateStore.LockHandle ignored = store.tryLock()) {
			final Path target = root.resolve("foreign-state");
			Files.writeString(target, "not discovery state", StandardCharsets.US_ASCII);
			try {
				Files.createSymbolicLink(paths.discoveryFile(), target);
			} catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
				return;
			}
			assertThatThrownBy(store::read)
					.isInstanceOf(DaemonSecurityException.class)
					.hasMessageContaining("symbolic link");
		}
	}

	@Test
	void evenSameMajorProductVersionDriftRequiresDaemonRestart() {
		final DaemonDiscovery discovery = new DaemonDiscovery("version", DaemonVersion.PRODUCT_VERSION + ".different",
				DaemonVersion.PROTOCOL_MAJOR, DaemonVersion.PROTOCOL_MINOR, UUID.randomUUID(), ProcessHandle.current().pid(),
				ProcessHandle.current().info().startInstant().orElse(Instant.now()).toEpochMilli(),
				new DaemonEndpoint(DaemonEndpoint.Kind.TCP, "127.0.0.1:2525"), new byte[32], Instant.now());

		assertThat(discovery.compatible()).isFalse();
	}

	@Test
	void broadenedPosixStateDirectoryPermissionsFailClosed() throws Exception {
		useRoot();
		final DaemonPaths paths = DaemonPaths.forInstance("permissions");
		final DaemonStateStore store = new DaemonStateStore(paths);
		try (DaemonStateStore.LockHandle ignored = store.tryLock()) {
			if (Files.getFileAttributeView(paths.instanceDirectory(), PosixFileAttributeView.class) == null) {
				return;
			}
			store.write(discovery("permissions", new DaemonEndpoint(DaemonEndpoint.Kind.TCP, "127.0.0.1:2525")));
			Files.setPosixFilePermissions(paths.instanceDirectory(), PosixFilePermissions.fromString("rwxr-xr-x"));
			assertThatThrownBy(store::read)
					.isInstanceOf(DaemonSecurityException.class)
					.hasMessageContaining("owner-only");
		} finally {
			if (Files.exists(paths.instanceDirectory())
					&& Files.getFileAttributeView(paths.instanceDirectory(), PosixFileAttributeView.class) != null) {
				Files.setPosixFilePermissions(paths.instanceDirectory(), PosixFilePermissions.fromString("rwx------"));
			}
		}
	}

	private static DaemonDiscovery discovery(final String instance, final DaemonEndpoint endpoint) {
		return new DaemonDiscovery(instance, DaemonVersion.PRODUCT_VERSION, DaemonVersion.PROTOCOL_MAJOR,
				DaemonVersion.PROTOCOL_MINOR, UUID.randomUUID(), ProcessHandle.current().pid(),
				ProcessHandle.current().info().startInstant().orElse(Instant.now()).toEpochMilli(), endpoint, new byte[32],
				Instant.now());
	}

	private void useRoot() {
		previousRoot = System.getProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY);
		System.setProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY, root.toString());
	}
}
