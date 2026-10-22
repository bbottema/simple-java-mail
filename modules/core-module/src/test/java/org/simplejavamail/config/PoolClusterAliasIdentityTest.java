package org.simplejavamail.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PoolClusterAliasIdentityTest {
    private static final UUID CLUSTER = UUID.fromString("00000000-0000-0000-0000-00000000abcd");
    private static final UUID OTHER_CLUSTER = UUID.fromString("00000000-0000-0000-0000-00000000abce");

    @Test
    void rejectsTwoNamedAliasesForOneUuidWithDeterministicActionableError() {
        final Map<String, Object> firstOrder = new LinkedHashMap<>();
        firstOrder.put(cluster("orders", "clusterkey.uuid"), CLUSTER.toString());
        firstOrder.put(cluster("orders", "maxsize"), 2);
        firstOrder.put(cluster("bulk", "clusterkey.uuid"), CLUSTER.toString());
        firstOrder.put(cluster("bulk", "expireafter.millis"), 30_000);
        final Map<String, Object> reversedOrder = new LinkedHashMap<>();
        firstOrder.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> reversedOrder.put(entry.getKey(), entry.getValue()));

        final IllegalArgumentException first = collision(ConfigLoader.builder().withMap(firstOrder), "bulk", "orders");
        final IllegalArgumentException reversed = collision(ConfigLoader.builder().withMap(reversedOrder), "bulk", "orders");

        assertThat(reversed.getMessage()).isEqualTo(first.getMessage());
    }

    @Test
    void rejectsNamedAndDirectUuidAliasesContributedByDifferentSources() {
        collision(ConfigLoader.builder()
                .withMap("company defaults", Map.of(
                        cluster("orders", "clusterkey.uuid"), CLUSTER.toString(),
                        cluster("orders", "maxsize"), 2))
                .withMap("application overrides", Map.of(cluster(CLUSTER.toString(), "expireafter.millis"), 30_000)),
                CLUSTER.toString(), "orders");
    }

    @Test
    void rejectsAnAliasCollisionRatherThanDiscardingALockedLeaf() {
        collision(ConfigLoader.builder()
                .withMap("company defaults", Map.of(
                        cluster("orders", "clusterkey.uuid"), CLUSTER.toString(),
                        lockedCluster("orders", "maxsize"), 2))
                .withMap("application overrides", Map.of(cluster(CLUSTER.toString(), "expireafter.millis"), 30_000)),
                CLUSTER.toString(), "orders");
    }

    @ParameterizedTest
    @ValueSource(strings = {"00000000-0000-0000-0000-00000000ABCD", "0-0-0-0-abcd"})
    void comparesResolvedUuidIdentityRatherThanItsTextualSpelling(final String equivalentUuidAlias) {
        collision(ConfigLoader.builder().withMap(Map.of(
                cluster(CLUSTER.toString(), "maxsize"), 2,
                cluster(equivalentUuidAlias, "expireafter.millis"), 30_000)),
                equivalentUuidAlias, CLUSTER.toString());
    }

    @Test
    void laterLeavesForTheSameAliasRetainOrderedSourceResolution() {
        final SimpleJavaMailConfig config = ConfigLoader.builder()
                .withMap("company defaults", Map.of(
                        cluster("orders", "clusterkey.uuid"), CLUSTER.toString(),
                        cluster("orders", "maxsize"), 2))
                .withMap("application overrides", Map.of(
                        cluster("orders", "clusterkey.uuid"), CLUSTER.toString().toUpperCase(Locale.ROOT),
                        cluster("orders", "maxsize"), 5,
                        cluster("orders", "expireafter.millis"), 30_000))
                .load();

        final Map<UUID, ConnectionPoolClusterConfig> clusters = config.get(ConfigKeys.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS);
        assertThat(clusters).containsOnlyKeys(CLUSTER);
        assertThat(clusters.get(CLUSTER).getMaxSize()).isEqualTo(5);
        assertThat(clusters.get(CLUSTER).getExpireAfter()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void sameAliasLocksSurviveOrdinaryOverridesAndSnapshotCopying() {
        final SimpleJavaMailConfig company = ConfigLoader.builder().withMap("company defaults", Map.of(
                cluster("orders", "clusterkey.uuid"), CLUSTER.toString(),
                lockedCluster("orders", "maxsize"), 2)).load();
        final SimpleJavaMailConfig config = ConfigLoader.builder().withConfig(company)
                .withMap("application overrides", Map.of(
                        cluster("orders", "maxsize"), 99,
                        cluster("orders", "expireafter.millis"), 30_000))
                .load();

        final Map<UUID, ConnectionPoolClusterConfig> clusters = config.get(ConfigKeys.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS);
        assertThat(clusters).containsOnlyKeys(CLUSTER);
        assertThat(clusters.get(CLUSTER).getMaxSize()).isEqualTo(2);
        assertThat(clusters.get(CLUSTER).getExpireAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(company.get(ConfigKeys.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS).get(CLUSTER).getExpireAfter()).isNull();
        assertThat(config.getDiagnostics().toString()).contains(lockedCluster("orders", "maxsize"));
    }

    @Test
    void independentNamedAndDirectAliasesKeepTheirSeparatePartialConfigurations() {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap(Map.of(
                cluster("orders", "clusterkey.uuid"), CLUSTER.toString(),
                cluster("orders", "maxsize"), 2,
                cluster(OTHER_CLUSTER.toString(), "expireafter.millis"), 30_000)).load();

        final Map<UUID, ConnectionPoolClusterConfig> clusters = config.get(ConfigKeys.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS);
        assertThat(clusters).containsOnlyKeys(CLUSTER, OTHER_CLUSTER);
        assertThat(clusters.get(CLUSTER).getMaxSize()).isEqualTo(2);
        assertThat(clusters.get(CLUSTER).getExpireAfter()).isNull();
        assertThat(clusters.get(OTHER_CLUSTER).getMaxSize()).isNull();
        assertThat(clusters.get(OTHER_CLUSTER).getExpireAfter()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void identityValidationDoesNotApplyOptionalPoolSizeSemantics() {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap(Map.of(
                cluster("unused", "clusterkey.uuid"), CLUSTER.toString(),
                cluster("unused", "maxsize"), -1)).load();

        assertThat(config.get(ConfigKeys.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS).get(CLUSTER).getMaxSize()).isEqualTo(-1);
    }

    private static IllegalArgumentException collision(final ConfigLoader loader, final String firstAlias, final String secondAlias) {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, loader::load);
        assertThat(failure).hasMessageContaining(firstAlias)
                .hasMessageContaining(secondAlias)
                .hasMessageContaining(CLUSTER.toString())
                .hasMessageContaining("Use one alias per cluster UUID");
        return failure;
    }

    private static String cluster(final String alias, final String field) {
        return "simplejavamail.defaults.connectionpool.clusters." + alias + "." + field;
    }

    private static String lockedCluster(final String alias, final String field) {
        return "simplejavamail.locked.defaults.connectionpool.clusters." + alias + "." + field;
    }
}
