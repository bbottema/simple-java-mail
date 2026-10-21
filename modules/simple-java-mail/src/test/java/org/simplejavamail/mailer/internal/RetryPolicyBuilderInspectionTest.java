package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.config.ConfigLoader;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY;

class RetryPolicyBuilderInspectionTest {

    @Test
    void emptyChoicesRemainEmptyBeforeAndAfterOperationalDefaultsAreResolved() throws Exception {
        final MailerRegularBuilder<?> builder = builder(Map.of());
        final MailSendRetryPolicy empty = MailSendRetryPolicy.builder().build();
        assertThat(builder.getMailSendRetryPolicy()).isEqualTo(empty);

        try (Mailer mailer = builder.buildMailer()) {
            final MailSendRetryPolicy resolved = mailer.getOperationalConfig().getMailSendRetryPolicy();
            assertThat(resolved.isRetryEnabled()).isFalse();
            assertThat(resolved.getWaitingCapacity()).isEqualTo(DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY);
            assertThat(resolved.getMaximumMessageSize()).isEqualTo(DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE);
            assertThat(resolved.getMemoryLimit()).isEqualTo(DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT);
            assertThat(builder.getMailSendRetryPolicy()).isEqualTo(empty);
        }
    }

    @Test
    void partialScheduleCanBeInspectedAndCompletedWithoutChangingItsEarlierValue() throws Exception {
        final MailSendRetryPolicy partial = MailSendRetryPolicy.builder().withMaximumAttempts(3).build();
        final MailerRegularBuilder<?> builder = builder(Map.of()).withMailSendRetryPolicy(partial);
        final MailSendRetryPolicy inspected = builder.getMailSendRetryPolicy();
        assertThat(inspected).isEqualTo(partial);
        assertThat(inspected.getRetryDelay()).isNull();
        assertThat(inspected.isRetryDelayCleared()).isFalse();
        assertThatIllegalArgumentException().isThrownBy(builder::buildMailer);

        final MailSendRetryPolicy completed = inspected.toBuilder().withRetryDelay(Duration.ofSeconds(2)).build();
        builder.withMailSendRetryPolicy(completed);
        assertThat(builder.getMailSendRetryPolicy()).isEqualTo(completed);
        assertThat(builder.getMailSendRetryPolicy().getWaitingCapacity()).isNull();
        try (Mailer mailer = builder.buildMailer()) {
            final MailSendRetryPolicy resolved = mailer.getOperationalConfig().getMailSendRetryPolicy();
            assertThat(resolved.getMaximumAttempts()).isEqualTo(3);
            assertThat(resolved.getRetryDelay()).isEqualTo(Duration.ofSeconds(2));
            assertThat(resolved.getWaitingCapacity()).isEqualTo(DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY);
            builder.clearMailSendRetryPolicy();
            assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy()).isEqualTo(resolved);
        }
        assertThat(inspected.getRetryDelay()).isNull();
        assertThat(partial.getRetryDelay()).isNull();
    }

    @Test
    void capturedPropertiesAreResolvedOnlyOnOperationalConfiguration() throws Exception {
        final MailerRegularBuilder<?> builder = builder(configuredProperties());
        final MailSendRetryPolicy empty = MailSendRetryPolicy.builder().build();
        assertThat(builder.getMailSendRetryPolicy()).isEqualTo(empty);
        assertThat(builder.getMailSendRetryPolicy()).isEqualTo(empty);

        try (Mailer mailer = builder.buildMailer()) {
            final MailSendRetryPolicy resolved = mailer.getOperationalConfig().getMailSendRetryPolicy();
            assertThat(resolved.getMaximumAttempts()).isEqualTo(3);
            assertThat(resolved.getRetryDelay()).isEqualTo(Duration.ofSeconds(2));
            assertThat(resolved.getWaitingCapacity()).isEqualTo(7);
            assertThat(resolved.getMaximumMessageSize()).isEqualTo(1024);
            assertThat(resolved.getMemoryLimit()).isEqualTo(4096);
            assertThat(builder.getMailSendRetryPolicy()).isEqualTo(empty);
        }
    }

    @Test
    void individualClearRemainsInspectableWhileTheEffectiveScheduleIsIncomplete() throws Exception {
        final MailSendRetryPolicy partial = MailSendRetryPolicy.builder().clearMaximumAttempts().build();
        final MailerRegularBuilder<?> builder = builder(configuredProperties()).withMailSendRetryPolicy(partial);
        final MailSendRetryPolicy inspected = builder.getMailSendRetryPolicy();
        assertThat(inspected).isEqualTo(partial);
        assertThat(inspected.isMaximumAttemptsCleared()).isTrue();
        assertThat(inspected.isRetryDelayCleared()).isFalse();
        assertThat(inspected.getRetryDelay()).isNull();
        assertThatIllegalArgumentException().isThrownBy(builder::buildMailer);

        final MailSendRetryPolicy disabled = inspected.toBuilder().clearRetrySchedule().build();
        builder.withMailSendRetryPolicy(disabled);
        assertThat(builder.getMailSendRetryPolicy()).isEqualTo(disabled);
        try (Mailer mailer = builder.buildMailer()) {
            assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy().isRetryEnabled()).isFalse();
            assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy().getWaitingCapacity()).isEqualTo(7);
        }
    }

    @Test
    void explicitResetsAndWholePolicyClearRemainDistinctFromOmittedChoices() throws Exception {
        final MailSendRetryPolicy reset = MailSendRetryPolicy.builder().resetRetryBackoff().resetWaitingCapacity().build();
        final MailerRegularBuilder<?> builder = builder(configuredProperties()).withMailSendRetryPolicy(reset);
        assertThat(builder.getMailSendRetryPolicy()).isEqualTo(reset);
        assertThat(builder.getMailSendRetryPolicy().isMaximumRetryDelayCleared()).isTrue();
        assertThat(builder.getMailSendRetryPolicy().getMaximumAttempts()).isNull();
        assertThat(builder.getMailSendRetryPolicy().getMemoryLimit()).isNull();
        try (Mailer mailer = builder.buildMailer()) {
            final MailSendRetryPolicy resolved = mailer.getOperationalConfig().getMailSendRetryPolicy();
            assertThat(resolved.getMaximumAttempts()).isEqualTo(3);
            assertThat(resolved.getRetryBackoffMultiplier()).isOne();
            assertThat(resolved.getMaximumRetryDelay()).isNull();
            assertThat(resolved.getWaitingCapacity()).isEqualTo(DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY);
            assertThat(resolved.getMemoryLimit()).isEqualTo(4096);

            builder.clearMailSendRetryPolicy();
            final MailSendRetryPolicy cleared = MailSendRetryPolicy.builder().clearRetrySchedule()
                    .resetWaitingCapacity().resetMaximumMessageSize().resetMemoryLimit().build();
            assertThat(builder.getMailSendRetryPolicy()).isEqualTo(cleared);
            assertThat(mailer.getOperationalConfig().getMailSendRetryPolicy()).isEqualTo(resolved);
        }
    }

    @Test
    void inspectingResourceChoicesDoesNotValidateTheirEffectiveInheritedRelationship() {
        final MailSendRetryPolicy supplied = MailSendRetryPolicy.builder().withMemoryLimit(512).build();
        final MailerRegularBuilder<?> builder = builder(configuredProperties()).withMailSendRetryPolicy(supplied);
        assertThat(builder.getMailSendRetryPolicy()).isEqualTo(supplied);
        assertThatIllegalArgumentException().isThrownBy(builder::buildMailer).withMessageContaining("memory limit");
        assertThat(builder.getMailSendRetryPolicy()).isEqualTo(supplied);
    }

    @Test
    void inspectingChoicesDoesNotHideALockConflictOrMoveItsConstructionBoundary() {
        final MailSendRetryPolicy supplied = MailSendRetryPolicy.builder().withWaitingCapacity(9).build();
        final MailerRegularBuilder<?> builder = builder(Map.of(
                "simplejavamail.locked.defaults.mailsend.retry.waitingcapacity", 7)).withMailSendRetryPolicy(supplied);
        assertThat(builder.getMailSendRetryPolicy()).isEqualTo(supplied);
        assertThatIllegalArgumentException().isThrownBy(builder::buildMailer)
                .withMessageContaining("locked.defaults.mailsend.retry.waitingcapacity");
        assertThat(builder.getMailSendRetryPolicy()).isEqualTo(supplied);
    }

    private static Map<String, Object> configuredProperties() {
        return Map.of("simplejavamail.smtp.retry.attempts", 3,
                "simplejavamail.smtp.retry.delay", "PT2S",
                "simplejavamail.smtp.retry.backoffmultiplier", 2,
                "simplejavamail.smtp.retry.maximumdelay", "PT20S",
                "simplejavamail.defaults.mailsend.retry.waitingcapacity", 7,
                "simplejavamail.defaults.mailsend.retry.maximummessagesize", 1024L,
                "simplejavamail.defaults.mailsend.retry.memorylimit", 4096L);
    }

    private static MailerRegularBuilder<?> builder(final Map<String, Object> properties) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(properties).load()).mailerBuilder()
                .withTransportModeLoggingOnly(true);
    }
}
