package org.simplejavamail.config;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY;

class MailSendRetryInheritanceTest {
    @Test
    void freshResourceChoiceInheritsCapturedScheduleAndOtherResourceProperties() throws Exception {
        final Properties properties = configuredProperties();
        final SimpleJavaMail factory = factory(properties);
        properties.setProperty("simplejavamail.smtp.retry.attempts", "99");
        properties.setProperty("simplejavamail.defaults.mailsend.retry.waitingcapacity", "99");

        try (Mailer mailer = factory.mailerBuilder().withMailSendRetryPolicy(
                MailSendRetryPolicy.builder().withMemoryLimit(8192).build()).buildMailer()) {
            final MailSendRetryPolicy resolved = mailer.getOperationalConfig().getMailSendRetryPolicy();
            assertThat(resolved.getMaximumAttempts()).isEqualTo(3);
            assertThat(resolved.getRetryDelay()).isEqualTo(Duration.ofSeconds(2));
            assertThat(resolved.getRetryBackoffMultiplier()).isEqualTo(2);
            assertThat(resolved.getMaximumRetryDelay()).isEqualTo(Duration.ofSeconds(20));
            assertThat(resolved.getRetryJitter()).isEqualTo(0.2);
            assertThat(resolved.getWaitingCapacity()).isEqualTo(7);
            assertThat(resolved.getMaximumMessageSize()).isEqualTo(1024);
            assertThat(resolved.getMemoryLimit()).isEqualTo(8192);
        }
    }

    @Test
    void replacingSuppliedChoicesRestoresFactoryInheritanceInsteadOfPreviousJavaValues() throws Exception {
        final MailerRegularBuilder builder = factory(configuredProperties()).mailerBuilder();
        builder.withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumAttempts(5)
                .withRetryDelay(Duration.ofSeconds(4)).withWaitingCapacity(11).build());
        builder.withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMemoryLimit(8192).build());
        try (Mailer mailer = builder.buildMailer()) {
            final MailSendRetryPolicy resolved = mailer.getOperationalConfig().getMailSendRetryPolicy();
            assertThat(resolved.getMaximumAttempts()).isEqualTo(3);
            assertThat(resolved.getRetryDelay()).isEqualTo(Duration.ofSeconds(2));
            assertThat(resolved.getWaitingCapacity()).isEqualTo(7);
            assertThat(resolved.getMemoryLimit()).isEqualTo(8192);
        }
    }

    @Test
    void explicitFallbackValuedChoicesAndResetsOverrideConfiguredValues() throws Exception {
        final MailSendRetryPolicy supplied = MailSendRetryPolicy.builder()
                .withWaitingCapacity(DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY)
                .resetRetryBackoff().withRetryJitter(0).resetMemoryLimit().build();
        try (Mailer mailer = factory(configuredProperties()).mailerBuilder().withMailSendRetryPolicy(supplied).buildMailer()) {
            final MailSendRetryPolicy resolved = mailer.getOperationalConfig().getMailSendRetryPolicy();
            assertThat(resolved.getMaximumAttempts()).isEqualTo(3);
            assertThat(resolved.getRetryBackoffMultiplier()).isOne();
            assertThat(resolved.getMaximumRetryDelay()).isNull();
            assertThat(resolved.getRetryJitter()).isZero();
            assertThat(resolved.getWaitingCapacity()).isEqualTo(DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY);
            assertThat(resolved.getMaximumMessageSize()).isEqualTo(1024);
            assertThat(resolved.getMemoryLimit()).isEqualTo(DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT);
        }
    }

    @Test
    void explicitScheduleClearDoesNotRestorePropertiesAndStillInheritsResourceLimits() throws Exception {
        final MailSendRetryPolicy disabled = MailSendRetryPolicy.builder().clearRetrySchedule().build();
        try (Mailer mailer = factory(configuredProperties()).mailerBuilder().withMailSendRetryPolicy(disabled).buildMailer()) {
            final MailSendRetryPolicy resolved = mailer.getOperationalConfig().getMailSendRetryPolicy();
            assertThat(resolved.isRetryEnabled()).isFalse();
            assertThat(resolved.getMaximumAttempts()).isNull();
            assertThat(resolved.getRetryDelay()).isNull();
            assertThat(resolved.getRetryBackoffMultiplier()).isOne();
            assertThat(resolved.getMaximumRetryDelay()).isNull();
            assertThat(resolved.getRetryJitter()).isZero();
            assertThat(resolved.getWaitingCapacity()).isEqualTo(7);
            assertThat(resolved.getMemoryLimit()).isEqualTo(4096);
        }
    }

    @Test
    void javaAndCapturedPropertiesCanCompleteEachOthersScheduleBeforeFinalValidation() throws Exception {
        final Properties onlyDelay = new Properties();
        onlyDelay.setProperty("simplejavamail.smtp.retry.delay", "PT2S");
        try (Mailer mailer = factory(onlyDelay).mailerBuilder().withMailSendRetryPolicy(
                MailSendRetryPolicy.builder().withMaximumAttempts(4).build()).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy().getMaximumAttempts()).isEqualTo(4);
            assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy().getRetryDelay()).isEqualTo(Duration.ofSeconds(2));
        }
        final Properties onlyAttempts = new Properties();
        onlyAttempts.setProperty("simplejavamail.smtp.retry.attempts", "3");
        try (Mailer mailer = factory(onlyAttempts).mailerBuilder().withMailSendRetryPolicy(
                MailSendRetryPolicy.builder().withRetryDelay(Duration.ofSeconds(5)).build()).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy().getMaximumAttempts()).isEqualTo(3);
            assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy().getRetryDelay()).isEqualTo(Duration.ofSeconds(5));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> factory(onlyAttempts).mailerBuilder().buildMailer());
        assertThatIllegalArgumentException().isThrownBy(() -> factory(new Properties()).mailerBuilder()
                .withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumAttempts(3).build()).buildMailer());
    }

    @Test
    void inheritedLocksRemainBindingWithoutTreatingOmissionAsConflict() throws Exception {
        final Properties properties = configuredProperties();
        properties.setProperty("simplejavamail.locked.smtp.retry.attempts", "3");
        properties.setProperty("simplejavamail.locked.defaults.mailsend.retry.waitingcapacity", "7");
        final SimpleJavaMail factory = factory(properties);
        try (Mailer mailer = factory.mailerBuilder().withMailSendRetryPolicy(
                MailSendRetryPolicy.builder().withMemoryLimit(8192).build()).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy().getMaximumAttempts()).isEqualTo(3);
            assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy().getWaitingCapacity()).isEqualTo(7);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> factory.mailerBuilder()
                .withMailSendRetryPolicy(MailSendRetryPolicy.builder().clearRetrySchedule().build()).buildMailer())
                .withMessageContaining("locked.smtp.retry.attempts");
        assertThatIllegalArgumentException().isThrownBy(() -> factory.mailerBuilder()
                .withMailSendRetryPolicy(MailSendRetryPolicy.builder().resetWaitingCapacity().build()).buildMailer())
                .withMessageContaining("locked.defaults.mailsend.retry.waitingcapacity");
    }

    @Test
    void toBuilderPreservesSuppliedOmissionsAndExplicitClears() throws Exception {
        final MailSendRetryPolicy resourceChoice = MailSendRetryPolicy.builder().withMemoryLimit(8192).build();
        final MailerRegularBuilder builder = factory(configuredProperties()).mailerBuilder();
        builder.withMailSendRetryPolicy(resourceChoice.toBuilder().withWaitingCapacity(9).build());
        final MailSendRetryPolicy supplied = builder.getMailSendRetryPolicy();
        assertThat(supplied.getMaximumAttempts()).isNull();
        assertThat(supplied.getRetryDelay()).isNull();
        assertThat(supplied.getMaximumMessageSize()).isNull();
        builder.withMailSendRetryPolicy(supplied.toBuilder().withMemoryLimit(16384).build());
        try (Mailer mailer = builder.buildMailer()) {
            final MailSendRetryPolicy resolved = mailer.getOperationalConfig().getMailSendRetryPolicy();
            assertThat(resolved.getMaximumAttempts()).isEqualTo(3);
            assertThat(resolved.getWaitingCapacity()).isEqualTo(9);
            assertThat(resolved.getMaximumMessageSize()).isEqualTo(1024);
            assertThat(resolved.getMemoryLimit()).isEqualTo(16384);
            assertThat(supplied.getMemoryLimit()).isEqualTo(8192);
            assertThat(builder.getMailSendRetryPolicy().getMaximumAttempts()).isNull();
        }
        final MailSendRetryPolicy disabled = resourceChoice.toBuilder().clearRetrySchedule().build();
        try (Mailer mailer = factory(configuredProperties()).mailerBuilder().withMailSendRetryPolicy(
                disabled.toBuilder().withWaitingCapacity(9).build()).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy().isRetryEnabled()).isFalse();
        }
    }

    @Test
    void validatesResourceRelationshipsAfterInheritanceRatherThanAgainstUnselectedFallbacks() throws Exception {
        try (Mailer mailer = factory(configuredProperties()).mailerBuilder().withMailSendRetryPolicy(
                MailSendRetryPolicy.builder().withMemoryLimit(2048).build()).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy().getMemoryLimit()).isEqualTo(2048);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> factory(configuredProperties()).mailerBuilder()
                .withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMemoryLimit(512).build()).buildMailer());
        assertThatIllegalArgumentException().isThrownBy(() -> factory(configuredProperties()).mailerBuilder()
                .withMailSendRetryPolicy(MailSendRetryPolicy.builder().withRetryDelay(Duration.ofSeconds(30)).build()).buildMailer());
    }

    @Test
    void individualClearsDoNotSilentlyRestoreCapturedRequiredScheduleFields() {
        final SimpleJavaMail factory = factory(configuredProperties());
        assertThatIllegalArgumentException().isThrownBy(() -> factory.mailerBuilder()
                .withMailSendRetryPolicy(MailSendRetryPolicy.builder().clearMaximumAttempts().build()).buildMailer());
        assertThatIllegalArgumentException().isThrownBy(() -> factory.mailerBuilder()
                .withMailSendRetryPolicy(MailSendRetryPolicy.builder().clearRetryDelay().build()).buildMailer());
        assertThatIllegalArgumentException().isThrownBy(() -> factory.mailerBuilder()
                .withMailSendRetryPolicy(MailSendRetryPolicy.builder().clearMaximumAttempts().clearRetryDelay().build()).buildMailer());
    }

    @Test
    void inheritedMaximumDelayBoundsOtherwiseOverflowingJitter() throws Exception {
        final Duration delay = Duration.ofNanos(Long.MAX_VALUE - 1);
        final Properties properties = configuredProperties();
        properties.setProperty("simplejavamail.smtp.retry.maximumdelay", delay.toString());
        try (Mailer mailer = factory(properties).mailerBuilder().withMailSendRetryPolicy(
                MailSendRetryPolicy.builder().withRetryDelay(delay).withRetryJitter(0.5).build()).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy().getMaximumRetryDelay()).isEqualTo(delay);
        }
    }

    private static Properties configuredProperties() {
        final Properties properties = new Properties();
        properties.setProperty("simplejavamail.smtp.retry.attempts", "3");
        properties.setProperty("simplejavamail.smtp.retry.delay", "PT2S");
        properties.setProperty("simplejavamail.smtp.retry.backoffmultiplier", "2");
        properties.setProperty("simplejavamail.smtp.retry.maximumdelay", "PT20S");
        properties.setProperty("simplejavamail.smtp.retry.jitter", "0.2");
        properties.setProperty("simplejavamail.defaults.mailsend.retry.waitingcapacity", "7");
        properties.setProperty("simplejavamail.defaults.mailsend.retry.maximummessagesize", "1024");
        properties.setProperty("simplejavamail.defaults.mailsend.retry.memorylimit", "4096");
        return properties;
    }

    private static SimpleJavaMail factory(final Properties properties) {
        properties.setProperty("simplejavamail.smtp.host", "localhost");
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withProperties("retry inheritance", properties).load());
    }
}
