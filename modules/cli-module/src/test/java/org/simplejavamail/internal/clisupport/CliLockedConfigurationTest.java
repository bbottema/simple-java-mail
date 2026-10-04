package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CliLockedConfigurationTest {
	@Test
	void daemonProfilesDistinguishLockedAndOrdinaryValues() {
		final SimpleJavaMailConfig ordinary = ConfigLoader.builder().withMap(Map.of("simplejavamail.smtp.host", "relay.example.org")).load();
		final SimpleJavaMailConfig locked = ConfigLoader.builder().withMap(Map.of("simplejavamail.locked.smtp.host", "relay.example.org")).load();
		assertThat(CliMailerProfile.create(ordinary, List.of(), new byte[32]))
				.isNotEqualTo(CliMailerProfile.create(locked, List.of(), new byte[32]));
		assertThat(CliMailerProfile.create(locked, List.of(), new byte[32]))
				.isEqualTo(CliMailerProfile.create(ConfigLoader.builder().withConfig(locked).load(), List.of(), new byte[32]));
	}

	@ParameterizedTest
	@ValueSource(strings = {"send", "connect", "probe"})
	void explicitHostOptionCannotReplaceLockedConfiguration(final String command) throws Exception {
		final SimpleJavaMailConfig config = ConfigLoader.builder().withMap(Map.of("simplejavamail.locked.smtp.host", "relay.example.org")).load();
		try (CliExecutionEnvironment environment = new CliExecutionEnvironment(config, new OneShotMailerProvider())) {
			final String[] arguments = command.equals("send")
					? new String[]{command, "--mailer:withSMTPServer", "other.example.org", "25", "--email:startingBlank", "--email:from", "sender@example.org",
							"--email:to", "receiver@example.org"}
					: new String[]{command, "--mailer:withSMTPServer", "other.example.org", "25"};
			final CliExecutionResult result = CliSupport.execute(arguments, Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
			assertThat(result.exitCode()).as(result.stderr()).isEqualTo(CliExitCode.COMMAND_FAILED.code());
			assertThat(result.stderr()).contains("locked.smtp.host", "Remove the conflicting customization");
		}
	}
}
