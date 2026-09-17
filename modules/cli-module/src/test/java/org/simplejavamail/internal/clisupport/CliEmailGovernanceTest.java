package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.config.ConfigLoader;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CliEmailGovernanceTest {

	@Test
	void generatedHelpOmitsTheRemovedDkimClearOption() {
		try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), mock(MailerProvider.class))) {
			final CliExecutionResult result = execute(environment, "send", "--help");
			assertThat(result.exitCode()).isZero();
			assertThat(result.stdout())
					.doesNotContain("--mailer:clearDefaultDkimSigning", "--mailer:withDefaultDkimSigning")
					.contains("--email:clearDkim", "--email:ignoringDefaultsYesNo");
		}
	}

	@Test
	void removedDkimClearOptionFailsBeforeAcquiringAMailer() {
		final MailerProvider provider = mock(MailerProvider.class);
		try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
			final CliExecutionResult result = execute(environment, "connect", "--mailer:clearDefaultDkimSigning");
			assertThat(result.exitCode()).isEqualTo(CliExitCode.CLI_ERROR.code());
			assertThat(result.stderr()).contains("--mailer:clearDefaultDkimSigning");
			verifyNoInteractions(provider);
		}
	}

	private static CliExecutionResult execute(final CliExecutionEnvironment environment, final String... arguments) {
		return CliSupport.execute(arguments, Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
	}
}
