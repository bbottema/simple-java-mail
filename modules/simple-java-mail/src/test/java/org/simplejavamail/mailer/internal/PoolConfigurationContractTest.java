package org.simplejavamail.mailer.internal;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PoolConfigurationContractTest {
    @ParameterizedTest
    @CsvSource({"coreSize,coresize,-1", "maxSize,maxsize,0", "claimTimeoutMillis,claimtimeout.millis,-1",
            "expireAfterMillis,expireafter.millis,-1", "expireAfterCreationMillis,expireaftercreation.millis,0"})
    void validatesClusterValuesOnlyWhenTheBatchModuleUsesThem(final String field, final String tail, final int value) throws Exception {
        final ConnectionPoolClusterConfig.ConnectionPoolClusterConfigBuilder builder = ConnectionPoolClusterConfig.builder();
        switch (field) {
            case "coreSize": builder.coreSize(value); break;
            case "maxSize": builder.maxSize(value); break;
            case "claimTimeoutMillis": builder.claimTimeout(Duration.ofMillis(value)); break;
            case "expireAfterMillis": builder.expireAfter(Duration.ofMillis(value)); break;
            default: builder.expireAfterCreation(Duration.ofMillis(value)); break;
        }
        assertThat(builder.build()).isNotNull();
        final UUID cluster = UUID.randomUUID();
        final SimpleJavaMail mail = factory(Map.of("defaults.connectionpool.clusters." + cluster + "." + tail, Integer.toString(value)));
        try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            buildAndClose(mail.mailerBuilder().withClusterKey(cluster));
            modules.verify(ModuleLoader::loadBatchModule, Mockito.never());
        }
        assertThatThrownBy(() -> buildAndClose(mail.mailerBuilder().withClusterKey(cluster)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
    }

    @Test
    void unusedPoolChoicesDoNotPreventDirectMailerConstruction() throws Exception {
        try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            final SimpleJavaMail mail = factory(Map.of("defaults.connectionpool.coresize", "-1",
                    "defaults.connectionpool.maxsize", "0", "defaults.connectionpool.expireaftercreation.millis", "0"));
            buildAndClose(mail.mailerBuilder().withConnectionPoolClaimTimeout(Duration.ofMillis(-1)).withConnectionPoolExpireAfterCreation(Duration.ofMillis(-1)));
            modules.verify(ModuleLoader::loadBatchModule, Mockito.never());
        }
    }

    @Test
    void onlyEffectiveSettingsOfTheSelectedClusterAreValidated() throws Exception {
        final UUID selected = UUID.randomUUID();
        final UUID unused = UUID.randomUUID();
        final String prefix = "defaults.connectionpool.clusters." + selected + ".";
        final SimpleJavaMail mail = factory(Map.of(prefix + "coresize", "0", prefix + "maxsize", "1",
                prefix + "claimtimeout.millis", "0", prefix + "expireafter.millis", "0", prefix + "expireaftercreation.millis", "1",
                "defaults.connectionpool.clusters." + unused + ".coresize", "3", "defaults.connectionpool.clusters." + unused + ".maxsize", "1"));
        buildAndClose(mail.mailerBuilder().withClusterKey(selected)
                .withConnectionPoolCoreSize(-1).withConnectionPoolMaxSize(0).withConnectionPoolClaimTimeout(Duration.ofMillis(-1))
                .withConnectionPoolExpireAfter(Duration.ofMillis(-1)).withConnectionPoolExpireAfterCreation(Duration.ofMillis(0)));
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
        assertThatThrownBy(() -> buildAndClose(factory(Map.of()).mailerBuilder().withConnectionPoolClaimTimeout(Duration.ofMillis(-1))))
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
                .withConnectionPoolClaimTimeout(Duration.ofMillis(0)).withConnectionPoolExpireAfter(Duration.ofMillis(0));
        try (Mailer mailer = builder.buildMailer()) {
            assertThat(mailer.getOperationalConfig().getConnectionPoolClaimTimeout()).isEqualTo(Duration.ZERO);
            assertThat(mailer.getOperationalConfig().getConnectionPoolExpireAfter()).isEqualTo(Duration.ZERO);
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
