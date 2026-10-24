package org.simplejavamail.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.internal.config.ConfigurationSnapshot.locksOf;

class ConnectionConflictPolicyPropertyTest {
    @Test
    void absenceLeavesPolicyResolutionToTheMailer() {
        final SimpleJavaMailConfig config = ConfigLoader.builder().load();
        assertThat(config.get(ConfigKeys.CONNECTION_CONFLICT_POLICY)).isNull();
        assertThat(config.hasProperty(ConfigKeys.CONNECTION_CONFLICT_POLICY)).isFalse();
        assertThat(config.getPropertySource(ConfigKeys.CONNECTION_CONFLICT_POLICY)).isNull();
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void textAndTypedInputsHaveTheSameSchemaAndVisibleSource(final ConnectionConflictPolicy policy) {
        for (final Object value : new Object[] {policy, policy.name()}) {
            final SimpleJavaMailConfig config = ConfigLoader.builder().withMap("deployment", Map.of(
                    ConfigKeys.CONNECTION_CONFLICT_POLICY.key(), value)).load();
            assertThat(config.get(ConfigKeys.CONNECTION_CONFLICT_POLICY)).isSameAs(policy);
            assertThat(config.asMap()).containsEntry(ConfigLoader.Property.CONNECTION_CONFLICT_POLICY, policy);
            assertThat(config.getPropertySource(ConfigKeys.CONNECTION_CONFLICT_POLICY)).isEqualTo("deployment");
            assertThat(config.getDiagnostics().getProperties(ConfigDiagnosticGroup.SMTP_CONNECTION)).anySatisfy(diagnostic -> {
                assertThat(diagnostic.getPropertyName()).isEqualTo(ConfigKeys.CONNECTION_CONFLICT_POLICY.key());
                assertThat(diagnostic.getDisplayValue()).isEqualTo(policy.name());
                assertThat(diagnostic.getSourceName()).isEqualTo("deployment");
                assertThat(diagnostic.isRedacted()).isFalse();
            });
        }
    }

    @Test
    void sourcesResolveBeforeTheConflictPolicyAndSnapshotsRemainIndependent() {
        final Map<String, Object> deployment = new LinkedHashMap<>();
        deployment.put(ConfigKeys.CONNECTION_CONFLICT_POLICY.key(), "ALLOW_EXPLICIT_OVERRIDES");
        final ConfigLoader loader = ConfigLoader.builder()
                .withMap("application", Map.of(ConfigKeys.CONNECTION_CONFLICT_POLICY.key(), "REJECT_CONFLICTS"))
                .withMap("deployment", deployment);
        final SimpleJavaMailConfig permissive = loader.load();
        deployment.put(ConfigKeys.CONNECTION_CONFLICT_POLICY.key(), "REJECT_CONFLICTS");
        final SimpleJavaMailConfig strict = ConfigLoader.builder().withMap(deployment).load();
        assertThat(permissive.get(ConfigKeys.CONNECTION_CONFLICT_POLICY)).isSameAs(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
        assertThat(permissive.getPropertySource(ConfigKeys.CONNECTION_CONFLICT_POLICY)).isEqualTo("deployment");
        assertThat(strict.get(ConfigKeys.CONNECTION_CONFLICT_POLICY)).isSameAs(ConnectionConflictPolicy.REJECT_CONFLICTS);
        assertThat(ConfigLoader.builder().withConfig(permissive).load().get(ConfigKeys.CONNECTION_CONFLICT_POLICY))
                .isSameAs(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
    }

    @Test
    void policyLocksAndNamedConfigurationIsolationSurviveSnapshotRoundTrips() {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap("central configuration", Map.of(
                ConfigKeys.CONNECTION_CONFLICT_POLICY.key(), "ALLOW_EXPLICIT_OVERRIDES",
                "simplejavamail.locked.connection.conflictpolicy", "REJECT_CONFLICTS",
                "simplejavamail.mailers.secondary.connection.conflictpolicy", "ALLOW_EXPLICIT_OVERRIDES")).load();
        for (final SimpleJavaMailConfig snapshot : new SimpleJavaMailConfig[] {config, ConfigLoader.builder().withConfig(config).load()}) {
            assertThat(snapshot.get(ConfigKeys.CONNECTION_CONFLICT_POLICY)).isSameAs(ConnectionConflictPolicy.REJECT_CONFLICTS);
            assertThat(locksOf(snapshot).contains(ConfigLoader.Property.CONNECTION_CONFLICT_POLICY)).isTrue();
            final SimpleJavaMailConfig secondary = snapshot.get(ConfigKeys.NAMED_MAILERS).get("secondary");
            assertThat(secondary.get(ConfigKeys.CONNECTION_CONFLICT_POLICY)).isSameAs(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
            assertThat(locksOf(secondary).contains(ConfigLoader.Property.CONNECTION_CONFLICT_POLICY)).isFalse();
        }
        assertThat(config.getDiagnostics().toString()).contains(
                "simplejavamail.locked.connection.conflictpolicy = REJECT_CONFLICTS (source: central configuration)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OVERRIDE", "reject_conflicts", "true"})
    void invalidNamesFailWithTheConfigurationSource(final String value) {
        for (final String property : new String[] {ConfigKeys.CONNECTION_CONFLICT_POLICY.key(), "simplejavamail.locked.connection.conflictpolicy"}) {
            assertThatThrownBy(() -> ConfigLoader.builder().withMap("deployment", Map.of(property, value)).load())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(property, "deployment", "connection conflict policy");
        }
    }

    @Test
    void unrelatedEnumsAndBooleansCannotBeUsedAsPolicyValues() {
        for (final Object value : new Object[] {TransportStrategy.SMTP, true}) {
            assertThatThrownBy(() -> ConfigLoader.builder().withMap("deployment", Map.of(
                    ConfigKeys.CONNECTION_CONFLICT_POLICY.key(), value)).load())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(ConfigKeys.CONNECTION_CONFLICT_POLICY.key(), "deployment", "connection conflict policy");
        }
    }
}
