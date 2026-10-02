package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.SendingRateLimit;
import org.simplejavamail.config.ConfigLoader;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CliSendingLimitsTest {

	@Test
	void generatedTypedOptionsReachOperationalConfiguration() {
		final AtomicReference<OperationalConfig> selected = new AtomicReference<>();
		final OneShotMailerProvider delegate = new OneShotMailerProvider();
		final MailerProvider provider = (profile, factory) -> delegate.acquire(profile, () -> {
			final Mailer mailer = factory.get();
			selected.set(mailer.getOperationalConfig());
			return mailer;
		});
		try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
			final CliExecutionResult result = execute(environment, "send", "--email:startingBlank", "--email:from", "sender@example.org",
					"--email:to", "recipient@example.org", "--mailer:withTransportModeLoggingOnly", "true",
					"--mailer:withMessageRateLimit", "30", "PT1M", "--mailer:withRecipientRateLimit", "100", "PT1M",
					"--mailer:withRateLimitGroup", "company-account", "--mailer:withRateLimitBurstsAllowed", "false");
			assertThat(result.exitCode()).as(result.stderr()).isZero();
			assertThat(result.stdout()).isEmpty();
			assertThat(selected.get().getMessageRateLimit()).isEqualTo(new SendingRateLimit(30, Duration.ofMinutes(1)));
			assertThat(selected.get().getRecipientRateLimit()).isEqualTo(new SendingRateLimit(100, Duration.ofMinutes(1)));
			assertThat(selected.get().getRateLimitGroup()).isEqualTo("company-account");
			assertThat(selected.get().isRateLimitBurstsAllowed()).isFalse();
		}
	}

	@Test
	void generatedHelpExplainsCountingAndDoesNotIntroduceAnImplicitRate() {
		try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), new OneShotMailerProvider())) {
			final CliExecutionResult help = execute(environment, "send", "--mailer:withMessageRateLimit--help");
			assertThat(help.exitCode()).isZero();
			assertThat(help.stdout()).contains("count(=NUM)", "period(=DURATION)", "rolling", "no limit by default", "CustomMailer");
			final CliExecutionResult all = execute(environment, "send", "--help");
			assertThat(all.stdout()).contains("--mailer:withRecipientRateLimit", "--mailer:resetMessageRateLimit", "--mailer:resetRateLimitGroup");
		}
	}

	private static CliExecutionResult execute(final CliExecutionEnvironment environment, final String... arguments) {
		return CliSupport.execute(arguments, Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
	}
}
