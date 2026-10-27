package org.simplejavamail.internal.batchsupport;

import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.junit.jupiter.api.Test;
import org.simplejavamail.batch.BatchLoadBalancingStrategy;
import org.simplejavamail.batch.BatchTransportExecutor;
import org.simplejavamail.batch.BatchTransportExecutorBuilder;
import org.simplejavamail.batch.BatchTransportPoolConfiguration;
import testutil.configcontracts.ConfigurationContractCoverage;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

/** Calls the supported standalone setup routes and checks their produced values and selected runtime pool settings. */
class StandaloneBatchConfigurationContractTest {
    @Test
    void theStateCarryingPoolFactoryCreatesAnIndependentDraft() throws Exception {
        final BatchTransportPoolConfiguration original = BatchTransportPoolConfiguration.builder()
                .withCorePoolSize(1).withMaxPoolSize(7).withClaimTimeout(Duration.ofMillis(303))
                .withExpireAfter(Duration.ofMillis(505)).withExpireAfterCreation(Duration.ofMillis(707))
                .withLoadBalancingStrategy(BatchLoadBalancingStrategy.RANDOM_ACCESS).build();
        final BatchTransportPoolConfiguration.Builder draft = (BatchTransportPoolConfiguration.Builder) ConfigurationContractCoverage.invoke(
                null, BatchTransportPoolConfiguration.class.getMethod("builder", BatchTransportPoolConfiguration.class), original);
        final BatchTransportPoolConfiguration retained = draft.build();
        assertThat(retained).extracting("corePoolSize", "maxPoolSize", "claimTimeout", "expireAfter", "expireAfterCreation", "loadBalancingStrategy")
                .containsExactly(1, 7, Duration.ofMillis(303), Duration.ofMillis(505), Duration.ofMillis(707), BatchLoadBalancingStrategy.RANDOM_ACCESS);
        final BatchTransportPoolConfiguration edited = draft.clearExpireAfterCreation().withMaxPoolSize(9).build();
        assertThat(edited.getExpireAfterCreation()).isNull();
        assertThat(edited.getMaxPoolSize()).isEqualTo(9);
        assertThat(original.getExpireAfterCreation()).isEqualTo(Duration.ofMillis(707));
        assertThat(retained.getMaxPoolSize()).isEqualTo(7);
        record("case", "batch.pool-copy-factory");
    }

    @Test
    void poolValueBuilderRoutesProduceIndependentCompleteValues() throws Exception {
        final BatchTransportPoolConfiguration.Builder builder = BatchTransportPoolConfiguration.builder();
        call(builder, "withCorePoolSize", int.class, 1);
        call(builder, "withMaxPoolSize", int.class, 7);
        call(builder, "withClaimTimeout", Duration.class, Duration.ofMillis(303));
        call(builder, "withExpireAfter", Duration.class, Duration.ofMillis(505));
        call(builder, "withExpireAfterCreation", Duration.class, Duration.ofMillis(707));
        call(builder, "withLoadBalancingStrategy", BatchLoadBalancingStrategy.class, BatchLoadBalancingStrategy.RANDOM_ACCESS);
        final BatchTransportPoolConfiguration original = builder.build();
        assertThat(original).extracting("corePoolSize", "maxPoolSize", "claimTimeout", "expireAfter", "expireAfterCreation", "loadBalancingStrategy")
                .containsExactly(1, 7, Duration.ofMillis(303), Duration.ofMillis(505), Duration.ofMillis(707), BatchLoadBalancingStrategy.RANDOM_ACCESS);

        call(builder, "clearExpireAfterCreation");
        assertThat(builder.build().getExpireAfterCreation()).isNull();
        assertThat(original.getExpireAfterCreation()).isEqualTo(Duration.ofMillis(707));
        record("rule", "batch.pool-value-snapshot");
        record("case", "batch.pool-value-builder");
    }

    @Test
    void facadeScalarRoutesReachTheRuntimePoolAndOwnedWorkers() throws Exception {
        final BatchTransportExecutorBuilder<String> builder = BatchTransportExecutor.builder();
        call(builder, "withCorePoolSize", int.class, 1);
        call(builder, "withMaxPoolSize", int.class, 5);
        call(builder, "withClaimTimeout", Duration.class, Duration.ofMillis(113));
        call(builder, "withExpireAfter", Duration.class, Duration.ofMillis(227));
        call(builder, "withExpireAfterCreation", Duration.class, Duration.ofMillis(331));
        call(builder, "withLoadBalancingStrategy", BatchLoadBalancingStrategy.class, BatchLoadBalancingStrategy.RANDOM_ACCESS);
        call(builder, "withThreadPoolSize", int.class, 2);
        call(builder, "withThreadPoolKeepAliveTime", Duration.class, Duration.ofMillis(47));

        final ThreadPoolExecutor workers;
        try (BatchTransportExecutor<String> facade = builder.build()) {
            facade.registerSession("ordinary", session());
            assertPoolSettings(facade, "ordinary", 1, 5, 113, 227, 331, BatchLoadBalancingStrategy.RANDOM_ACCESS);
            workers = (ThreadPoolExecutor) field(facade, "executorService");
            assertThat(workers.getCorePoolSize()).isEqualTo(2);
            assertThat(workers.getMaximumPoolSize()).isEqualTo(2);
            assertThat(workers.getKeepAliveTime(TimeUnit.MILLISECONDS)).isEqualTo(47);
        }
        assertThat(workers.isShutdown()).isTrue();
        recordPoolFields();
        record("field", "batch.executor");
        record("rule", "batch.scalar-field-wiring");
        record("rule", "batch.owned-worker-settings");
        record("adapter", "batch.standalone");
        record("case", "batch.facade-scalar-settings");
    }

    @Test
    void facadeDefaultAndClusterRoutesReplaceWholeValuesAndBorrowExecutors() throws Exception {
        final BatchTransportPoolConfiguration defaults = BatchTransportPoolConfiguration.builder()
                .withCorePoolSize(0).withMaxPoolSize(7).withClaimTimeout(Duration.ofMillis(211))
                .withExpireAfter(Duration.ofMillis(419)).withExpireAfterCreation(Duration.ofMillis(631))
                .withLoadBalancingStrategy(BatchLoadBalancingStrategy.RANDOM_ACCESS).build();
        final BatchTransportPoolConfiguration superseded = BatchTransportPoolConfiguration.builder().withMaxPoolSize(9).build();
        final BatchTransportPoolConfiguration selected = BatchTransportPoolConfiguration.builder()
                .withCorePoolSize(0).withMaxPoolSize(2).withClaimTimeout(Duration.ofMillis(101))
                .withExpireAfter(Duration.ZERO).withLoadBalancingStrategy(BatchLoadBalancingStrategy.ROUND_ROBIN).build();
        final ExecutorService borrowed = Executors.newSingleThreadExecutor();
        try {
            final BatchTransportExecutorBuilder<String> builder = BatchTransportExecutor.builder();
            call(builder, "withDefaultPoolConfiguration", BatchTransportPoolConfiguration.class, defaults);
            call(builder, "clearExpireAfterCreation");
            call(builder, "withClusterConfiguration", new Class<?>[] {Object.class, BatchTransportPoolConfiguration.class}, "selected", superseded);
            call(builder, "withClusterConfiguration", new Class<?>[] {Object.class, BatchTransportPoolConfiguration.class}, "selected", selected);
            call(builder, "withExecutorService", ExecutorService.class, borrowed);
            try (BatchTransportExecutor<String> facade = builder.build()) {
                facade.registerSession("ordinary", session()).registerSession("selected", session());
                assertPoolSettings(facade, "ordinary", 0, 7, 211, 419, null, BatchLoadBalancingStrategy.RANDOM_ACCESS);
                assertPoolSettings(facade, "selected", 0, 2, 101, 0, null, BatchLoadBalancingStrategy.ROUND_ROBIN);
                assertThat(field(facade, "executorService")).isSameAs(borrowed);
            }
            assertThat(borrowed.isShutdown()).isFalse();
        } finally {
            borrowed.shutdownNow();
        }
        assertThat(defaults.getExpireAfterCreation()).isEqualTo(Duration.ofMillis(631));
        record("rule", "batch.cluster-whole-replacement");
        record("rule", "batch.borrowed-executor-ownership");
        record("case", "batch.facade-default-cluster-replacement");
    }

    private static void recordPoolFields() {
        for (String field : new String[] {"coreSize", "maxSize", "claimTimeout", "expireAfter", "expireAfterCreation", "loadBalancingStrategy"}) {
            record("field", "batch.pool." + field);
        }
    }

    private static void assertPoolSettings(final BatchTransportExecutor<String> facade, final String cluster,
            final int core, final int maximum, final int claim, final int idle, final Integer age, final BatchLoadBalancingStrategy strategy)
            throws ReflectiveOperationException {
        final Map<?, ?> settings = (Map<?, ?>) field(field(facade, "engine"), "clusterSettings");
        assertThat(settings.get(cluster)).extracting("corePoolSize", "maxPoolSize", "claimTimeoutMillis", "expireAfterMillis",
                "expireAfterCreationMillis", "loadBalancingStrategy").containsExactly(core, maximum, claim, idle, age, strategy);
    }

    private static Object field(final Object owner, final String name) throws ReflectiveOperationException {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static Session session() throws Exception {
        final Session session = mock(Session.class);
        when(session.getProperties()).thenReturn(new Properties());
        when(session.getTransport()).thenAnswer(call -> {
            final Transport transport = mock(Transport.class);
            when(transport.getURLName()).thenReturn(new URLName("smtp", "localhost", 25, null, null, null));
            when(transport.isConnected()).thenReturn(true);
            return transport;
        });
        return session;
    }

    private static void call(final Object receiver, final String name, final Class<?> type, final Object value) throws Exception {
        call(receiver, name, new Class<?>[] {type}, value);
    }

    private static void call(final Object receiver, final String name, final Class<?>[] types, final Object... values) throws Exception {
        ConfigurationContractCoverage.invoke(receiver, receiver.getClass().getMethod(name, types), values);
    }

    private static void call(final Object receiver, final String name) throws Exception {
        call(receiver, name, new Class<?>[0]);
    }
}
