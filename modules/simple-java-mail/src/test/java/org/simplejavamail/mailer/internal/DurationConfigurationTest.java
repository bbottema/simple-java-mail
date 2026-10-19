package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.config.ConfigLoader;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurationConfigurationTest {
    @Test
    void everyMigratedSettingRejectsFractionalMillisecondsAndOverflow() {
        final List<BiConsumer<MailerRegularBuilder<?>, Duration>> setters = List.of(
                (builder, value) -> builder.withSessionTimeout(value),
                (builder, value) -> builder.withThreadPoolKeepAliveTime(value),
                (builder, value) -> builder.withAsyncQueueWaitTimeout(value),
                (builder, value) -> builder.withConnectionPoolClaimTimeout(value),
                (builder, value) -> builder.withConnectionPoolExpireAfter(value),
                (builder, value) -> builder.withConnectionPoolExpireAfterCreation(value));
        for (BiConsumer<MailerRegularBuilder<?>, Duration> setter : setters) {
            assertThatThrownBy(() -> setter.accept(factory(Map.of()).mailerBuilder(), Duration.ofNanos(1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whole number of milliseconds");
            assertThatThrownBy(() -> setter.accept(factory(Map.of()).mailerBuilder(), Duration.ofMillis((long) Integer.MAX_VALUE + 1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("integer-millisecond range");
        }
    }

    @Test
    void numericPropertiesKeepTheirMillisecondUnits() {
        final MailerRegularBuilder<?> builder = factory(Map.of(
                "simplejavamail.defaults.sessiontimeoutmillis", 1250,
                "simplejavamail.defaults.poolsize.keepalivetime", 2,
                "simplejavamail.defaults.async.queue.waittimeoutmillis", 3,
                "simplejavamail.defaults.connectionpool.claimtimeout.millis", 4,
                "simplejavamail.defaults.connectionpool.expireafter.millis", 5,
                "simplejavamail.defaults.connectionpool.expireaftercreation.millis", 6)).mailerBuilder();
        assertThat(builder.getSessionTimeout()).isEqualTo(Duration.ofMillis(1250));
        assertThat(builder.getThreadPoolKeepAliveTime()).isEqualTo(Duration.ofMillis(2));
        assertThat(builder.getAsyncQueueConfig().getWaitTimeout()).isEqualTo(Duration.ofMillis(3));
        assertThat(builder.getConnectionPoolClaimTimeout()).isEqualTo(Duration.ofMillis(4));
        assertThat(builder.getConnectionPoolExpireAfter()).isEqualTo(Duration.ofMillis(5));
        assertThat(builder.getConnectionPoolExpireAfterCreation()).isEqualTo(Duration.ofMillis(6));
    }

    @Test
    void durationsCompareToNumericLocksAndReachProviderPropertiesExactly() throws Exception {
        final SimpleJavaMail mail = factory(Map.of("simplejavamail.locked.defaults.sessiontimeoutmillis", 1250));
        try (Mailer mailer = mail.mailerBuilder().withSMTPServerHost("localhost").withSessionTimeout(Duration.ofMillis(1250)).buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.connectiontimeout")).isEqualTo("1250");
            assertThat(mailer.getSession().getProperty("mail.smtp.timeout")).isEqualTo("1250");
            assertThat(mailer.getOperationalConfig().getSessionTimeout()).isEqualTo(Duration.ofMillis(1250));
        }
        assertThatThrownBy(() -> mail.mailerBuilder().withSMTPServerHost("localhost").withSessionTimeout(Duration.ofSeconds(2)).buildMailer())
                .hasMessageContaining("locked.defaults.sessiontimeoutmillis");
    }

    @Test
    void clusterValuesCheckRepresentationWithoutResolvingFeatureDefaults() {
        assertThat(ConnectionPoolClusterConfig.builder().claimTimeout(Duration.ZERO).build().getClaimTimeout()).isEqualTo(Duration.ZERO);
        assertThat(ConnectionPoolClusterConfig.builder().build().getExpireAfterCreation()).isNull();
        assertThatThrownBy(() -> ConnectionPoolClusterConfig.builder().expireAfter(Duration.ofNanos(1)).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whole number of milliseconds");
    }

    private static SimpleJavaMail factory(final Map<String, ?> properties) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(properties).load());
    }
}
