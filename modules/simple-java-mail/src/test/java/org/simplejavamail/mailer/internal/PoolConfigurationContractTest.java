package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.config.ConfigLoader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PoolConfigurationContractTest {
    @ParameterizedTest
    @CsvSource({"coreSize,-1", "maxSize,0", "claimTimeoutMillis,-1", "expireAfterMillis,-1", "expireAfterCreationMillis,0"})
    void rejectsInvalidSuppliedClusterValues(final String field, final int value) {
        final ConnectionPoolClusterConfig.ConnectionPoolClusterConfigBuilder builder = ConnectionPoolClusterConfig.builder();
        switch (field) {
            case "coreSize": builder.coreSize(value); break;
            case "maxSize": builder.maxSize(value); break;
            case "claimTimeoutMillis": builder.claimTimeoutMillis(value); break;
            case "expireAfterMillis": builder.expireAfterMillis(value); break;
            default: builder.expireAfterCreationMillis(value); break;
        }
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
    }

    @ParameterizedTest
    @CsvSource({"coresize,-1,coreSize", "maxsize,0,maxSize", "claimtimeout.millis,-1,claimTimeoutMillis", "expireafter.millis,-1,expireAfterMillis"})
    void rejectsInvalidCapturedGlobalValues(final String tail, final int value, final String field) {
        assertThatThrownBy(() -> buildAndClose(factory(Map.of("defaults.connectionpool." + tail, Integer.toString(value))).mailerBuilder()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
    }

    @Test
    void rejectsInvalidJavaChoicesBeforePoolCreation() {
        assertThatThrownBy(() -> buildAndClose(factory(Map.of()).mailerBuilder().withConnectionPoolCoreSize(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("coreSize");
        assertThatThrownBy(() -> buildAndClose(factory(Map.of()).mailerBuilder().withConnectionPoolClaimTimeoutMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("claimTimeoutMillis");
        assertThatThrownBy(() -> buildAndClose(factory(Map.of()).mailerBuilder().withConnectionPoolMaxSize(1).withConnectionPoolCoreSize(2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("coreSize").hasMessageContaining("maxSize");
    }

    @Test
    void finalClusterOverridesDetermineTheCoreMaximumRelationship() throws Exception {
        final UUID cluster = UUID.randomUUID();
        final SimpleJavaMail mail = factory(Map.of("defaults.connectionpool.clusters.orders.clusterkey.uuid", cluster.toString(),
                "defaults.connectionpool.clusters.orders.maxsize", "4"));
        try (Mailer ignored = mail.mailerBuilder().withClusterKey(cluster).withConnectionPoolCoreSize(0)
                .withConnectionPoolMaxSize(1).buildMailer()) {
            assertThat(ignored.getOperationalConfig().getConnectionPoolClusterConfigs().get(cluster).getMaxSize()).isEqualTo(4);
        }
        final SimpleJavaMail invalid = factory(Map.of("defaults.connectionpool.clusters.orders.clusterkey.uuid", cluster.toString(),
                "defaults.connectionpool.clusters.orders.maxsize", "1"));
        assertThatThrownBy(() -> buildAndClose(invalid.mailerBuilder().withClusterKey(cluster)
                .withConnectionPoolMaxSize(3).withConnectionPoolCoreSize(2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("coreSize").hasMessageContaining("maxSize");
    }

    @Test
    void zeroValuesAndSetterOrderingRemainSupported() throws Exception {
        final MailerRegularBuilder<?> builder = factory(Map.of()).mailerBuilder().withConnectionPoolCoreSize(4)
                .withConnectionPoolMaxSize(4).withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1)
                .withConnectionPoolClaimTimeoutMillis(0).withConnectionPoolExpireAfterMillis(0);
        try (Mailer mailer = builder.buildMailer()) {
            assertThat(mailer.getOperationalConfig().getConnectionPoolClaimTimeoutMillis()).isZero();
            assertThat(mailer.getOperationalConfig().getConnectionPoolExpireAfterMillis()).isZero();
        }
    }

    @Test
    void aClusterOverrideCanCompleteOtherwiseConflictingGlobalSizes() throws Exception {
        final UUID cluster = UUID.randomUUID();
        final SimpleJavaMail mail = factory(Map.of("defaults.connectionpool.clusters.orders.clusterkey.uuid", cluster.toString(),
                "defaults.connectionpool.clusters.orders.coresize", "0"));
        try (Mailer ignored = mail.mailerBuilder().withClusterKey(cluster).withConnectionPoolCoreSize(2).withConnectionPoolMaxSize(1).buildMailer()) {
            assertThat(ignored.getOperationalConfig().getConnectionPoolClusterConfigs().get(cluster).getCoreSize()).isZero();
        }
    }

    private static void buildAndClose(final MailerRegularBuilder<?> builder) throws Exception {
        try (Mailer ignored = builder.buildMailer()) {
            // Even an unexpectedly accepted configuration must release its pool/executor resources.
        }
    }

    private static SimpleJavaMail factory(final Map<String, String> overrides) {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("simplejavamail.smtp.host", "localhost");
        overrides.forEach((tail, value) -> values.put("simplejavamail." + tail, value));
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(values).load());
    }
}
