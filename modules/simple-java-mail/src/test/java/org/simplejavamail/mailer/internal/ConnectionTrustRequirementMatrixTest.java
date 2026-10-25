package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionTrustRequirementMatrixTest {
    private static final String A = "first.example.org";
    private static final String B = "second.example.org";

    @Test
    void completeTrustRequirementsAndExplicitChoiceMatrix() {
        // Requirements: none, forbid wildcard, require bounded hosts, literal bounded trust, literal wildcard trust.
        for (int requirement = 0; requirement < 5; requirement++) {
            for (boolean supplied : new boolean[] {false, true}) {
                for (TransportStrategy strategy : new TransportStrategy[] {TransportStrategy.SMTP, TransportStrategy.SMTPS}) {
                    for (ConnectionConflictPolicy policy : ConnectionConflictPolicy.values()) {
                        for (String baseline : new String[] {null, "*", A, B, ""}) {
                            for (Boolean flag : new Boolean[] {null, false, true}) {
                                for (String hosts : new String[] {null, "", A, B}) {
                                    verify(requirement, supplied, strategy, policy, baseline, flag, hosts);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void verify(final int requirement, final boolean supplied, final TransportStrategy strategy,
            final ConnectionConflictPolicy policy, final String baseline, final Boolean flag, final String hosts) {
        final String required = requirement == 2 || requirement == 3 ? A : requirement == 4 ? "*" : null;
        final boolean local = flag != null || hosts != null;
        final String base = required == null ? baseline : required;
        final String requested = Boolean.TRUE.equals(flag) ? "*" : hosts != null ? hosts.isEmpty() ? null : hosts
                : Boolean.FALSE.equals(flag) && "*".equals(base) ? null : base;
        final String expected = requirement == 1 && "*".equals(requested) ? null : requested;
        boolean conflict = baseline != null && (required != null && !required.equals(baseline) || requirement == 1 && "*".equals(baseline));
        if (local && baseline != null) {
            conflict |= hosts == null && Boolean.FALSE.equals(flag) ? "*".equals(baseline)
                    : !Objects.equals(baseline, Boolean.TRUE.equals(flag) ? "*" : hosts == null ? baseline : hosts.isEmpty() ? null : hosts);
        }
        final boolean incompatibleLocal = requirement == 1 && Boolean.TRUE.equals(flag)
                || required != null && local && !Objects.equals(requested, required);
        final boolean incompatibleRaw = baseline != null && (requirement >= 3 && !baseline.equals(required)
                || !supplied && (requirement == 2 && !A.equals(baseline) || requirement == 1 && "*".equals(baseline)));
        final boolean reject = policy == ConnectionConflictPolicy.REJECT_CONFLICTS && conflict || incompatibleLocal || incompatibleRaw;
        final String key = strategy.propertyNameSSLTrust();
        final Map<String, Object> values = new java.util.LinkedHashMap<>();
        if (requirement == 1) { values.put("simplejavamail.locked.defaults.trustallhosts", false); }
        if (requirement == 2) { values.put("simplejavamail.locked.defaults.trustedhosts", A); }
        if (requirement >= 3) { values.put("simplejavamail.locked.extraproperties." + key, required); }
        final var factory = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(values).load());
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", strategy == TransportStrategy.SMTP ? "smtp" : "smtps");
        if (supplied && baseline != null) { properties.setProperty(key, baseline); }
        final Session session = supplied ? Session.getInstance(properties) : null;
        final MailerGenericBuilder<?> builder = (supplied ? factory.mailerBuilder(session)
                : factory.mailerBuilder().withSMTPServerHost("relay.example.org").withTransportStrategy(strategy)).withConnectionConflictPolicy(policy);
        if (!supplied && baseline != null) { builder.withProperty(key, baseline); }
        final TlsConfig.Builder choices = TlsConfig.builder();
        if (flag != null) { choices.trustingAllHosts(flag); }
        if (hosts != null) { choices.trustingSSLHosts(hosts.isEmpty() ? new String[0] : new String[] {hosts}); }
        builder.withTlsConfig(choices.build());
        RuntimeException failure = null;
        String actual = null;
        try {
            final MailerGenericBuilderImpl<?> implementation = (MailerGenericBuilderImpl<?>) builder;
            implementation.validateLockedConfiguration();
            actual = implementation.buildSessionSettings(session).copyEffectiveProperties().getProperty(key);
        } catch (RuntimeException rejected) { failure = rejected; }
        final String context = "requirement=" + requirement + " supplied=" + supplied + " " + strategy + " " + policy
                + " baseline=" + baseline + " flag=" + flag + " hosts=" + hosts;
        if (reject) { assertThat(failure).as(context).isInstanceOf(IllegalArgumentException.class); }
        else {
            assertThat(failure).as(context).isNull();
            assertThat(actual).as(context).isEqualTo(expected);
        }
    }
}
