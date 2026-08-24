package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CliExitCodeTest {
	@Test
	void publicProcessExitCategoriesRemainStable() {
		assertThat(CliExitCode.values())
				.extracting(CliExitCode::name, CliExitCode::code)
				.containsExactly(
						tuple("SUCCESS", 0),
						tuple("CLI_ERROR", 2),
						tuple("COMMAND_FAILED", 3),
						tuple("DAEMON_ABSENT", 10),
						tuple("DAEMON_START_FAILED", 11),
						tuple("DAEMON_SECURITY", 12),
						tuple("DAEMON_INCOMPATIBLE", 13),
						tuple("DAEMON_BUSY", 14),
						tuple("DAEMON_AMBIGUOUS", 15),
						tuple("DAEMON_REFUSED", 16));
	}

	private static org.assertj.core.groups.Tuple tuple(final String name, final int code) {
		return org.assertj.core.groups.Tuple.tuple(name, code);
	}
}
