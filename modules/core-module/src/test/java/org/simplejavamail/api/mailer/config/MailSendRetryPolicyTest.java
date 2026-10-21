package org.simplejavamail.api.mailer.config;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.MailerGenericBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT;

class MailSendRetryPolicyTest {
    @Test void omittedChoicesStayAbsentAndExplicitResetsUsePublishedMailerConstants() {
        final MailSendRetryPolicy.Builder builder = MailSendRetryPolicy.builder().withMaximumAttempts(3).withRetryDelay(Duration.ofSeconds(2));
        final MailSendRetryPolicy omitted = builder.build();
        assertThat(omitted.getRetryBackoffMultiplier()).isNull();
        assertThat(omitted.getRetryJitter()).isNull();
        assertThat(omitted.getWaitingCapacity()).isNull();
        assertThat(omitted.getMaximumMessageSize()).isNull();
        assertThat(omitted.getMemoryLimit()).isNull();
        final MailSendRetryPolicy defaults = builder.resetRetryBackoff().resetRetryJitter()
                .resetWaitingCapacity().resetMaximumMessageSize().resetMemoryLimit().build();
        assertThat(defaults.getRetryBackoffMultiplier()).isEqualTo(MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_BACKOFF_MULTIPLIER);
        assertThat(defaults.getRetryJitter()).isEqualTo(MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_JITTER);
        assertThat(defaults.getWaitingCapacity()).isEqualTo(DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY);
        assertThat(defaults.getMaximumMessageSize()).isEqualTo(DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE);
        assertThat(defaults.getMemoryLimit()).isEqualTo(DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT);
        assertThat(builder.withRetryBackoff(2, Duration.ofMinutes(1)).withRetryJitter(0.2).resetRetryBackoff().resetRetryJitter().build())
                .isEqualTo(defaults);
        assertThat(builder.withWaitingCapacity(1).withMaximumMessageSize(1024).withMemoryLimit(2048)
                .resetWaitingCapacity().resetMaximumMessageSize().resetMemoryLimit().build()).isEqualTo(defaults);
    }

    @Test void incompleteSuppliedChoicesAreRetainedForMailerResolution() {
        assertThat(MailSendRetryPolicy.builder().build().isRetryEnabled()).isFalse();
        assertThat(MailSendRetryPolicy.builder().withMaximumAttempts(3).build().getMaximumAttempts()).isEqualTo(3);
        assertThat(MailSendRetryPolicy.builder().withMaximumAttempts(3).build().isRetryEnabled()).isFalse();
        assertThat(MailSendRetryPolicy.builder().withRetryDelay(Duration.ofSeconds(1)).build().getRetryDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(MailSendRetryPolicy.builder().withRetryJitter(0.2).build().getRetryJitter()).isEqualTo(0.2);
        assertThat(MailSendRetryPolicy.builder().withRetryBackoff(2, Duration.ofSeconds(1)).build().getRetryBackoffMultiplier()).isEqualTo(2);
    }

    @Test void retainsOmittedChoicesAndSupportsReplacement() {
        final MailSendRetryPolicy policy = MailSendRetryPolicy.builder().withMaximumAttempts(2).withMaximumAttempts(4)
                .withRetryDelay(Duration.ofSeconds(1)).withRetryDelay(Duration.ofSeconds(2)).build();
        assertThat(policy.getMaximumAttempts()).isEqualTo(4);
        assertThat(policy.getRetryDelay()).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.getRetryBackoffMultiplier()).isNull();
        assertThat(policy.getMaximumRetryDelay()).isNull();
        assertThat(policy.getRetryJitter()).isNull();
    }

    @Test void resetsOptionalSettings() {
        final MailSendRetryPolicy policy = MailSendRetryPolicy.builder().withMaximumAttempts(3).withRetryDelay(Duration.ofSeconds(1))
                .withRetryBackoff(2, Duration.ofMinutes(1)).withRetryJitter(0.2).resetRetryBackoff().resetRetryJitter().build();
        assertThat(policy.getRetryBackoffMultiplier()).isEqualTo(1);
        assertThat(policy.getMaximumRetryDelay()).isNull();
        assertThat(policy.getRetryJitter()).isZero();
    }

    @Test void validatesSuppliedAndSerializedInvariants() throws Exception {
        assertThatIllegalArgumentException().isThrownBy(() -> suppliedPolicy(1, Duration.ofSeconds(1), 1, null, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> suppliedPolicy(2, Duration.ZERO, 1, null, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> suppliedPolicy(2, Duration.ofSeconds(Long.MAX_VALUE), 1, null, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> suppliedPolicy(2, Duration.ofSeconds(2), 2, Duration.ofSeconds(1), 0));
        assertThatIllegalArgumentException().isThrownBy(() -> suppliedPolicy(2, Duration.ofSeconds(1), 2, null, 0));
        for (double invalid : new double[]{-1, 1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatIllegalArgumentException().isThrownBy(() -> suppliedPolicy(2, Duration.ofSeconds(1), 1, null, invalid));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> suppliedPolicy(2, null, 1, null, 0));
        final MailSendRetryPolicy original = suppliedPolicy(3, Duration.ofSeconds(1), 2, Duration.ofMinutes(1), 0.2);
        assertThat(roundTrip(original)).isEqualTo(original);
    }

    @Test void resourceOnlyPoliciesAreImmutableAndSerializableWithoutAnImplicitSchedule() throws Exception {
        final MailSendRetryPolicy.Builder builder = MailSendRetryPolicy.builder().withWaitingCapacity(2)
                .withMaximumMessageSize(1024).withMemoryLimit(4096);
        final MailSendRetryPolicy policy = builder.build();
        builder.withWaitingCapacity(3);
        assertThat(policy.isRetryEnabled()).isFalse();
        assertThat(policy.getMaximumAttempts()).isNull();
        assertThat(policy.getRetryDelay()).isNull();
        assertThat(policy.getWaitingCapacity()).isEqualTo(2);
        assertThat(policy.getMaximumMessageSize()).isEqualTo(1024);
        assertThat(policy.getMemoryLimit()).isEqualTo(4096);
        assertThat(roundTrip(policy)).isEqualTo(policy);
    }

    @Test void copyingAndSerializationPreserveOmissionVersusExplicitClearing() throws Exception {
        final MailSendRetryPolicy omitted = MailSendRetryPolicy.builder().withMemoryLimit(2048).build();
        final MailSendRetryPolicy cleared = omitted.toBuilder().clearRetrySchedule().build();
        assertThat(omitted.isMaximumAttemptsCleared()).isFalse();
        assertThat(omitted.isRetryDelayCleared()).isFalse();
        assertThat(omitted.isMaximumRetryDelayCleared()).isFalse();
        assertThat(cleared.isMaximumAttemptsCleared()).isTrue();
        assertThat(cleared.isRetryDelayCleared()).isTrue();
        assertThat(cleared.isMaximumRetryDelayCleared()).isTrue();
        assertThat(cleared.toBuilder().build()).isEqualTo(cleared);
        assertThat(roundTrip(omitted)).isEqualTo(omitted);
        assertThat(roundTrip(cleared)).isEqualTo(cleared);
    }

    @Test void validatesResourceLimitsWithoutResolvingDefaultsInTheConstructor() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MailSendRetryPolicy(null, null, 1, null, 0, 0, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new MailSendRetryPolicy(null, null, 1, null, 0, 1, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new MailSendRetryPolicy(null, null, 1, null, 0, 1, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThatIllegalArgumentException().isThrownBy(() -> new MailSendRetryPolicy(null, null, 1, null, 0, 1, 2, 1));
        assertThat(new MailSendRetryPolicy(null, null, 1, null, 0, 1, 2, 3).getMemoryLimit()).isEqualTo(3);
    }

    private static MailSendRetryPolicy suppliedPolicy(final Integer attempts, final Duration delay, final int multiplier,
            final Duration maximum, final double jitter) {
        return new MailSendRetryPolicy(attempts, delay, multiplier, maximum, jitter,
                DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY, DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE, DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT);
    }

    private static Object roundTrip(final MailSendRetryPolicy original) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(original); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return input.readObject();
        }
    }
}
