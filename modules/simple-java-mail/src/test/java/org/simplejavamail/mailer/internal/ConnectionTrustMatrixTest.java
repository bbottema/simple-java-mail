package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent trust decision table, including direct/default/opaque interactions rather than pairwise sampling. */
class ConnectionTrustMatrixTest {
    @Test
    void completeRawTrustViewAndLocalChoiceMatrix() {
        final String[] inheritedValues = {null, "*", "first.example.org", ""};
        final Object[] directValues = {null, "*", "first.example.org", "first.example.org second.example.org", "", Boolean.FALSE};
        final Boolean[] flags = {null, false, true};
        final String[][] lists = {null, {}, {"first.example.org"}, {"second.example.org", "first.example.org", "first.example.org"}, {" * "}};
        for (TransportStrategy strategy : new TransportStrategy[] {TransportStrategy.SMTP, TransportStrategy.SMTPS}) {
            for (boolean supplied : new boolean[] {false, true}) {
                for (ConnectionConflictPolicy policy : ConnectionConflictPolicy.values()) {
                    for (String inherited : inheritedValues) {
                        if (!supplied && inherited != null) { continue; }
                        for (Object direct : directValues) {
                            for (Boolean flag : flags) {
                                for (String[] hosts : lists) {
                                    verify(strategy, supplied, policy, inherited, direct, flag, hosts);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void verify(final TransportStrategy strategy, final boolean supplied, final ConnectionConflictPolicy policy,
            final String inherited, final Object direct, final Boolean flag, final String[] hosts) {
        final String visible = direct instanceof String ? (String) direct : inherited;
        final boolean opaque = direct != null && !(direct instanceof String) && inherited == null;
        final boolean local = flag != null || hosts != null;
        final String expected = Boolean.TRUE.equals(flag) ? "*" : hosts != null ? hosts.length == 0 ? null : String.join(" ", hosts)
                : Boolean.FALSE.equals(flag) && "*".equals(visible) ? null : visible;
        final boolean conflict;
        if (!local || visible == null && !opaque) { conflict = false; }
        else if (Boolean.FALSE.equals(flag) && hosts == null) { conflict = "*".equals(visible); }
        else { conflict = opaque || !equivalentTrust(visible, expected); }
        final boolean removes = !Boolean.TRUE.equals(flag) && hosts != null && hosts.length == 0
                || hosts == null && Boolean.FALSE.equals(flag) && "*".equals(visible);
        final boolean rejects = policy == ConnectionConflictPolicy.REJECT_CONFLICTS && conflict || supplied && removes && inherited != null;
        final String key = strategy.propertyNameSSLTrust();
        final Properties defaults = new Properties();
        if (inherited != null) { defaults.setProperty(key, inherited); }
        final Properties raw = new Properties(defaults);
        if (direct != null) { raw.put(key, direct); }
        final Session session = supplied ? Session.getInstance(raw) : null;
        if (supplied) { raw.setProperty("mail.transport.protocol", strategy == TransportStrategy.SMTP ? "smtp" : "smtps"); }
        final var factory = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        final MailerGenericBuilder<?> builder = supplied ? factory.mailerBuilder(session)
                : factory.mailerBuilder().withSMTPServerHost("relay.example.org").withTransportStrategy(strategy).withProperties(raw);
        builder.withConnectionConflictPolicy(policy);
        final TlsConfig.Builder tls = TlsConfig.builder();
        if (flag != null) { tls.trustingAllHosts(flag); }
        if (hosts != null) { tls.trustingSSLHosts(hosts); }
        builder.withTlsConfig(tls.build());
        RuntimeException failure = null;
        String actual = null;
        try {
            actual = ((MailerGenericBuilderImpl<?>) builder).buildSessionSettings(session).copyEffectiveProperties().getProperty(key);
        } catch (RuntimeException rejected) { failure = rejected; }
        final String context = strategy + " supplied=" + supplied + " " + policy + " default=" + inherited
                + " direct=" + direct + " flag=" + flag + " hosts=" + Arrays.toString(hosts);
        if (rejects) { assertThat(failure).as(context).isInstanceOf(IllegalArgumentException.class); }
        else {
            assertThat(failure).as(context).isNull();
            // Equivalent input text may be retained, but the provider-visible meaning must match the reference table.
            assertThat(equivalentTrust(actual, expected)).as(context + " actual=" + actual).isTrue();
        }
        assertThat(raw.getProperty(key)).as(context + " unchanged input").isEqualTo(visible);
    }

    private static boolean equivalentTrust(final String first, final String second) {
        if (first == null || second == null) { return first == second; }
        if ("*".equals(first) || "*".equals(second)) { return first.equals(second); }
        return new HashSet<>(Arrays.asList(first.trim().split("\\s+"))).equals(new HashSet<>(Arrays.asList(second.trim().split("\\s+"))));
    }
}
