package org.simplejavamail.internal.batchsupport;

import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.bbottema.clusteredobjectpool.core.ClusterConfig;
import org.bbottema.clusteredobjectpool.cyclingstrategies.RandomAccessLoadBalancing;
import org.bbottema.clusteredobjectpool.cyclingstrategies.RoundRobinLoadBalancing;
import org.bbottema.genericobjectpool.ExpirationPolicy;
import org.bbottema.genericobjectpool.PoolableObject;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.batch.BatchTransportException;
import org.simplejavamail.batch.BatchTransportExecutor;
import org.simplejavamail.batch.BatchTransportPoolConfiguration;
import org.simplejavamail.smtpconnectionpool.SessionTransport;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

/** Independent fallback table for partial Mailer clusters and the standalone facade's whole-value overrides. */
class BatchConfigurationResolutionTest {
    @Test
    void everyPartialClusterMemberCombinationRetainsOmittedOperationalValues() {
        final UUID cluster = UUID.randomUUID();
        for (boolean baselineCreationAge : new boolean[] {false, true}) {
            for (int mask = 0; mask < 64; mask++) {
                final ConnectionPoolClusterConfig.ConnectionPoolClusterConfigBuilder override = ConnectionPoolClusterConfig.builder();
                if (selected(mask, 0)) { override.coreSize(2); }
                if (selected(mask, 1)) { override.maxSize(7); }
                if (selected(mask, 2)) { override.claimTimeout(Duration.ofMillis(303)); }
                if (selected(mask, 3)) { override.expireAfter(Duration.ZERO); }
                if (selected(mask, 4)) { override.expireAfterCreation(Duration.ofMillis(2000)); }
                if (selected(mask, 5)) { override.loadBalancingStrategy(LoadBalancingStrategy.RANDOM_ACCESS); }
                final OperationalConfig operational = operationalDefaults(baselineCreationAge);
                when(operational.getConnectionPoolClusterConfigs()).thenReturn(Map.of(cluster, override.build()));

                final ClusterConfig<UUID, Session, SessionTransport> actual = PoolSettings.from(operational, cluster)
                        .<UUID>toSmtpClusterConfig().getConfigBuilder().build();
                final String context = "creation=" + baselineCreationAge + " mask=" + mask;
                assertThat(actual.getDefaultCorePoolSize()).as(context).isEqualTo(selected(mask, 0) ? 2 : 1);
                assertThat(actual.getDefaultMaxPoolSize()).as(context).isEqualTo(selected(mask, 1) ? 7 : 5);
                assertThat(actual.getClaimTimeout().getDurationMs()).as(context).isEqualTo(selected(mask, 2) ? 303 : 101);
                assertThat(actual.getLoadBalancingStrategy()).as(context).isInstanceOf(selected(mask, 5)
                        ? RandomAccessLoadBalancing.class : RoundRobinLoadBalancing.class);

                final int idle = selected(mask, 3) ? 0 : 200;
                final Integer creation = selected(mask, 4) ? Integer.valueOf(2000) : baselineCreationAge ? Integer.valueOf(1000) : null;
                final ExpirationPolicy<SessionTransport> policy = actual.getDefaultExpirationPolicy();
                assertThat(policy.hasExpired(poolable(0, 199))).as(context).isFalse();
                assertThat(policy.hasExpired(poolable(0, 200))).as(context).isEqualTo(idle != 0);
                assertThat(policy.hasExpired(poolable(999, 0))).as(context).isFalse();
                assertThat(policy.hasExpired(poolable(1000, 0))).as(context).isEqualTo(creation != null && creation == 1000);
                assertThat(policy.hasExpired(poolable(2000, 0))).as(context).isEqualTo(creation != null);
            }
        }
        record("rule", "batch.partial-member-inheritance");
        record("adapter", "batch.mailer-cluster");
        record("case", "batch.partial-cluster-members");
    }

    @Test
    void anAbsentClusterUsesOperationalDefaultsWithoutReadingTheClusterMap() {
        final OperationalConfig operational = operationalDefaults(false);
        final var actual = PoolSettings.from(operational, null).<String>toSmtpClusterConfig().getConfigBuilder().build();
        assertThat(actual.getDefaultCorePoolSize()).isEqualTo(1);
        assertThat(actual.getDefaultMaxPoolSize()).isEqualTo(5);
        assertThat(actual.getClaimTimeout().getDurationMs()).isEqualTo(101);
        verify(operational, never()).getConnectionPoolClusterConfigs();
        record("case", "batch.absent-cluster-defaults");
    }

    @Test
    void selectedJavaClusterMembersReachRuntimeRegistrationAndUnusedClustersStayUnused() throws Exception {
        final UUID selected = UUID.randomUUID();
        final OperationalConfig operational = operationalDefaults(true);
        when(operational.getConnectionPoolClusterConfigs()).thenReturn(Map.of(selected,
                ConnectionPoolClusterConfig.builder().coreSize(0).maxSize(3).claimTimeout(Duration.ofMillis(303))
                        .expireAfter(Duration.ZERO).build(),
                UUID.randomUUID(), ConnectionPoolClusterConfig.builder().maxSize(0).build()));
        final Session session = session(new AtomicInteger());
        final BatchSupport batch = new BatchSupport();
        try {
            batch.registerToCluster(operational, selected, session, mock(SendingAllowance.class));
            assertThat(batch).extracting("batchTransportEngine").extracting("clusterSettings")
                    .asInstanceOf(InstanceOfAssertFactories.MAP).extractingByKey(selected)
                    .extracting("corePoolSize", "maxPoolSize", "claimTimeoutMillis", "expireAfterMillis",
                            "expireAfterCreationMillis", "loadBalancingStrategy")
                    .containsExactly(0, 3, 303, 0, 1000, org.simplejavamail.batch.BatchLoadBalancingStrategy.ROUND_ROBIN);
        } finally {
            batch.shutdownConnectionPools(session).get(5, TimeUnit.SECONDS);
        }
        record("rule", "batch.partial-member-inheritance");
        record("adapter", "batch.mailer-cluster");
        record("case", "batch.partial-cluster-members");
    }

    @Test
    void standaloneClusterOverridesAreCompleteReplaceableAndLeaveDefaultClustersIndependent() throws Exception {
        final ExecutorService borrowedWorkers = Executors.newFixedThreadPool(2);
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final AtomicInteger connects = new AtomicInteger();
        final Session session = session(connects);
        final BatchTransportPoolConfiguration defaults = BatchTransportPoolConfiguration.builder()
                .withCorePoolSize(0).withMaxPoolSize(2).withClaimTimeout(Duration.ofSeconds(1))
                .withExpireAfter(Duration.ZERO).withExpireAfterCreation(Duration.ofMinutes(1)).build();
        final BatchTransportPoolConfiguration superseded = BatchTransportPoolConfiguration.builder().withMaxPoolSize(4).build();
        final BatchTransportPoolConfiguration chosen = BatchTransportPoolConfiguration.builder()
                .withCorePoolSize(0).withMaxPoolSize(1).withClaimTimeout(Duration.ofMillis(25)).withExpireAfter(Duration.ZERO).build();
        final BatchTransportExecutor<String> facade = BatchTransportExecutor.<String>builder()
                .withDefaultPoolConfiguration(defaults)
                .withClusterConfiguration("limited", superseded)
                .withClusterConfiguration("limited", chosen)
                .withExecutorService(borrowedWorkers).build();
        try {
            assertThat(connects).hasValue(0);
            facade.registerSession("limited", session).registerSession("ordinary", session);
            assertThat(connects).hasValue(0); // Registration may inspect a disconnected provider, but does not connect it.
            final CompletableFuture<Void> first = facade.submit("limited", (selected, transport) -> {
                firstEntered.countDown();
                releaseFirst.await();
                return null;
            });
            assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> facade.execute("limited", (selected, transport) -> null))
                    .isInstanceOf(BatchTransportException.class).hasMessageContaining("Unable to claim");
            releaseFirst.countDown();
            first.get(2, TimeUnit.SECONDS);

            facade.execute("ordinary", (selected, firstTransport) -> facade.execute("ordinary", (nested, secondTransport) -> {
                assertThat(secondTransport).isNotSameAs(firstTransport);
                return null;
            }));
            assertThat(chosen.getExpireAfterCreation()).isNull();
        } finally {
            releaseFirst.countDown();
            try {
                facade.close();
                assertThat(borrowedWorkers.isShutdown()).isFalse();
            } finally {
                borrowedWorkers.shutdownNow();
            }
        }
    }

    private static boolean selected(final int mask, final int member) { return (mask & (1 << member)) != 0; }

    private static OperationalConfig operationalDefaults(final boolean creationAge) {
        final OperationalConfig operational = mock(OperationalConfig.class);
        when(operational.getConnectionPoolCoreSize()).thenReturn(1);
        when(operational.getConnectionPoolMaxSize()).thenReturn(5);
        when(operational.getConnectionPoolClaimTimeout()).thenReturn(Duration.ofMillis(101));
        when(operational.getConnectionPoolExpireAfter()).thenReturn(Duration.ofMillis(200));
        when(operational.getConnectionPoolExpireAfterCreation()).thenReturn(creationAge ? Duration.ofMillis(1000) : null);
        when(operational.getConnectionPoolLoadBalancingStrategy()).thenReturn(LoadBalancingStrategy.ROUND_ROBIN);
        return operational;
    }

    @SuppressWarnings("unchecked")
    private static PoolableObject<SessionTransport> poolable(final long age, final long allocationAge) {
        final PoolableObject<SessionTransport> pooled = mock(PoolableObject.class);
        when(pooled.getExpiriesMs()).thenReturn(new HashMap<>());
        when(pooled.ageMs()).thenReturn(age);
        when(pooled.allocationAgeMs()).thenReturn(allocationAge);
        return pooled;
    }

    private static Session session(final AtomicInteger connects) throws Exception {
        final Session session = mock(Session.class);
        when(session.getProperties()).thenReturn(new Properties());
        when(session.getTransport()).thenAnswer(call -> {
            final Transport transport = mock(Transport.class);
            when(transport.getURLName()).thenReturn(new URLName("smtp", "localhost", 25, null, null, null));
            when(transport.isConnected()).thenReturn(true);
            org.mockito.Mockito.doAnswer(connect -> { connects.incrementAndGet(); return null; }).when(transport).connect();
            return transport;
        });
        return session;
    }
}
