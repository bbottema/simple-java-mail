package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.configresolution.ConfigurationContractModel;

import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Finite source/authority matrix. The expected outcome is independent of production interpretation and selection. */
class ConnectionResolutionMatrixTest {
    enum Setting {
        CONNECT("defaults.connecttimeoutmillis", "connectiontimeout", "123", "456", "60000"),
        READ("defaults.readtimeoutmillis", "timeout", "123", "456", "60000"),
        WRITE("defaults.writetimeoutmillis", "writetimeout", "123", "456", "60000"),
        LOCAL_ADDRESS("smtp.localaddress", "localaddress", "192.0.2.1", "192.0.2.2", null),
        LOCAL_PORT("smtp.localport", "localport", "123", "456", null),
        EHLO("smtp.clienthostname", "localhost", "first.example.org", "second.example.org", null),
        IDENTITY("defaults.verifyserveridentity", "ssl.checkserveridentity", "true", "false", "true"),
        HOST("smtp.host", "host", "first.example.org", "second.example.org", null),
        PORT("smtp.port", "port", "123", "456", "25"),
        USERNAME("smtp.username", "user", "first-user", "second-user", null);

        final String property;
        final String suffix;
        final String first;
        final String second;
        final String fallback;

        Setting(final String property, final String suffix, final String first, final String second, final String fallback) {
            this.property = property;
            this.suffix = suffix;
            this.first = first;
            this.second = second;
            this.fallback = fallback;
        }

        String value(final int state) { return state == 0 ? null : state == 1 ? first : second; }

        void supply(final MailerGenericBuilder<?> builder, final int state) {
            if (state == 0) { return; }
            final String value = value(state);
            final ConnectionConfig.Builder connection = ConnectionConfig.builder();
            switch (this) {
                case CONNECT: connection.withConnectTimeout(Duration.ofMillis(Integer.parseInt(value))); break;
                case READ: connection.withReadTimeout(Duration.ofMillis(Integer.parseInt(value))); break;
                case WRITE: connection.withWriteTimeout(Duration.ofMillis(Integer.parseInt(value))); break;
                case LOCAL_ADDRESS: connection.withLocalBindAddress(value); break;
                case LOCAL_PORT: connection.withLocalBindPort(Integer.valueOf(value)); break;
                case EHLO: connection.withSmtpClientHostname(value); break;
                case IDENTITY: builder.withTlsConfig(TlsConfig.builder().verifyingServerIdentity(Boolean.parseBoolean(value)).build()); return;
                case HOST: ((MailerRegularBuilder<?>) builder).withSMTPServerHost(value); return;
                case PORT: ((MailerRegularBuilder<?>) builder).withSMTPServerPort(Integer.valueOf(value)); return;
                case USERNAME: ((MailerRegularBuilder<?>) builder).withSMTPServerUsername(value); return;
                default: throw new AssertionError(this);
            }
            builder.withConnectionConfig(connection.build());
        }
    }

    enum Rule { ABSENCE, SOURCE_MAPPING, EQUIVALENCE, CONFLICT, EXPLICIT_OVERRIDE, MANDATORY_LOCK, CHANNEL_REPLACEMENT }

    enum Scenario {
        OWNED_GAP(Rule.ABSENCE, TransportStrategy.SMTP, false, ConnectionConflictPolicy.REJECT_CONFLICTS, 0, 0,0,0,0,0),
        SUPPLIED_GAP(Rule.ABSENCE, TransportStrategy.SMTPS, true, ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES, 0, 0,0,0,0,0),
        CONFIGURED_TYPED(Rule.SOURCE_MAPPING, TransportStrategy.SMTP, false, ConnectionConflictPolicy.REJECT_CONFLICTS, 0, 1,0,0,0,0),
        CONFIGURED_RAW(Rule.SOURCE_MAPPING, TransportStrategy.SMTPS, false, ConnectionConflictPolicy.REJECT_CONFLICTS, 0, 0,1,0,0,0),
        SESSION(Rule.SOURCE_MAPPING, TransportStrategy.SMTP, true, ConnectionConflictPolicy.REJECT_CONFLICTS, 0, 0,0,1,0,0),
        JAVA_RAW(Rule.SOURCE_MAPPING, TransportStrategy.SMTPS, true, ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES, 0, 0,0,0,1,0),
        JAVA_TYPED(Rule.SOURCE_MAPPING, TransportStrategy.SMTP, false, ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES, 0, 0,0,0,0,1),
        EQUAL_DECLARATIONS(Rule.EQUIVALENCE, TransportStrategy.SMTPS, false, ConnectionConflictPolicy.REJECT_CONFLICTS, 0, 1,1,0,1,1),
        RAW_CONFLICT(Rule.CONFLICT, TransportStrategy.SMTP, false, ConnectionConflictPolicy.REJECT_CONFLICTS, 0, 1,2,0,0,0),
        SESSION_CONFLICT(Rule.CONFLICT, TransportStrategy.SMTPS, true, ConnectionConflictPolicy.REJECT_CONFLICTS, 0, 0,0,1,2,0),
        RAW_OVERRIDE(Rule.EXPLICIT_OVERRIDE, TransportStrategy.SMTPS, false, ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES, 0, 1,2,0,0,0),
        TYPED_OVERRIDE(Rule.EXPLICIT_OVERRIDE, TransportStrategy.SMTP, false, ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES, 0, 1,1,0,1,2),
        SESSION_OVERRIDE(Rule.EXPLICIT_OVERRIDE, TransportStrategy.SMTP, true, ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES, 0, 0,0,1,2,0),
        TYPED_LOCK_EQUAL(Rule.MANDATORY_LOCK, TransportStrategy.SMTPS, false, ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES, 1, 0,0,0,0,1),
        TYPED_LOCK_CONFLICT(Rule.MANDATORY_LOCK, TransportStrategy.SMTP, false, ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES, 1, 0,0,0,0,2),
        RAW_LOCK_EQUAL(Rule.MANDATORY_LOCK, TransportStrategy.SMTP, true, ConnectionConflictPolicy.REJECT_CONFLICTS, 2, 0,0,1,1,0),
        RAW_LOCK_CONFLICT(Rule.MANDATORY_LOCK, TransportStrategy.SMTPS, true, ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES, 2, 0,0,1,2,0),
        RAW_CHANNEL_REPLACEMENT(Rule.CHANNEL_REPLACEMENT, TransportStrategy.SMTPS, false, ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES, 1, 0,2,0,1,0);

        final Rule rule;
        final TransportStrategy strategy;
        final boolean supplied;
        final ConnectionConflictPolicy policy;
        final int lock;
        final int[] choices;
        Scenario(final Rule rule, final TransportStrategy strategy, final boolean supplied, final ConnectionConflictPolicy policy,
                final int lock, final int... choices) {
            this.rule = rule; this.strategy = strategy; this.supplied = supplied; this.policy = policy; this.lock = lock; this.choices = choices;
        }
    }

    @Test
    void scenarioCatalogueAccountsForEveryAuthorityRuleAndSupportedContext() {
        final Set<Rule> rules = EnumSet.noneOf(Rule.class);
        final Set<TransportStrategy> protocols = EnumSet.noneOf(TransportStrategy.class);
        final Set<ConnectionConflictPolicy> policies = EnumSet.noneOf(ConnectionConflictPolicy.class);
        for (Scenario scenario : Scenario.values()) { rules.add(scenario.rule); protocols.add(scenario.strategy); policies.add(scenario.policy); }
        assertThat(rules).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(Rule.class));
        assertThat(protocols).containsExactlyInAnyOrder(TransportStrategy.SMTP, TransportStrategy.SMTPS);
        assertThat(policies).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(ConnectionConflictPolicy.class));
    }

    @ParameterizedTest
    @EnumSource(Setting.class)
    void authorityRulesApplyThroughEveryScalarInputAdapter(final Setting setting) {
        ConfigurationAdoptionCatalogue.assertPropertyCategory(ConfigurationAdoptionCatalogue.Owner.MAILER_CONNECTION,
                "simplejavamail." + setting.property);
        ConfigurationAdoptionCatalogue.assertApiCategory(ConfigurationAdoptionCatalogue.Owner.MAILER_CONNECTION,
                MailerGenericBuilder.class, "withConnectionConfig", "useConfiguredConnectionConfig", "withConnectionConflictPolicy");
        // Rules are sampled by category once; this small mapping sweep ensures every adopted field reaches those rules.
        for (Scenario scenario : Scenario.values()) {
            verify(setting, scenario.strategy, scenario.supplied, scenario.policy, scenario.lock, scenario.choices, scenario.ordinal());
        }
    }

    private static void verify(final Setting setting, final TransportStrategy strategy, final boolean supplied,
            final ConnectionConflictPolicy policy, final int lock, final int[] choices, final int scenario) {
        // Order: configured typed, configured raw, supplied Session, local raw, local typed.
        final int[] declarations = choices.clone();
        if (lock == 1) { declarations[0] = 1; }
        if (lock == 2) { declarations[1] = 1; }
        final boolean conflict = policy == ConnectionConflictPolicy.REJECT_CONFLICTS
                && ConfigurationContractModel.conflicts(declarations, -1);
        // A local raw setter replaces that channel's captured raw value before its lock restriction is checked.
        final int finalRawChannel = declarations[3] == 0 ? declarations[1] : declarations[3];
        final boolean violatesLock = lock == 1 && (finalRawChannel == 2 || declarations[4] == 2)
                || lock == 2 && (declarations[2] == 2 || declarations[3] == 2 || declarations[4] == 2)
                || supplied && lock == 1 && endpoint(setting) && (setting == Setting.USERNAME || declarations[2] != 1);
        final String expected;
        if (lock != 0) {
            expected = setting.first;
        } else {
            final int[] eligible = declarations.clone();
            if (supplied) { eligible[0] = 0; }
            final int selected = ConfigurationContractModel.selectedIndex(eligible, new int[] {4, 3, 1, 2, 0});
            expected = selected < 0 ? supplied ? null : setting == Setting.PORT ? String.valueOf(strategy.getDefaultServerPort()) : setting.fallback
                    : setting.value(eligible[selected]);
        }

        final String key = (strategy == TransportStrategy.SMTP ? "mail.smtp." : "mail.smtps.") + setting.suffix;
        final Map<String, Object> configuration = new LinkedHashMap<>();
        if (choices[0] != 0) { configuration.put("simplejavamail." + setting.property, setting.value(choices[0])); }
        if (choices[1] != 0) { configuration.put("simplejavamail.extraproperties." + key, setting.value(choices[1])); }
        if (lock == 1) { configuration.put("simplejavamail.locked." + setting.property, setting.first); }
        if (lock == 2) { configuration.put("simplejavamail.locked.extraproperties." + key, setting.first); }
        final Properties baseline = new Properties();
        baseline.setProperty("mail.transport.protocol", strategy == TransportStrategy.SMTP ? "smtp" : "smtps");
        if (choices[2] != 0) { baseline.setProperty(key, setting.value(choices[2])); }
        final Session session = supplied ? Session.getInstance(baseline) : null;
        final var factory = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(configuration).load());
        final MailerGenericBuilder<?> builder = (supplied ? factory.mailerBuilder(session)
                : factory.mailerBuilder().withTransportStrategy(strategy)).withConnectionConflictPolicy(policy).withTransportModeLoggingOnly(true);
        if (choices[3] != 0) { builder.withProperty(key, setting.value(choices[3])); }
        setting.supply(builder, choices[4]);
        final String context = setting + " " + strategy + " supplied=" + supplied + " " + policy + " lock=" + lock + " case=" + scenario;
        String actual = null;
        RuntimeException rejection = null;
        try {
            final MailerGenericBuilderImpl<?> implementation = (MailerGenericBuilderImpl<?>) builder;
            implementation.validateLockedConfiguration();
            actual = implementation.buildSessionSettings(session).copyEffectiveProperties().getProperty(key);
        } catch (RuntimeException failure) {
            rejection = failure;
        }
        if (conflict || violatesLock) {
            assertThat(rejection).as(context).isInstanceOf(IllegalArgumentException.class);
        } else {
            assertThat(rejection).as(context).isNull();
            assertThat(actual).as(context).isEqualTo(expected);
        }
        assertThat(baseline.getProperty(key)).as(context + " preserves input").isEqualTo(setting.value(choices[2]));
    }

    private static boolean endpoint(final Setting setting) {
        return setting == Setting.HOST || setting == Setting.PORT || setting == Setting.USERNAME;
    }
}
