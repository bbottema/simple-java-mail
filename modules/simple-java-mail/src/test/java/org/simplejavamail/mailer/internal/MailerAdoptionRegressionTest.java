package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.configresolution.ConfigurationChoice;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MailerAdoptionRegressionTest {
    @Test
    void legacyEmptyFallbackAndLiteralEmptyConnectionChoicesRemainDifferentAdapters() {
        final var config = ConfigLoader.builder().withMap(Map.of("simplejavamail.smtp.clienthostname", "captured.example.org")).load();
        assertThat(MailerConfigurationResolver.resolve(config, ConfigKeys.SMTP_CLIENT_HOSTNAME, "", "fallback.example.org"))
                .isEqualTo("captured.example.org");
        assertThat(MailerConfigurationResolver.resolve(config, ConfigKeys.SMTP_CLIENT_HOSTNAME, ConfigurationChoice.set(""),
                "fallback.example.org", "withSmtpClientHostname")).isEmpty();
        final var builder = new MailerRegularBuilderImpl(config)
                .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("").build());
        assertThat(builder.getConnectionConfig().getSmtpClientHostname()).isEmpty();
        assertThat(builder.getSmtpClientHostname()).isEmpty();
    }

    @Test
    void groupReplacementRestoresOneStableGeneratedClusterIdentity() {
        final var builder = new MailerRegularBuilderImpl(ConfigLoader.builder().load());
        final UUID original = builder.getClusterKey();
        final UUID supplied = UUID.fromString("890b414e-56c5-4647-8b0d-6231ca27c830");
        builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().withClusterKey(supplied).build());
        assertThat(builder.getClusterKey()).isSameAs(supplied);
        builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).build());
        assertThat(builder.getClusterKey()).isSameAs(original);
        builder.useConfiguredConnectionPoolConfig();
        assertThat(builder.getClusterKey()).isSameAs(original);
    }

    @Test
    void aCreationAgeClearBlocksTheConfiguredValueUntilRestoration() {
        final var config = ConfigLoader.builder().withMap(Map.of("simplejavamail.defaults.connectionpool.expireaftercreation.millis", 1234)).load();
        final var builder = new MailerRegularBuilderImpl(config);
        assertThat(builder.getConnectionPoolExpireAfterCreation()).isEqualTo(Duration.ofMillis(1234));
        builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().clearExpireAfterCreation().build());
        assertThat(builder.getConnectionPoolExpireAfterCreation()).isNull();
        assertThat(builder.getConnectionPoolConfig().isExpireAfterCreationCleared()).isTrue();
        builder.withConnectionPoolConfig(builder.getConnectionPoolConfig().toBuilder().useConfiguredExpireAfterCreation().build());
        assertThat(builder.getConnectionPoolExpireAfterCreation()).isEqualTo(Duration.ofMillis(1234));
    }
}
