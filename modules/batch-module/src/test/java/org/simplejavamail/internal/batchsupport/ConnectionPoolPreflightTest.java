package org.simplejavamail.internal.batchsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.internal.modules.BatchModule;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

class ConnectionPoolPreflightTest {
    @Test
    void validAndRejectedPreflightLeaveTheTransportEngineUninitialized() {
        final BatchModule module = new BatchSupport();
        final ConnectionPoolConfig defaults = defaults().build();

        module.validateConnectionPoolConfiguration(defaults, null);
        module.validateConnectionPoolConfiguration(defaults, ConnectionPoolClusterConfig.builder().coreSize(0).build());
        assertThatThrownBy(() -> module.validateConnectionPoolConfiguration(defaults,
                ConnectionPoolClusterConfig.builder().coreSize(4).build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("coreSize").hasMessageContaining("maxSize");

        assertThat(module).extracting("batchTransportEngine", "smtpConnectionPool").containsOnlyNulls();
        assertThat(module).extracting("sendingAllowances").isEqualTo(java.util.Collections.emptyMap());
        record("rule", "batch.selected-pool-preflight");
        record("case", "batch.selected-preflight");
    }

    @Test
    void selectedOverridesCanRepairInvalidUnusedDefaultMembers() {
        final BatchSupport module = new BatchSupport();
        final ConnectionPoolConfig defaults = defaults().withCoreSize(8).withClaimTimeout(Duration.ofMillis(-1))
                .withExpireAfterCreation(Duration.ZERO).build();
        final ConnectionPoolClusterConfig selected = ConnectionPoolClusterConfig.builder()
                .coreSize(0).claimTimeout(Duration.ZERO).expireAfterCreation(Duration.ofMillis(1)).build();

        module.validateConnectionPoolConfiguration(defaults, selected);
        assertThatThrownBy(() -> module.validateConnectionPoolConfiguration(defaults, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(module).extracting("batchTransportEngine", "smtpConnectionPool").containsOnlyNulls();
    }

    @Test
    void selectedSizePairIsCheckedAfterInheritanceAndNullableAgeStaysAbsent() {
        final BatchSupport module = new BatchSupport();
        final ConnectionPoolConfig defaults = defaults().withCoreSize(2).clearExpireAfterCreation().build();

        module.validateConnectionPoolConfiguration(defaults, ConnectionPoolClusterConfig.builder().maxSize(4).build());
        assertThatThrownBy(() -> module.validateConnectionPoolConfiguration(defaults,
                ConnectionPoolClusterConfig.builder().maxSize(1).build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("coreSize").hasMessageContaining("maxSize");
        assertThat(defaults.getExpireAfterCreation()).isNull();
    }

    @Test
    void registrationAdapterInterpretsSelectedDurationsAfterOverrideResolution() {
        final UUID cluster = UUID.randomUUID();
        final OperationalConfig operational = mock(OperationalConfig.class);
        when(operational.getConnectionPoolCoreSize()).thenReturn(0);
        when(operational.getConnectionPoolMaxSize()).thenReturn(1);
        when(operational.getConnectionPoolClaimTimeout()).thenReturn(Duration.ofNanos(1));
        when(operational.getConnectionPoolExpireAfter()).thenReturn(Duration.ZERO);
        when(operational.getConnectionPoolExpireAfterCreation()).thenReturn(null);
        when(operational.getConnectionPoolLoadBalancingStrategy()).thenReturn(LoadBalancingStrategy.ROUND_ROBIN);
        when(operational.getConnectionPoolClusterConfigs()).thenReturn(Map.of(cluster,
                ConnectionPoolClusterConfig.builder().claimTimeout(Duration.ZERO).build()));

        assertThat(PoolSettings.from(operational, cluster).getClaimTimeoutMillis()).isZero();
        assertThatThrownBy(() -> PoolSettings.from(operational, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whole number of milliseconds");
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("invalidSelectedValues")
    void preflightAppliesEveryPoolConstraintToSelectedValues(final ConnectionPoolClusterConfig override, final String setting) {
        final BatchSupport module = new BatchSupport();
        assertThatThrownBy(() -> module.validateConnectionPoolConfiguration(defaults().build(), override))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(setting);
        assertThat(module).extracting("batchTransportEngine", "smtpConnectionPool").containsOnlyNulls();
    }

    private static Stream<Arguments> invalidSelectedValues() {
        return Stream.of(
                Arguments.of(ConnectionPoolClusterConfig.builder().coreSize(-1).build(), "coreSize"),
                Arguments.of(ConnectionPoolClusterConfig.builder().maxSize(0).build(), "maxSize"),
                Arguments.of(ConnectionPoolClusterConfig.builder().coreSize(4).build(), "coreSize"),
                Arguments.of(ConnectionPoolClusterConfig.builder().claimTimeout(Duration.ofMillis(-1)).build(), "claimTimeoutMillis"),
                Arguments.of(ConnectionPoolClusterConfig.builder().expireAfter(Duration.ofMillis(-1)).build(), "expireAfterMillis"),
                Arguments.of(ConnectionPoolClusterConfig.builder().expireAfterCreation(Duration.ZERO).build(), "expireAfterCreationMillis"));
    }

    private static ConnectionPoolConfig.Builder defaults() {
        return ConnectionPoolConfig.builder().withCoreSize(1).withMaxSize(3)
                .withClaimTimeout(Duration.ofMillis(100)).withExpireAfter(Duration.ZERO)
                .withExpireAfterCreation(Duration.ofSeconds(1)).withLoadBalancingStrategy(LoadBalancingStrategy.ROUND_ROBIN);
    }
}
