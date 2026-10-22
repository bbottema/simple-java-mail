package org.simplejavamail.api.mailer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.MailerGenericBuilder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionConfigTimeoutTest {
    @Test
    void convenienceSetterAndFineTuningUseTheLastChoiceForEachOperation() {
        final ConnectionConfig.Builder builder = ConnectionConfig.builder().withSessionTimeout(Duration.ofSeconds(30))
                .withConnectTimeout(Duration.ofSeconds(10)).withReadTimeout(Duration.ofSeconds(45));
        assertThat(builder.getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(builder.getReadTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(builder.getWriteTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(builder.getSessionTimeout()).isNull();
        final ConnectionConfig original = builder.build();
        assertThat(original.getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(original.getReadTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(original.getWriteTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(original.getSessionTimeout()).isNull();

        builder.withSessionTimeout(Duration.ofSeconds(20));
        assertAllTimeouts(builder.build(), Duration.ofSeconds(20));
        assertAllTimeouts(original.toBuilder().withSessionTimeout(Duration.ofSeconds(15)).build(), Duration.ofSeconds(15));
        assertThat(original.getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(original.getReadTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(original.getWriteTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void inspectionDoesNotInventDefaultsAndTheCommonGetterRequiresThreeEqualChoices() {
        final ConnectionConfig.Builder builder = ConnectionConfig.builder();
        assertThat(builder.getConnectTimeout()).isNull();
        assertThat(builder.getReadTimeout()).isNull();
        assertThat(builder.getWriteTimeout()).isNull();
        assertThat(builder.getSessionTimeout()).isNull();
        assertAllTimeouts(builder.build(), null);

        builder.withConnectTimeout(Duration.ofSeconds(10));
        assertThat(builder.getReadTimeout()).isNull();
        assertThat(builder.getWriteTimeout()).isNull();
        assertThat(builder.getSessionTimeout()).isNull();
        assertThat(builder.build().getSessionTimeout()).isNull();
        builder.withReadTimeout(Duration.ofSeconds(10)).withWriteTimeout(Duration.ofSeconds(10));
        assertThat(builder.getSessionTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertAllTimeouts(builder.build(), Duration.ofSeconds(10));
    }

    @Test
    void individualResetAndInheritanceCompanionsPreserveOtherChoicesAndSnapshots() {
        final ConnectionConfig original = ConnectionConfig.builder().withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofSeconds(20)).withWriteTimeout(Duration.ofSeconds(30)).build();
        final ConnectionConfig.Builder changed = original.toBuilder().resetConnectTimeout();
        assertThat(changed.getConnectTimeout()).isEqualTo(MailerGenericBuilder.DEFAULT_SESSION_TIMEOUT);
        assertThat(changed.getReadTimeout()).isEqualTo(original.getReadTimeout());
        assertThat(changed.getWriteTimeout()).isEqualTo(original.getWriteTimeout());
        changed.useConfiguredConnectTimeout().resetReadTimeout();
        assertThat(changed.getConnectTimeout()).isNull();
        assertThat(changed.getReadTimeout()).isEqualTo(MailerGenericBuilder.DEFAULT_SESSION_TIMEOUT);
        assertThat(changed.getWriteTimeout()).isEqualTo(original.getWriteTimeout());
        changed.useConfiguredReadTimeout().resetWriteTimeout();
        assertThat(changed.getConnectTimeout()).isNull();
        assertThat(changed.getReadTimeout()).isNull();
        assertThat(changed.getWriteTimeout()).isEqualTo(MailerGenericBuilder.DEFAULT_SESSION_TIMEOUT);
        changed.useConfiguredWriteTimeout();
        assertAllTimeouts(changed.build(), null);
        assertThat(original.getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(original.getReadTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(original.getWriteTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void combinedResetAndInheritanceTouchAllThreeChoicesAndPreserveOtherSettings() {
        final ConnectionConfig.Builder builder = ConnectionConfig.builder().withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofSeconds(20)).withWriteTimeout(Duration.ofSeconds(30))
                .withLocalBindAddress("192.0.2.10").withLegacySmtpContentSupport(true);
        assertAllTimeouts(builder.resetSessionTimeout().build(), MailerGenericBuilder.DEFAULT_SESSION_TIMEOUT);
        assertAllTimeouts(builder.useConfiguredSessionTimeout().build(), null);
        assertThat(builder.getLocalBindAddress()).isEqualTo("192.0.2.10");
        assertThat(builder.getLegacySmtpContentSupport()).isTrue();
    }

    @Test
    void rejectedTimeoutInputsDoNotChangeAnyStoredChoice() {
        final ConnectionConfig.Builder builder = ConnectionConfig.builder().withSessionTimeout(Duration.ofSeconds(30));
        for (final Duration invalid : new Duration[] {null, Duration.ofNanos(1), Duration.ofNanos(-1),
                Duration.ofMillis(Integer.MAX_VALUE + 1L), Duration.ofMillis(Integer.MIN_VALUE - 1L)}) {
            final Class<? extends Throwable> expected = invalid == null ? NullPointerException.class : IllegalArgumentException.class;
            assertThatThrownBy(() -> builder.withSessionTimeout(invalid)).isInstanceOf(expected);
            assertThatThrownBy(() -> builder.withConnectTimeout(invalid)).isInstanceOf(expected);
            assertThatThrownBy(() -> builder.withReadTimeout(invalid)).isInstanceOf(expected);
            assertThatThrownBy(() -> builder.withWriteTimeout(invalid)).isInstanceOf(expected);
            assertAllTimeouts(builder.build(), Duration.ofSeconds(30));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, 0, Integer.MAX_VALUE})
    void exactSignedIntegerMillisecondsRemainRepresentable(final int millis) {
        final Duration timeout = Duration.ofMillis(millis);
        assertAllTimeouts(ConnectionConfig.builder().withConnectTimeout(timeout).withReadTimeout(timeout).withWriteTimeout(timeout).build(), timeout);
        assertAllTimeouts(ConnectionConfig.builder().withSessionTimeout(timeout).build(), timeout);
    }

    private static void assertAllTimeouts(final ConnectionConfig config, final Duration expected) {
        assertThat(config.getConnectTimeout()).isEqualTo(expected);
        assertThat(config.getReadTimeout()).isEqualTo(expected);
        assertThat(config.getWriteTimeout()).isEqualTo(expected);
        assertThat(config.getSessionTimeout()).isEqualTo(expected);
    }
}
