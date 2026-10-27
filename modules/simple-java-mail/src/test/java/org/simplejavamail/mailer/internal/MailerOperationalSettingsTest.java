package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.simplejavamail.api.mailer.config.AsyncQueueConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import org.simplejavamail.api.mailer.config.SessionDebugConfig;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_ASYNC_QUEUE_CAPACITY;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT;
import static org.simplejavamail.api.mailer.config.LoadBalancingStrategy.ROUND_ROBIN;

class MailerOperationalSettingsTest {
    private static final UUID CLUSTER = UUID.fromString("a5aa935b-ab1e-4728-855f-705968ce9721");

    @Test
    void capturedClusterStructureIsIndependentAndBorrowedValuesRetainIdentity() {
        final ConnectionPoolClusterConfig cluster = ConnectionPoolClusterConfig.builder().coreSize(1).build();
        final Map<UUID, ConnectionPoolClusterConfig> source = new LinkedHashMap<>();
        source.put(CLUSTER, cluster);
        final MailerOperationalSettings captured = settings().connectionPoolClusterConfigs(source).build();
        source.clear();

        assertThat(captured.getConnectionPoolClusterConfigs()).containsEntry(CLUSTER, cluster);
        assertThat(captured.getConnectionPoolClusterConfigs().get(CLUSTER)).isSameAs(cluster);
        assertThatThrownBy(() -> captured.getConnectionPoolClusterConfigs().clear()).isInstanceOf(UnsupportedOperationException.class);
        final MailerOperationalSettings replacement = captured.toBuilder().connectionPoolClusterConfigs(Map.of()).build();
        assertThat(replacement.getConnectionPoolClusterConfigs()).isEmpty();
        assertThat(captured.getConnectionPoolClusterConfigs()).containsEntry(CLUSTER, cluster);
    }

    @Test
    void borrowedExecutorsKeepTheirWorkerConfigurationAndOwnership() {
        final ExecutorService borrowed = mock(ExecutorService.class);
        final MailerOperationalSettings captured = settings().executorService(borrowed).threadPoolSize(0)
                .threadPoolKeepAliveTime(Duration.ofMillis(-1)).build();
        captured.validateExecution();
        assertThat(captured.getExecutorService()).isSameAs(borrowed);
        assertThatThrownBy(captured::createOwnedExecutor).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> captured.toBuilder().asyncQueueConfig(new AsyncQueueConfig(1,
                DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY, DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT)).build().validateExecution())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("caller-owned executor");
        verifyNoInteractions(borrowed);
    }

    @Test
    void ownedWorkerRequirementsAreCheckedWithoutAllocatingAnExecutor() {
        try (MockedConstruction<MailSendExecutor> executors = mockConstruction(MailSendExecutor.class)) {
            assertThatThrownBy(() -> settings().threadPoolSize(0).build().validateExecution())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("withThreadPoolSize");
            assertThatThrownBy(() -> settings().threadPoolKeepAliveTime(Duration.ofMillis(-1)).build().validateExecution())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("withKeepAliveTime");
            settings().threadPoolKeepAliveTime(Duration.ZERO).build().validateExecution();
            assertThat(executors.constructed()).isEmpty();
        }
    }

    @Test
    void completedPoolDefaultsDistinguishDisabledAndSelectedMaximumAge() {
        final MailerOperationalSettings disabled = settings().build();
        assertThat(disabled.poolDefaults().getClusterKey()).isEqualTo(CLUSTER);
        assertThat(disabled.poolDefaults().getExpireAfterCreation()).isNull();
        assertThat(disabled.poolDefaults().isExpireAfterCreationCleared()).isTrue();
        final MailerOperationalSettings selected = disabled.toBuilder().connectionPoolExpireAfterCreation(Duration.ofSeconds(5)).build();
        assertThat(selected.poolDefaults().getExpireAfterCreation()).isEqualTo(Duration.ofSeconds(5));
        assertThat(selected.poolDefaults().isExpireAfterCreationCleared()).isFalse();
        assertThat(disabled.poolDefaults().getExpireAfterCreation()).isNull();
    }

    private static MailerOperationalSettings.MailerOperationalSettingsBuilder settings() {
        return MailerOperationalSettings.builder().telemetryConfig(MailSendTelemetryConfig.builder().build())
                .threadPoolSize(1).threadPoolKeepAliveTime(Duration.ofMillis(1)).clusterKey(CLUSTER)
                .connectionPoolCoreSize(0).connectionPoolMaxSize(4).connectionPoolClaimTimeout(Duration.ofSeconds(5))
                .connectionPoolExpireAfter(Duration.ofSeconds(5)).connectionPoolLoadBalancingStrategy(ROUND_ROBIN)
                .connectionPoolClusterConfigs(Map.of()).debugConfig(SessionDebugConfig.builder().build())
                .asyncQueueConfig(new AsyncQueueConfig(DEFAULT_ASYNC_QUEUE_CAPACITY,
                        DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY, DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT))
                .mailSendRetryPolicy(MailSendRetryPolicy.builder().build());
    }
}
