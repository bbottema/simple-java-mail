package org.simplejavamail.springsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ensures plain Spring uses the same final-property guard as a Java-created Mailer. */
class SimpleJavaMailSpringTlsConfigurationTest {

	private static final String REQUIRED = "simplejavamail.extraproperties.mail.smtp.starttls.required";

	@ParameterizedTest
	@ValueSource(strings = {"SMTP_TLS", "SMTP_OAUTH2"})
	void rejectsAContradictoryWildcardPropertyDuringContextStartup(final String strategy) {
		try (AnnotationConfigApplicationContext context = context(strategy)) {
			assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(MailException.class)
					.hasStackTraceContaining(strategy + " requires STARTTLS, but mail.smtp.starttls.required disables it.");
		}
	}

	@Test
	void higherPriorityPlaceholderCanCorrectTheOverride() {
		try (AnnotationConfigApplicationContext context = context("SMTP_TLS")) {
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("deployment", Map.of(
					REQUIRED, "${smtp.require-tls}", "smtp.require-tls", "true")));
			context.refresh();
			assertThat(context.getBean(Mailer.class).getSession().getProperty("mail.smtp.starttls.required")).isEqualTo("true");
			assertThat(context.getBean(SimpleJavaMailConfig.class).getDiagnostics().toString())
					.contains(REQUIRED + " = true (source: deployment)");
		}
	}

	private static AnnotationConfigApplicationContext context(final String strategy) {
		final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("application", Map.of(
				"simplejavamail.smtp.host", "localhost", "simplejavamail.transportstrategy", strategy,
				"simplejavamail.smtp.username", "test-user", "simplejavamail.smtp.password", "fake-password-or-token",
				REQUIRED, "false")));
		context.register(SimpleJavaMailSpringSupport.class);
		return context;
	}
}
