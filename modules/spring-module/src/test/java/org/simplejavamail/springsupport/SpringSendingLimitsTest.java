package org.simplejavamail.springsupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.SendingRateLimit;
import org.simplejavamail.config.ConfigDiagnosticGroup;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringSendingLimitsTest {

	@Test
	void environmentResolvesBothRulesAndRetainsTheirSources() throws Exception {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("company-mail", Map.of(
					"simplejavamail.smtp.host", "localhost", "simplejavamail.smtp.port", "25",
					"simplejavamail.smtp.ratelimit.messages.limit", "30", "simplejavamail.smtp.ratelimit.messages.period", "PT1M",
					"simplejavamail.smtp.ratelimit.recipients.limit", "100", "simplejavamail.smtp.ratelimit.recipients.period", "PT1M",
					"simplejavamail.smtp.ratelimit.group", "account", "simplejavamail.smtp.ratelimit.allowbursts", "false")));
			context.register(SimpleJavaMailSpringSupport.class);
			context.refresh();
			final SimpleJavaMail factory = context.getBean(SimpleJavaMail.class);
			try (Mailer mailer = factory.mailerBuilder().buildMailer()) {
				assertThat(mailer.getOperationalConfig().getMessageRateLimit()).isEqualTo(new SendingRateLimit(30, Duration.ofMinutes(1)));
				assertThat(mailer.getOperationalConfig().getRecipientRateLimit()).isEqualTo(new SendingRateLimit(100, Duration.ofMinutes(1)));
				assertThat(mailer.getOperationalConfig().isRateLimitBurstsAllowed()).isFalse();
				assertThat(mailer.getOperationalConfig().getRateLimitGroup()).isEqualTo("account");
				assertThat(factory.getConfig().getDiagnostics().getProperties(ConfigDiagnosticGroup.EXECUTION_AND_POOLING))
						.filteredOn(property -> property.getPropertyName().contains(".ratelimit."))
						.hasSize(6).allSatisfy(property -> assertThat(property.getSourceName()).isEqualTo("company-mail"));
			}
		}
	}
}
