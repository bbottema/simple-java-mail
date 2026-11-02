package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.internal.clisupport.CliExitCode;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DaemonStartupLogSafetyTest {

	@TempDir Path root;
	private final String previousStateRoot = System.getProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY);

	@AfterEach
	void restoreStateRoot() {
		if (previousStateRoot == null) {
			System.clearProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY);
		} else {
			System.setProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY, previousStateRoot);
		}
	}

	@Test
	void refusesLinkedStartupLogWithoutChangingTargetContentOrPermissions() throws Exception {
		final Path target = Files.writeString(root.resolve("important.txt"), "keep this content");
		final Object permissions = permissionSnapshot(target);
		final Path startupLog = root.resolve("daemon-startup.log");
		createSymbolicLink(startupLog, target);

		assertThatThrownBy(() -> prepareStartupLog(startupLog)).isInstanceOf(DaemonSecurityException.class);

		assertThat(Files.readString(target)).isEqualTo("keep this content");
		assertThat(permissionSnapshot(target)).isEqualTo(permissions);
		assertThat(Files.isSymbolicLink(startupLog)).isTrue();
	}

	@Test
	void refusesDanglingStartupLogWithoutCreatingItsTarget() throws Exception {
		final Path target = root.resolve("must-not-be-created.txt");
		final Path startupLog = root.resolve("daemon-startup.log");
		createSymbolicLink(startupLog, target);

		assertThatThrownBy(() -> prepareStartupLog(startupLog)).isInstanceOf(DaemonSecurityException.class);

		assertThat(target).doesNotExist();
		assertThat(Files.isSymbolicLink(startupLog)).isTrue();
	}

	@Test
	void refusesDirectoryWithoutChangingItsContents() throws Exception {
		final Path startupLog = Files.createDirectory(root.resolve("daemon-startup.log"));
		final Path child = Files.writeString(startupLog.resolve("keep.txt"), "keep this too");

		assertThatThrownBy(() -> prepareStartupLog(startupLog)).isInstanceOf(IOException.class);

		assertThat(Files.readString(child)).isEqualTo("keep this too");
		assertThat(startupLog).isDirectory();
	}

	@Test
	void createsAPrivateEmptyStartupLog() throws Exception {
		final Path startupLog = root.resolve("daemon-startup.log");

		prepareStartupLog(startupLog);

		assertThat(startupLog).isRegularFile();
		assertThat(Files.size(startupLog)).isZero();
		DaemonStateSecurity.verifyPrivateFile(startupLog);
	}

	@Test
	void truncatesAnExistingRegularStartupLogAndRetainsPrivatePermissions() throws Exception {
		final Path startupLog = Files.writeString(root.resolve("daemon-startup.log"), "previous startup");

		prepareStartupLog(startupLog);

		assertThat(Files.size(startupLog)).isZero();
		DaemonStateSecurity.verifyPrivateFile(startupLog);
	}

	@Test
	void preservesExistingLogWhenPrivatePermissionsCannotBeEstablished() throws Exception {
		final Path startupLog = Files.writeString(root.resolve("daemon-startup.log"), "retain this diagnostic");
		final DaemonSecurityException refusal = new DaemonSecurityException("Private permissions unavailable");
		try (MockedStatic<DaemonStateSecurity> security = Mockito.mockStatic(DaemonStateSecurity.class)) {
			security.when(() -> DaemonStateSecurity.ensurePrivateFile(startupLog)).thenThrow(refusal);

			assertThatThrownBy(() -> prepareStartupLog(startupLog)).isSameAs(refusal);
		}

		assertThat(Files.readString(startupLog)).isEqualTo("retain this diagnostic");
	}

	@Test
	void daemonStartRefusesLinkedStartupLogBeforeLaunchingAChild() throws Exception {
		System.setProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY, root.resolve("daemon-state").toString());
		final DaemonPaths paths = DaemonPaths.forInstance("startup-link");
		paths.prepare();
		final Path target = Files.writeString(root.resolve("important.txt"), "still here");
		createSymbolicLink(paths.startupLogFile(), target);

		final var result = DaemonProcessLauncher.start(paths.instance());

		assertThat(result.category()).isEqualTo(CliExitCode.DAEMON_START_FAILED);
		assertThat(Files.readString(target)).isEqualTo("still here");
		assertThat(paths.discoveryFile()).doesNotExist();
	}

	private static void prepareStartupLog(final Path startupLog) throws Exception {
		final Method prepare = DaemonProcessLauncher.class.getDeclaredMethod("prepareStartupLog", Path.class);
		prepare.setAccessible(true);
		try {
			prepare.invoke(null, startupLog);
		} catch (InvocationTargetException failure) {
			if (failure.getCause() instanceof Exception cause) {
				throw cause;
			}
			throw failure;
		}
	}

	private static void createSymbolicLink(final Path link, final Path target) throws IOException {
		try {
			Files.createSymbolicLink(link, target);
		} catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
			assumeTrue(false, "Symbolic-link creation is unavailable: " + unsupported.getMessage());
		}
	}

	private static Object permissionSnapshot(final Path path) throws IOException {
		if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
			return Files.getPosixFilePermissions(path);
		}
		final AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
		return acl == null ? null : List.copyOf(acl.getAcl());
	}
}
