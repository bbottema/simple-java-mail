package org.simplejavamail.springsupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigDiagnosticGroup;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringMailSendRetryTest {
    @Test
    void environmentLoadsTypedPolicyResourceLimitsAndLockedProvenance() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("company-retries", Map.of(
                    "simplejavamail.smtp.host", "localhost",
                    "simplejavamail.locked.smtp.retry.attempts", "3",
                    "simplejavamail.smtp.retry.delay", "PT30S",
                    "simplejavamail.smtp.retry.backoffmultiplier", "2",
                    "simplejavamail.smtp.retry.maximumdelay", "PT5M",
                    "simplejavamail.smtp.retry.jitter", "0.2",
                    "simplejavamail.defaults.mailsend.retry.maximummessagesize", "33554432",
                    "simplejavamail.defaults.mailsend.retry.memorylimit", "134217728")));
            context.register(SimpleJavaMailSpringSupport.class);
            context.refresh();
            final SimpleJavaMail factory = context.getBean(SimpleJavaMail.class);
            try (Mailer mailer = factory.mailerBuilder().buildMailer()) {
                assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy().getMaximumAttempts()).isEqualTo(3);
                assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy().getRetryDelay()).isEqualTo(Duration.ofSeconds(30));
                assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy().getRetryJitter()).isEqualTo(0.2);
                assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy().getMemoryLimit()).isEqualTo(134217728L);
                assertThatThrownBy(() -> factory.mailerBuilder().clearMailSendRetryPolicy().buildMailer()).hasMessageContaining("locked");
                assertThat(factory.getConfig().getDiagnostics().getProperties(ConfigDiagnosticGroup.SMTP_CONNECTION))
                        .filteredOn(property -> property.getPropertyName().contains(".retry."))
                        .hasSize(5).allSatisfy(property -> assertThat(property.getSourceName()).isEqualTo("company-retries"));
            }
        }
    }
}
