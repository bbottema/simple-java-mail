package org.simplejavamail.config;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.OperationalConfig;

import java.time.Duration;
import java.util.Properties;
import java.util.Arrays;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MailSendRetryConfigurationTest {
    @Test void optionalPropertyDefaultsAgreeWithTheJavaPolicyBuilder() throws Exception {
        final MailSendRetryPolicy expected = MailSendRetryPolicy.builder().withMaximumAttempts(3).withRetryDelay(Duration.ofSeconds(1)).build();
        try (Mailer mailer = factory(policyProperties()).mailerBuilder().buildMailer()) {
            assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy()).isEqualTo(expected);
        }
    }

    @Test void remainsDisabledAndResolvesResourceDefaultsInTheBuilder() throws Exception {
        try (Mailer mailer = factory(new Properties()).mailerBuilder().buildMailer()) {
            final OperationalConfig config = mailer.getOperationalConfig();
            assertThat(config.getMailSendRetryPolicy().isRetryEnabled()).isFalse();
            assertThat(config.getMailSendRetryPolicy().getWaitingCapacity()).isEqualTo(100);
            assertThat(config.getMailSendRetryPolicy().getMaximumMessageSize()).isEqualTo(32L * 1024 * 1024);
            assertThat(config.getMailSendRetryPolicy().getMemoryLimit()).isEqualTo(128L * 1024 * 1024);
        }
    }

    @Test void parsesTypedPolicyAndKeepsFactorySnapshotsSeparate() throws Exception {
        final Properties properties = policyProperties();
        properties.setProperty("simplejavamail.smtp.retry.jitter", "0.2");
        properties.setProperty("simplejavamail.defaults.mailsend.retry.maximummessagesize", "1024");
        properties.setProperty("simplejavamail.defaults.mailsend.retry.memorylimit", "4096");
        final SimpleJavaMail mail = factory(properties);
        properties.setProperty("simplejavamail.smtp.retry.attempts", "99");
        try (Mailer configured = mail.mailerBuilder().buildMailer(); Mailer independent = factory(new Properties()).mailerBuilder().buildMailer()) {
            assertThat(configured.getOperationalConfig().getMailSendRetryPolicy().getMaximumAttempts()).isEqualTo(3);
            assertThat(configured.getOperationalConfig().getMailSendRetryPolicy().getRetryJitter()).isEqualTo(0.2);
            assertThat(configured.getOperationalConfig().getMailSendRetryPolicy().getMaximumMessageSize()).isEqualTo(1024);
            assertThat(independent.getOperationalConfig().getMailSendRetryPolicy().isRetryEnabled()).isFalse();
            assertThat(mail.getConfig().getDiagnostics().getProperties(ConfigDiagnosticGroup.SMTP_CONNECTION))
                    .anySatisfy(entry -> {
                        assertThat(entry.getPropertyName()).isEqualTo("simplejavamail.smtp.retry.jitter");
                        assertThat(entry.getDisplayValue()).isEqualTo("0.2");
                        assertThat(entry.getSourceName()).isEqualTo("retry test");
                        assertThat(entry.isRedacted()).isFalse();
                    });
        }
    }

    @Test void supportsWholePolicyReplacementAndClearing() throws Exception {
        final MailSendRetryPolicy replacement = MailSendRetryPolicy.builder().withMaximumAttempts(4).withRetryDelay(Duration.ofSeconds(5))
                .withWaitingCapacity(2).withMaximumMessageSize(1024).withMemoryLimit(4096).build();
        try (Mailer mailer = factory(policyProperties()).mailerBuilder().withMailSendRetryPolicy(replacement).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy()).isSameAs(replacement);
        }
        try (Mailer mailer = factory(policyProperties()).mailerBuilder().withMailSendRetryPolicy(replacement).clearMailSendRetryPolicy().buildMailer()) {
            assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy()).isEqualTo(MailSendRetryPolicy.builder().build());
        }
        assertThatNullPointerException().isThrownBy(() -> factory(new Properties()).mailerBuilder().withMailSendRetryPolicy(null));
    }

    @Test void rejectsIncompleteConfigurationAndInvalidResourceBudgets() {
        final Properties incomplete = new Properties();
        incomplete.setProperty("simplejavamail.smtp.retry.attempts", "3");
        assertThatIllegalArgumentException().isThrownBy(() -> factory(incomplete).mailerBuilder()).withMessageContaining("retry.delay");
        assertThatIllegalArgumentException().isThrownBy(() -> MailSendRetryPolicy.builder().withWaitingCapacity(0).build());
        assertThatIllegalArgumentException().isThrownBy(() -> MailSendRetryPolicy.builder().withMemoryLimit(1).build());
    }

    @Test void enforcesLockedPolicyAndResourceValuesWithoutReplacingOtherSettings() throws Exception {
        final Properties locks = new Properties();
        locks.setProperty("simplejavamail.locked.smtp.retry.attempts", "3");
        locks.setProperty("simplejavamail.locked.smtp.retry.delay", "PT1S");
        locks.setProperty("simplejavamail.locked.defaults.mailsend.retry.waitingcapacity", "2");
        final SimpleJavaMail mail = factory(locks);
        try (Mailer equal = mail.mailerBuilder().withMailSendRetryPolicy(
                MailSendRetryPolicy.builder().withMaximumAttempts(3).withRetryDelay(Duration.ofSeconds(1)).withWaitingCapacity(2).build()).buildMailer()) {
            assertThat(equal.getOperationalConfig().getMailSendRetryPolicy().getWaitingCapacity()).isEqualTo(2);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> mail.mailerBuilder().clearMailSendRetryPolicy().buildMailer())
                .withMessageContaining("simplejavamail.locked.smtp.retry");
        assertThatIllegalArgumentException().isThrownBy(() -> mail.mailerBuilder().withMailSendRetryPolicy(
                MailSendRetryPolicy.builder().withMaximumAttempts(3).withRetryDelay(Duration.ofSeconds(1)).build()).buildMailer())
                .withMessageContaining("simplejavamail.locked.defaults.mailsend.retry.waitingcapacity");
    }

    @Test void propertyResourceLimitsDoNotEnableRetriesAndClearingResetsTheWholePolicy() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("simplejavamail.defaults.mailsend.retry.waitingcapacity", "2");
        properties.setProperty("simplejavamail.defaults.mailsend.retry.maximummessagesize", "1024");
        properties.setProperty("simplejavamail.defaults.mailsend.retry.memorylimit", "4096");
        final SimpleJavaMail mail = factory(properties);
        try (Mailer configured = mail.mailerBuilder().buildMailer(); Mailer cleared = mail.mailerBuilder().clearMailSendRetryPolicy().buildMailer()) {
            final MailSendRetryPolicy policy = configured.getOperationalConfig().getMailSendRetryPolicy();
            assertThat(policy.isRetryEnabled()).isFalse();
            assertThat(policy.getMaximumAttempts()).isNull();
            assertThat(policy.getRetryDelay()).isNull();
            assertThat(policy.getWaitingCapacity()).isEqualTo(2);
            assertThat(policy.getMaximumMessageSize()).isEqualTo(1024);
            assertThat(policy.getMemoryLimit()).isEqualTo(4096);
            assertThat(cleared.getOperationalConfig().getMailSendRetryPolicy()).isEqualTo(MailSendRetryPolicy.builder().build());
        }
    }

    @Test void canonicalJavaApiHasNoStandaloneResourceSettersResetsOrGetters() {
        for (Class<?> type : new Class<?>[] {MailerGenericBuilder.class, OperationalConfig.class}) {
            assertThat(Arrays.stream(type.getMethods()).map(Method::getName))
                    .noneMatch(name -> name.matches("(with|reset|get)MailSendRetry(WaitingCapacity|MaximumMessageSize|MemoryLimit)"));
        }
    }

    private static Properties policyProperties() {
        final Properties properties = new Properties();
        properties.setProperty("simplejavamail.smtp.retry.attempts", "3");
        properties.setProperty("simplejavamail.smtp.retry.delay", "PT1S");
        return properties;
    }

    private static SimpleJavaMail factory(final Properties properties) {
        final Properties input = new Properties();
        input.setProperty("simplejavamail.smtp.host", "localhost");
        input.putAll(properties);
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withProperties("retry test", input).load());
    }
}
