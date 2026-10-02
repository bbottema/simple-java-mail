package org.simplejavamail.config;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.SendingRateLimit;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_MESSAGE_RATE_LIMIT;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_MESSAGE_RATE_PERIOD;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_RATE_LIMIT_ALLOW_BURSTS;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_RATE_LIMIT_GROUP;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_RECIPIENT_RATE_LIMIT;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_RECIPIENT_RATE_PERIOD;

class SendingRateLimitConfigTest {

	@Test
	void snapshotPrecedenceAndDiagnosticsRemainIndependentFromBuilderOverrides() throws Exception {
		final Map<String, Object> properties = new HashMap<>(Map.of(
				SMTP_MESSAGE_RATE_LIMIT.key(), "30", SMTP_MESSAGE_RATE_PERIOD.key(), "PT60S",
				SMTP_RECIPIENT_RATE_LIMIT.key(), 100, SMTP_RECIPIENT_RATE_PERIOD.key(), Duration.ofMinutes(1),
				SMTP_RATE_LIMIT_GROUP.key(), "company-account", SMTP_RATE_LIMIT_ALLOW_BURSTS.key(), false));
		final SimpleJavaMailConfig config = ConfigLoader.builder().withMap("file", properties)
				.withMap("environment", Map.of(SMTP_MESSAGE_RATE_LIMIT.key(), 40)).load();
		final SimpleJavaMail factory = SimpleJavaMail.withConfig(config);
		properties.clear();
		final MailerRegularBuilder<?> builder = factory.mailerBuilder().withSMTPServer("localhost", 25);
		assertThat(builder.getMessageRateLimit()).isEqualTo(new SendingRateLimit(40, Duration.ofMinutes(1)));
		assertThat(builder.getRecipientRateLimit()).isEqualTo(new SendingRateLimit(100, Duration.ofMinutes(1)));
		assertThat(builder.getRateLimitGroup()).isEqualTo("company-account");
		assertThat(builder.isRateLimitBurstsAllowed()).isFalse();
		try (Mailer mailer = builder.withMessageRateLimit(10, Duration.ofSeconds(1)).buildMailer()) {
			assertThat(mailer.getOperationalConfig().getMessageRateLimit()).isEqualTo(new SendingRateLimit(10, Duration.ofSeconds(1)));
			assertThat(config.getDiagnostics().toString()).contains("40 (source: environment)", "PT1M (source: file)", "false (source: file)");
			assertThat(config.getDiagnostics().getProperties(ConfigDiagnosticGroup.EXECUTION_AND_POOLING)).hasSize(6)
					.allSatisfy(property -> assertThat(property.isRedacted()).isFalse());
		}
		assertThat(factory.mailerBuilder().getMessageRateLimit()).isEqualTo(new SendingRateLimit(40, Duration.ofMinutes(1)));
		assertThat(SimpleJavaMail.withConfig(ConfigLoader.builder().load()).mailerBuilder().getMessageRateLimit()).isNull();
	}

	@Test
	void resetsRemovePropertyRulesAndRepeatedSettersReplaceThem() {
		final MailerRegularBuilder<?> builder = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
				SMTP_MESSAGE_RATE_LIMIT.key(), 30, SMTP_MESSAGE_RATE_PERIOD.key(), "PT1M",
				SMTP_RECIPIENT_RATE_LIMIT.key(), 100, SMTP_RECIPIENT_RATE_PERIOD.key(), "PT1M",
				SMTP_RATE_LIMIT_GROUP.key(), "group", SMTP_RATE_LIMIT_ALLOW_BURSTS.key(), false)).load()).mailerBuilder();
		builder.withMessageRateLimit(10, Duration.ofSeconds(2)).withMessageRateLimit(20, Duration.ofSeconds(3));
		assertThat(builder.getMessageRateLimit()).isEqualTo(new SendingRateLimit(20, Duration.ofSeconds(3)));
		builder.resetMessageRateLimit().resetRecipientRateLimit().resetRateLimitGroup().resetRateLimitBurstsAllowed();
		assertThat(builder.getMessageRateLimit()).isNull();
		assertThat(builder.getRecipientRateLimit()).isNull();
		assertThat(builder.getRateLimitGroup()).isNull();
		assertThat(builder.isRateLimitBurstsAllowed()).isTrue();
	}

	@Test
	void incompleteOrInvalidRulesExplainHowToFixConfiguration() {
		for (Map<String, ?> incomplete : java.util.List.of(Map.of(SMTP_MESSAGE_RATE_LIMIT.key(), 30),
				Map.of(SMTP_MESSAGE_RATE_PERIOD.key(), "PT1M"))) {
			assertThatThrownBy(() -> SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(incomplete).load()).mailerBuilder())
					.hasMessageContaining("needs both").hasMessageContaining("remove both");
		}
		for (String invalid : new String[]{"PT0S", "PT-1S", "P999999D"}) {
			assertThatThrownBy(() -> SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
					SMTP_MESSAGE_RATE_LIMIT.key(), 30, SMTP_MESSAGE_RATE_PERIOD.key(), invalid)).load()).mailerBuilder())
					.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> ConfigLoader.builder().withMap(Map.of(SMTP_MESSAGE_RATE_LIMIT.key(), 0)).load())
				.isInstanceOf(IllegalArgumentException.class);
		final MailerRegularBuilder<?> builder = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).mailerBuilder();
		assertThatThrownBy(() -> builder.withRateLimitGroup(" ")).hasMessageContaining("resetRateLimitGroup");
		assertThatThrownBy(() -> builder.withRateLimitGroup(null)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void groupDeclarationsMustAgreeButIndependentFactoriesDoNotConflict() throws Exception {
		final SimpleJavaMailConfig config = ConfigLoader.builder().load();
		final SimpleJavaMail factory = SimpleJavaMail.withConfig(config);
		try (Mailer first = factory.mailerBuilder().withSMTPServer("localhost", 25).withRateLimitGroup("shared")
				.withMessageRateLimit(30, Duration.ofMinutes(1)).buildMailer();
			 Mailer independent = SimpleJavaMail.withConfig(config).mailerBuilder().withSMTPServer("localhost", 25).withRateLimitGroup("shared")
					.withMessageRateLimit(50, Duration.ofMinutes(1)).buildMailer()) {
			assertThatThrownBy(() -> factory.mailerBuilder().withSMTPServer("localhost", 25).withRateLimitGroup("shared")
					.withMessageRateLimit(50, Duration.ofMinutes(1)).buildMailer()).hasMessageContaining("must use the same");
			assertThat(independent.getOperationalConfig().getMessageRateLimit().getCount()).isEqualTo(50);
		}
	}
}
