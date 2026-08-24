package org.simplejavamail.internal.clisupport;

/** Stable process exit categories for the CLI and its daemon bootstrap. */
public enum CliExitCode {
	SUCCESS(0),
	CLI_ERROR(2),
	COMMAND_FAILED(3),
	DAEMON_ABSENT(10),
	DAEMON_START_FAILED(11),
	DAEMON_SECURITY(12),
	DAEMON_INCOMPATIBLE(13),
	DAEMON_BUSY(14),
	DAEMON_AMBIGUOUS(15),
	DAEMON_REFUSED(16);

	private final int code;

	CliExitCode(final int code) {
		this.code = code;
	}

	public int code() {
		return code;
	}

	public static CliExitCode fromCode(final int code) {
		for (final CliExitCode value : values()) {
			if (value.code == code) {
				return value;
			}
		}
		return COMMAND_FAILED;
	}

	public static boolean isKnown(final int code) {
		for (final CliExitCode value : values()) {
			if (value.code == code) {
				return true;
			}
		}
		return false;
	}
}
