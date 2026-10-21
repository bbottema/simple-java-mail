package org.simplejavamail.api.mailer.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyCopyTest {

    @Test
    void retryCopyPreservesResourceBudgetsWhileEnablingOrChangingTheSchedule() {
        final MailSendRetryPolicy original = MailSendRetryPolicy.builder()
                .withWaitingCapacity(7).withMaximumMessageSize(1024).withMemoryLimit(4096).build();
        assertThat(original.toBuilder().build()).isEqualTo(original);
        final MailSendRetryPolicy changed = original.toBuilder().withMaximumAttempts(3)
                .withRetryDelay(Duration.ofSeconds(2)).withRetryBackoff(2, Duration.ofSeconds(20)).withRetryJitter(0.2).build();
        assertThat(changed.getWaitingCapacity()).isEqualTo(7);
        assertThat(changed.getMaximumMessageSize()).isEqualTo(1024);
        assertThat(changed.getMemoryLimit()).isEqualTo(4096);
        assertThat(changed.toBuilder().build()).isEqualTo(changed);
        assertThat(changed.toBuilder().clearRetrySchedule().build()).isEqualTo(original.toBuilder().clearRetrySchedule().build());
        assertThat(original.isRetryEnabled()).isFalse();
    }

    @Test
    void failoverCopyPreservesEverySettingAndResetsRemainExplicit() {
        final FailoverPolicy original = FailoverPolicy.builder().withPrimaryConnectionAttempts(3)
                .withPrimaryConnectionRetryDelay(Duration.ofMillis(50)).withPrimaryRecheckInterval(Duration.ofSeconds(2))
                .withPrimaryRecheckBackoff(3, Duration.ofMinutes(1))
                .withFailoverOnAuthenticationFailure(true).withFailoverOnCertificateVerificationFailure(true).build();
        assertThat(original.toBuilder().build()).isEqualTo(original);
        final FailoverPolicy changed = original.toBuilder().withPrimaryConnectionAttempts(5).build();
        assertThat(changed).usingRecursiveComparison().ignoringFields("primaryConnectionAttempts").isEqualTo(original);
        assertThat(original.getPrimaryConnectionAttempts()).isEqualTo(3);
        assertThat(original.toBuilder().resetPrimaryRecheckBackoff().build().getPrimaryMaximumRecheckInterval())
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(FailoverPolicy.builder().build().toBuilder().build()).isEqualTo(FailoverPolicy.builder().build());
    }
}
