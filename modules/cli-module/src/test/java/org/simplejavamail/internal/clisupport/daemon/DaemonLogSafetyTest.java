package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.internal.clisupport.CliExitCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DaemonLogSafetyTest {

	@TempDir Path root;
	private final String previousStateRoot = System.getProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY);
	private final String previousLogFile = System.getProperty("simplejavamail.cli.daemon.log-file");
	private final String previousLogConfiguration = System.getProperty("log4j.configurationFile");

	@AfterEach
	void restoreProperties() {
		restoreProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY, previousStateRoot);
		restoreProperty("simplejavamail.cli.daemon.log-file", previousLogFile);
		restoreProperty("log4j.configurationFile", previousLogConfiguration);
	}

	@Test
	void foregroundStartupRefusesDirectoryLogBeforeChangingPermissionsOrOpeningTransport() throws Exception {
		final DaemonPaths paths = prepareInstance("directory-log");
		final Path log = Files.createDirectory(paths.logFile());
		final Path child = Files.writeString(log.resolve("keep.txt"), "keep this content");
		makeDirectoryPermissionsDistinct(log);
		final Permissions permissions = Permissions.capture(log);
		try {
			assertForegroundRefusal(paths);

			assertThat(Permissions.capture(log)).isEqualTo(permissions);
			assertThat(log).isDirectory();
			assertThat(Files.readString(child)).isEqualTo("keep this content");
		} finally {
			permissions.restore(log);
		}
	}

	@Test
	void privateFileBoundaryRefusesDirectoryWithoutChangingPermissions() throws Exception {
		final Path directory = Files.createDirectory(root.resolve("not-a-file"));
		makeDirectoryPermissionsDistinct(directory);
		final Permissions permissions = Permissions.capture(directory);
		try {
			assertThatThrownBy(() -> DaemonStateSecurity.ensurePrivateFile(directory))
					.isInstanceOf(DaemonSecurityException.class)
					.hasMessageContaining("regular file");

			assertThat(Permissions.capture(directory)).isEqualTo(permissions);
		} finally {
			permissions.restore(directory);
		}
	}

	@Test
	void detachedStartupRefusesDirectoryLogAndPreservesItsPermissionsAndContents() throws Exception {
		final DaemonPaths paths = prepareInstance("detached-directory-log");
		final Path log = Files.createDirectory(paths.logFile());
		final Path child = Files.writeString(log.resolve("keep.txt"), "keep the detached target");
		makeDirectoryPermissionsDistinct(log);
		final Permissions permissions = Permissions.capture(log);
		try {
			assertThat(DaemonProcessLauncher.start(paths.instance()).category()).isEqualTo(CliExitCode.DAEMON_SECURITY);

			assertThat(Permissions.capture(log)).isEqualTo(permissions);
			assertThat(Files.readString(child)).isEqualTo("keep the detached target");
			assertThat(Files.readString(paths.startupLogFile())).contains("Daemon state file must be a regular file");
			assertStoppedWithoutDiscovery(paths);
		} finally {
			new DaemonClient(paths.instance()).stop();
			permissions.restore(log);
		}
	}

	@Test
	void foregroundStartupRefusesLinkedLogWithoutChangingTarget() throws Exception {
		final DaemonPaths paths = prepareInstance("linked-log");
		final Path target = Files.writeString(root.resolve("important.txt"), "keep this target");
		final Permissions permissions = Permissions.capture(target);
		createSymbolicLink(paths.logFile(), target);

		assertForegroundRefusal(paths);

		assertThat(Files.readString(target)).isEqualTo("keep this target");
		assertThat(Permissions.capture(target)).isEqualTo(permissions);
		assertThat(Files.isSymbolicLink(paths.logFile())).isTrue();
	}

	@Test
	void foregroundStartupRefusesDanglingLogWithoutCreatingTarget() throws Exception {
		final DaemonPaths paths = prepareInstance("dangling-log");
		final Path target = root.resolve("must-not-be-created.txt");
		createSymbolicLink(paths.logFile(), target);

		assertForegroundRefusal(paths);

		assertThat(target).doesNotExist();
		assertThat(Files.isSymbolicLink(paths.logFile())).isTrue();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void regularLogIsPrivateAndPreservedBeforeTransportSetup(final boolean existing) throws Exception {
		final DaemonPaths paths = prepareInstance("regular-log");
		if (existing) {
			Files.writeString(paths.logFile(), "previous daemon diagnostic");
		}
		try (MockedStatic<LocalDaemonTransport> transport = Mockito.mockStatic(LocalDaemonTransport.class)) {
			transport.when(() -> LocalDaemonTransport.openServer(Mockito.any())).thenAnswer(invocation -> {
				DaemonStateSecurity.verifyPrivateFile(paths.logFile());
				throw new IOException("Stop the fixture after ordinary log setup");
			});

			assertThat(runForeground(paths)).isEqualTo(CliExitCode.DAEMON_START_FAILED.code());

			transport.verify(() -> LocalDaemonTransport.openServer(Mockito.any()));
		}
		assertThat(paths.logFile()).isRegularFile();
		assertThat(Files.readString(paths.logFile())).isEqualTo(existing ? "previous daemon diagnostic" : "");
		DaemonStateSecurity.verifyPrivateFile(paths.logFile());
		assertStoppedWithoutDiscovery(paths);
	}

	private DaemonPaths prepareInstance(final String instance) throws IOException {
		System.setProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY, root.resolve("daemon-state").toString());
		final DaemonPaths paths = DaemonPaths.forInstance(instance);
		paths.prepare();
		return paths;
	}

	private static void assertForegroundRefusal(final DaemonPaths paths) throws IOException {
		try (MockedStatic<LocalDaemonTransport> transport = Mockito.mockStatic(LocalDaemonTransport.class)) {
			transport.when(() -> LocalDaemonTransport.openServer(Mockito.any()))
					.thenThrow(new IOException("Startup must refuse this log before transport setup"));

			assertThat(runForeground(paths)).isEqualTo(CliExitCode.DAEMON_SECURITY.code());

			transport.verifyNoInteractions();
		}
		assertStoppedWithoutDiscovery(paths);
	}

	private static int runForeground(final DaemonPaths paths) {
		return DaemonBootstrap.run(new String[] {"daemon", "run", "--daemon-instance=" + paths.instance()});
	}

	private static void assertStoppedWithoutDiscovery(final DaemonPaths paths) throws IOException {
		assertThat(paths.discoveryFile()).doesNotExist();
		assertThat(paths.socketPath()).doesNotExist();
		try (DaemonStateStore.LockHandle lock = new DaemonStateStore(paths).tryLock()) {
			assertThat(lock).isNotNull();
		}
	}

	private static void makeDirectoryPermissionsDistinct(final Path directory) throws IOException {
		if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null) {
			Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
		} else {
			final AclFileAttributeView acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
			assumeTrue(acl != null, "The fixture requires a permission model used by the daemon");
			final EnumSet<AclEntryPermission> permissions = EnumSet.allOf(AclEntryPermission.class);
			permissions.remove(AclEntryPermission.WRITE_OWNER);
			acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
					.setPrincipal(Files.getOwner(directory)).setPermissions(permissions).build()));
		}
	}

	private static void createSymbolicLink(final Path link, final Path target) throws IOException {
		try {
			Files.createSymbolicLink(link, target);
		} catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
			assumeTrue(false, "Symbolic-link creation is unavailable: " + unsupported.getMessage());
		}
	}

	private static void restoreProperty(final String name, final String value) {
		if (value == null) {
			System.clearProperty(name);
		} else {
			System.setProperty(name, value);
		}
	}

	private record Permissions(Set<PosixFilePermission> posix, List<AclEntry> acl) {
		private static Permissions capture(final Path path) throws IOException {
			if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
				return new Permissions(Set.copyOf(Files.getPosixFilePermissions(path)), null);
			}
			final AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
			return new Permissions(null, view == null ? null : List.copyOf(view.getAcl()));
		}

		private void restore(final Path path) throws IOException {
			if (posix != null) {
				Files.setPosixFilePermissions(path, posix);
			} else if (acl != null) {
				Files.getFileAttributeView(path, AclFileAttributeView.class).setAcl(acl);
			}
		}
	}
}
