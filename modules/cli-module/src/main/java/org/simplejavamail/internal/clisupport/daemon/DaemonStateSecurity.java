package org.simplejavamail.internal.clisupport.daemon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Establishes and verifies owner-only daemon state on both POSIX-permission and ACL filesystems.
 * The authentication secret makes unverifiable privacy a hard failure: links, wrong path types, group/other POSIX bits,
 * foreign ACL principals, and filesystems without an enforceable permission model are all rejected.
 */
final class DaemonStateSecurity {
	private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	private static final Set<PosixFilePermission> FILE_PERMISSIONS = EnumSet.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

	private DaemonStateSecurity() {
	}

	static void ensurePrivateDirectory(final Path directory) throws IOException {
		try {
			if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(directory)) {
				throw new DaemonSecurityException("Daemon state directory may not be a symbolic link");
			}
			Files.createDirectories(directory);
			if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
				throw new DaemonSecurityException("Daemon state path is not a directory");
			}
			enforceAndVerifyPrivatePermissions(directory, StatePathType.DIRECTORY);
		} catch (DaemonSecurityException e) {
			throw e;
		} catch (IOException e) {
			throw new DaemonSecurityException("Unable to establish a private daemon state directory", e);
		}
	}

	static void ensurePrivateFile(final Path file) throws IOException {
		try {
			if (Files.isSymbolicLink(file)) {
				throw new DaemonSecurityException("Daemon state file may not be a symbolic link");
			}
			enforceAndVerifyPrivatePermissions(file, StatePathType.FILE);
		} catch (DaemonSecurityException e) {
			throw e;
		} catch (IOException e) {
			throw new DaemonSecurityException("Unable to establish private daemon state-file permissions", e);
		}
	}

	static void verifyPrivateFile(final Path file) throws IOException {
		try {
			verifyPrivatePath(file, StatePathType.FILE);
		} catch (DaemonSecurityException e) {
			throw e;
		} catch (IOException e) {
			throw new DaemonSecurityException("Unable to verify private daemon state-file permissions", e);
		}
	}

	static void verifyPrivateDirectory(final Path directory) throws IOException {
		try {
			verifyPrivatePath(directory, StatePathType.DIRECTORY);
		} catch (DaemonSecurityException e) {
			throw e;
		} catch (IOException e) {
			throw new DaemonSecurityException("Unable to verify private daemon state-directory permissions", e);
		}
	}

	private static void verifyPrivatePath(final Path path, final StatePathType pathType) throws IOException {
		if (Files.isSymbolicLink(path)) {
			throw new DaemonSecurityException("Daemon state path may not be a symbolic link");
		}
		if (!pathType.existsAt(path)) {
			throw new DaemonSecurityException("Daemon state path has the wrong type");
		}
		final PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
				LinkOption.NOFOLLOW_LINKS);
		if (posix != null) {
			final Set<PosixFilePermission> expected = pathType.permissions();
			if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(expected)) {
				throw new DaemonSecurityException("Daemon state path is not owner-only");
			}
			return;
		}
		final AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class,
				LinkOption.NOFOLLOW_LINKS);
		if (acl == null) {
			throw new DaemonSecurityException("Filesystem cannot verify private daemon state permissions");
		}
		final UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
		verifyOwnerOnlyAcl(acl, owner);
	}

	private static void enforceAndVerifyPrivatePermissions(final Path path, final StatePathType pathType)
			throws IOException {
		final PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
				LinkOption.NOFOLLOW_LINKS);
		if (posix != null) {
			final Set<PosixFilePermission> expected = pathType.permissions();
			Files.setPosixFilePermissions(path, expected);
			if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(expected)) {
				throw new DaemonSecurityException("Unable to establish owner-only daemon state permissions");
			}
			return;
		}

		final AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class,
				LinkOption.NOFOLLOW_LINKS);
		if (acl == null) {
			throw new DaemonSecurityException("Filesystem cannot enforce private daemon state permissions");
		}
		final UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
		final AclEntry ownerEntry = AclEntry.newBuilder()
				.setType(AclEntryType.ALLOW)
				.setPrincipal(owner)
				.setPermissions(EnumSet.allOf(AclEntryPermission.class))
				.build();
		acl.setAcl(List.of(ownerEntry));
		verifyOwnerOnlyAcl(acl, owner);
	}

	private static void verifyOwnerOnlyAcl(final AclFileAttributeView acl, final UserPrincipal owner)
			throws IOException {
		for (final AclEntry entry : acl.getAcl()) {
			if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)) {
				throw new DaemonSecurityException("Daemon state ACL permits another principal");
			}
		}
	}

	private enum StatePathType {
		DIRECTORY(DIRECTORY_PERMISSIONS),
		FILE(FILE_PERMISSIONS);

		private final Set<PosixFilePermission> permissions;

		StatePathType(final Set<PosixFilePermission> permissions) {
			this.permissions = permissions;
		}

		private boolean existsAt(final Path path) {
			return this == DIRECTORY
					? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
					: Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
		}

		private Set<PosixFilePermission> permissions() {
			return permissions;
		}
	}
}
