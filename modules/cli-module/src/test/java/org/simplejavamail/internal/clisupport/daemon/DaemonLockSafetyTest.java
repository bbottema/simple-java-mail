package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.internal.clisupport.CliExitCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DaemonLockSafetyTest {

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

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void refusesLinkedLockBeforeCreatingOrChangingItsTarget(final boolean targetExists) throws Exception {
		final DaemonPaths paths = prepareInstance("linked-lock");
		final Path target = root.resolve("unrelated-file");
		if (targetExists) {
			Files.writeString(target, "keep the target");
		}
		final Object permissions = targetExists ? permissionSnapshot(target) : null;
		createSymbolicLink(paths.lockFile(), target);

		assertThatThrownBy(() -> {
			try (DaemonStateStore.LockHandle ignored = new DaemonStateStore(paths).tryLock()) { }
		})
				.isInstanceOf(DaemonSecurityException.class)
				.hasMessageContaining("symbolic link");

		assertThat(Files.isSymbolicLink(paths.lockFile())).isTrue();
		if (targetExists) {
			assertThat(Files.readString(target)).isEqualTo("keep the target");
			assertThat(permissionSnapshot(target)).isEqualTo(permissions);
		} else {
			assertThat(target).doesNotExist();
		}
	}

	@Test
	void refusesDirectoryLockWithoutChangingItsContentsOrPermissions() throws Exception {
		final DaemonPaths paths = prepareInstance("directory-lock");
		Files.createDirectory(paths.lockFile());
		final Path child = Files.writeString(paths.lockFile().resolve("keep.txt"), "keep this directory");
		final Object permissions = permissionSnapshot(paths.lockFile());

		assertThatThrownBy(() -> {
			try (DaemonStateStore.LockHandle ignored = new DaemonStateStore(paths).tryLock()) { }
		})
				.isInstanceOf(DaemonSecurityException.class)
				.hasMessageContaining("regular file");

		assertThat(paths.lockFile()).isDirectory();
		assertThat(Files.readString(child)).isEqualTo("keep this directory");
		assertThat(permissionSnapshot(paths.lockFile())).isEqualTo(permissions);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void regularLocksRemainPrivateExclusiveAndReusable(final boolean existing) throws Exception {
		final DaemonPaths paths = prepareInstance("regular-lock");
		if (existing) {
			Files.writeString(paths.lockFile(), "existing lock content");
		}
		final DaemonStateStore store = new DaemonStateStore(paths);
		try (DaemonStateStore.LockHandle lock = store.tryLock()) {
			assertThat(lock).isNotNull();
			DaemonStateSecurity.verifyPrivateFile(paths.lockFile());
			assertThat(store.tryLock()).isNull();
		}
		assertThat(Files.readString(paths.lockFile())).isEqualTo(existing ? "existing lock content" : "");
		try (DaemonStateStore.LockHandle next = store.tryLock()) {
			assertThat(next).isNotNull();
		}
	}

	@Test
	void foregroundStartupRefusesDanglingLockWithoutCreatingItsTarget() throws Exception {
		final DaemonPaths paths = prepareInstance("foreground-lock");
		final Path target = root.resolve("foreground-target-must-stay-absent");
		createSymbolicLink(paths.lockFile(), target);

		assertThat(DaemonBootstrap.run(new String[] {"daemon", "run", "--daemon-instance=" + paths.instance()}))
				.isEqualTo(CliExitCode.DAEMON_SECURITY.code());

		assertThat(target).doesNotExist();
		assertNoDaemonState(paths);
	}

	@Test
	void detachedStartupRefusesDanglingLockWithoutCreatingItsTargetOrLeavingAChild() throws Exception {
		final DaemonPaths paths = prepareInstance("detached-lock");
		final Path target = root.resolve("detached-target-must-stay-absent");
		createSymbolicLink(paths.lockFile(), target);

		assertThat(DaemonProcessLauncher.start(paths.instance()).category()).isEqualTo(CliExitCode.DAEMON_SECURITY);

		assertThat(target).doesNotExist();
		assertThat(Files.readString(paths.startupLogFile())).contains("symbolic link");
		assertNoDaemonState(paths);
		assertThat(ProcessHandle.current().descendants().noneMatch(process -> process.info().commandLine().orElse("")
				.contains(paths.stateRoot().toString()))).isTrue();
	}

	@Test
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void detachedStartupRefusesFifoLockWithoutWaitingForAReader() throws Exception {
		final DaemonPaths paths = prepareInstance("fifo-lock");
		final Process mkfifo = new ProcessBuilder("mkfifo", paths.lockFile().toString()).redirectErrorStream(true).start();
		try {
			assertThat(mkfifo.waitFor(5, TimeUnit.SECONDS)).isTrue();
			assertThat(mkfifo.exitValue()).isZero();
		} finally {
			if (mkfifo.isAlive()) {
				mkfifo.destroyForcibly();
			}
		}
		final Object permissions = permissionSnapshot(paths.lockFile());

		assertThat(DaemonProcessLauncher.start(paths.instance()).category()).isEqualTo(CliExitCode.DAEMON_SECURITY);

		assertThat(Files.readAttributes(paths.lockFile(), java.nio.file.attribute.BasicFileAttributes.class).isOther()).isTrue();
		assertThat(permissionSnapshot(paths.lockFile())).isEqualTo(permissions);
		assertNoDaemonState(paths);
	}

	private DaemonPaths prepareInstance(final String instance) throws IOException {
		System.setProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY, root.resolve("daemon-state").toString());
		final DaemonPaths paths = DaemonPaths.forInstance(instance);
		paths.prepare();
		return paths;
	}

	private static void assertNoDaemonState(final DaemonPaths paths) {
		assertThat(paths.discoveryFile()).doesNotExist();
		assertThat(paths.socketPath()).doesNotExist();
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
