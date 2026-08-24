package org.simplejavamail.internal.clisupport;

import org.jetbrains.annotations.NotNull;

/** Bounded, request-owned output and the stable result category of one CLI command. */
public record CliExecutionResult(@NotNull CliExitCode category, @NotNull String stdout, @NotNull String stderr) {

	public int exitCode() {
		return category.code();
	}

	public static CliExecutionResult success(final String stdout, final String stderr) {
		return new CliExecutionResult(CliExitCode.SUCCESS, stdout, stderr);
	}
}
