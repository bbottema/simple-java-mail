package org.simplejavamail.api.mailer.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MailSendRetryPolicyCompanionTest {

    @Test
    void clearingTheWholeSchedulePreservesResourceLimitsAndExistingPolicies() {
        final MailSendRetryPolicy.Builder builder = configuredSchedule()
                .withWaitingCapacity(7)
                .withMaximumMessageSize(1024)
                .withMemoryLimit(4096);
        final MailSendRetryPolicy original = builder.build();

        assertThat(builder.clearRetrySchedule()).isSameAs(builder);
        final MailSendRetryPolicy cleared = builder.clearRetrySchedule().build();

        assertThat(cleared.isRetryEnabled()).isFalse();
        assertThat(cleared.getMaximumAttempts()).isNull();
        assertThat(cleared.getRetryDelay()).isNull();
        assertThat(cleared.getRetryBackoffMultiplier()).isOne();
        assertThat(cleared.getMaximumRetryDelay()).isNull();
        assertThat(cleared.getRetryJitter()).isZero();
        assertThat(cleared.getWaitingCapacity()).isEqualTo(7);
        assertThat(cleared.getMaximumMessageSize()).isEqualTo(1024);
        assertThat(cleared.getMemoryLimit()).isEqualTo(4096);
        assertThat(original.isRetryEnabled()).isTrue();
        assertThat(original.getMaximumAttempts()).isEqualTo(3);
        assertThat(original.getRetryBackoffMultiplier()).isEqualTo(2);
        assertThat(original.getRetryJitter()).isEqualTo(0.2);
        assertThat(builder.withMaximumAttempts(2).withRetryDelay(Duration.ofSeconds(5)).build().getMemoryLimit()).isEqualTo(4096);
    }

    @Test
    void clearingAnIndividualFieldDoesNotSilentlyRemoveTheRestOfTheSchedule() {
        final MailSendRetryPolicy.Builder attempts = configuredSchedule();
        assertThat(attempts.clearMaximumAttempts()).isSameAs(attempts);
        assertThatIllegalArgumentException().isThrownBy(attempts::build).withMessageContaining("clearRetrySchedule()");
        assertThat(attempts.withMaximumAttempts(4).build().getRetryJitter()).isEqualTo(0.2);

        final MailSendRetryPolicy.Builder delay = configuredSchedule();
        assertThat(delay.clearRetryDelay()).isSameAs(delay);
        assertThatIllegalArgumentException().isThrownBy(delay::build).withMessageContaining("clearRetrySchedule()");
        assertThat(delay.withRetryDelay(Duration.ofSeconds(3)).build().getMaximumAttempts()).isEqualTo(3);
    }

    @Test
    void clearingBothFieldsStillRequiresRemovingDependentModifiers() {
        final MailSendRetryPolicy.Builder builder = configuredSchedule().clearMaximumAttempts().clearRetryDelay();

        assertThatIllegalArgumentException().isThrownBy(builder::build).withMessageContaining("clearRetrySchedule()");
        assertThat(builder.resetRetryBackoff().resetRetryJitter().build().isRetryEnabled()).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> MailSendRetryPolicy.builder()
                .withMaximumAttempts(1).withRetryDelay(Duration.ofSeconds(1)).build()).withMessageContaining("clearRetrySchedule()");
    }

    private static MailSendRetryPolicy.Builder configuredSchedule() {
        return MailSendRetryPolicy.builder()
                .withMaximumAttempts(3)
                .withRetryDelay(Duration.ofSeconds(2))
                .withRetryBackoff(2, Duration.ofMinutes(1))
                .withRetryJitter(0.2);
    }
}
