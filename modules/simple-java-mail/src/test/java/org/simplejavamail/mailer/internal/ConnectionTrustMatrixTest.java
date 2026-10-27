package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Named trust-category proofs over an independent model, including direct/default/opaque interactions. */
class ConnectionTrustMatrixTest {
    private static final String A = "first.example.org";
    private static final String B = "second.example.org";

    @ParameterizedTest(name = "{0}")
    @MethodSource("trustRules")
    void rawTrustAndCompletedLocalChoiceFollowTheirCategoryRule(final String scenario, final TransportStrategy strategy,
            final boolean supplied, final ConnectionConflictPolicy policy, final String inherited, final Object direct,
            final Boolean flag, final String[] hosts) {
        ConfigurationAdoptionCatalogue.assertValueBuilderCategory(ConfigurationAdoptionCatalogue.Owner.MAILER_CONNECTION,
                TlsConfig.Builder.class);
        verify(scenario, strategy, supplied, policy, inherited, direct, flag, hosts);
    }

    private static Stream<Arguments> trustRules() {
        final ConnectionConflictPolicy strict = ConnectionConflictPolicy.REJECT_CONFLICTS;
        final ConnectionConflictPolicy override = ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES;
        final TransportStrategy smtp = TransportStrategy.SMTP;
        final TransportStrategy smtps = TransportStrategy.SMTPS;
        final String[] first = {A};
        final String[] second = {B};
        final String[] empty = {};
        return Stream.of(
                Arguments.of("owned Session without trust choices", smtp, false, strict, null, null, null, null),
                Arguments.of("supplied SMTPS Session preserves a trust gap", smtps, true, override, null, null, null, null),
                Arguments.of("direct wildcard remains when untouched", smtp, false, strict, null, "*", null, null),
                Arguments.of("direct bounded list remains when untouched", smtps, true, strict, null, A, null, null),
                Arguments.of("empty trust text remains present", smtp, true, strict, null, "", null, null),
                Arguments.of("unsupported direct value remains provider-owned", smtp, false, strict, null, Boolean.FALSE, null, null),
                Arguments.of("inherited wildcard remains when untouched", smtps, true, strict, "*", null, null, null),
                Arguments.of("inherited bounded trust survives disabling wildcard", smtp, true, strict, A, null, false, null),
                Arguments.of("inherited empty text remains present", smtp, true, override, "", null, null, null),
                Arguments.of("direct text masks inherited wildcard", smtp, true, strict, "*", A, false, null),
                Arguments.of("strict disable observes wildcard behind opaque entry", smtp, true, strict, "*", Boolean.FALSE, false, null),
                Arguments.of("override cannot clear an inherited wildcard", smtps, true, override, "*", Boolean.FALSE, false, null),
                Arguments.of("equivalent typed hosts observe String defaults behind opaque entry", smtp, true, strict, A, Boolean.FALSE, null, first),
                Arguments.of("opaque direct declaration conflicts with typed bounded hosts", smtp, false, strict, null, Boolean.FALSE, null, first),
                Arguments.of("explicit override replaces an opaque direct declaration", smtps, true, override, null, Boolean.FALSE, null, first),
                Arguments.of("conditional wildcard disable does not reinterpret opaque value", smtp, false, strict, null, Boolean.FALSE, false, null),
                Arguments.of("strict mode rejects removing direct wildcard", smtp, false, strict, null, "*", false, null),
                Arguments.of("owned override removes direct wildcard", smtps, false, override, null, "*", false, null),
                Arguments.of("supplied override removes direct wildcard", smtp, true, override, null, "*", false, null),
                Arguments.of("strict mode rejects broadening bounded trust", smtp, false, strict, null, A, true, null),
                Arguments.of("override deliberately broadens bounded trust", smtps, true, override, null, A, true, null),
                Arguments.of("membership ignores ordering and duplicate hosts", smtp, false, strict, null, A + " " + B, null, new String[] {B, A, A}),
                Arguments.of("host names retain exact case", smtp, true, strict, null, A, null, new String[] {"FIRST.example.org"}),
                Arguments.of("exact wildcard differs from bounded star token", smtps, false, strict, null, "*", false, new String[] {" * "}),
                Arguments.of("bounded star text retains its own membership", smtp, false, strict, null, " * ", null, new String[] {" * "}),
                Arguments.of("strict mode rejects clearing imported bounded trust", smtp, true, strict, null, A, null, empty),
                Arguments.of("owned override clears direct bounded trust", smtps, false, override, null, A, null, empty),
                Arguments.of("clear cannot reactivate inherited bounded trust", smtp, true, override, A, null, null, empty),
                Arguments.of("typed bounded hosts can supply an absent setting", smtp, false, strict, null, null, null, first),
                Arguments.of("typed wildcard can supply an absent setting", smtps, true, strict, null, null, true, null),
                Arguments.of("explicit false with equivalent bounded hosts is compatible", smtp, true, strict, null, A, false, first),
                Arguments.of("completed true flag makes host list inactive", smtp, false, strict, null, "*", true, second),
                Arguments.of("empty text differs from explicit removal", smtp, true, strict, null, "", null, empty),
                Arguments.of("override may remove empty direct text", smtps, true, override, null, "", null, empty),
                Arguments.of("override replaces a different bounded list", smtp, true, override, null, A, null, second));
    }

    private static void verify(final String scenario, final TransportStrategy strategy, final boolean supplied, final ConnectionConflictPolicy policy,
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
        final String context = scenario + ": " + strategy + " supplied=" + supplied + " " + policy + " default=" + inherited
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
