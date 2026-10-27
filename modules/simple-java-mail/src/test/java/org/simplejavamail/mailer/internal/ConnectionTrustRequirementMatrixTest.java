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

import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionTrustRequirementMatrixTest {
    private static final String A = "first.example.org";
    private static final String B = "second.example.org";

    private enum Requirement {
        NONE, FORBID_WILDCARD, TYPED_BOUNDED, RAW_BOUNDED, RAW_WILDCARD;

        boolean isRaw() { return this == RAW_BOUNDED || this == RAW_WILDCARD; }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("trustRequirementRules")
    void trustRequirementsConstrainCompletedChoices(final String scenario, final Requirement requirement,
            final boolean supplied, final TransportStrategy strategy, final ConnectionConflictPolicy policy,
            final String baseline, final Boolean flag, final String hosts) {
        verify(scenario, requirement, supplied, strategy, policy, baseline, flag, hosts);
    }

    private static Stream<Arguments> trustRequirementRules() {
        final ConnectionConflictPolicy strict = ConnectionConflictPolicy.REJECT_CONFLICTS;
        final ConnectionConflictPolicy override = ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES;
        final TransportStrategy smtp = TransportStrategy.SMTP;
        final TransportStrategy smtps = TransportStrategy.SMTPS;
        return Stream.of(
                Arguments.of("ordinary strict disagreement without a lock", Requirement.NONE, false, smtp, strict, A, null, B),
                Arguments.of("ordinary override disagreement without a lock", Requirement.NONE, true, smtps, override, A, null, B),
                Arguments.of("negative requirement preserves an absent setting", Requirement.FORBID_WILDCARD, false, smtp, strict, null, null, null),
                Arguments.of("negative requirement preserves bounded raw trust", Requirement.FORBID_WILDCARD, false, smtps, strict, A, null, null),
                Arguments.of("negative requirement preserves empty supplied text", Requirement.FORBID_WILDCARD, true, smtp, strict, "", null, null),
                Arguments.of("negative requirement rejects owned raw wildcard in strict mode", Requirement.FORBID_WILDCARD, false, smtp, strict, "*", null, null),
                Arguments.of("override cannot bypass an owned raw wildcard conflict with a lock", Requirement.FORBID_WILDCARD, false, smtps, override, "*", null, null),
                Arguments.of("strict mode protects a supplied wildcard declaration", Requirement.FORBID_WILDCARD, true, smtp, strict, "*", null, null),
                Arguments.of("override can apply the negative requirement to supplied wildcard", Requirement.FORBID_WILDCARD, true, smtps, override, "*", null, null),
                Arguments.of("local wildcard cannot bypass a negative requirement", Requirement.FORBID_WILDCARD, true, smtp, override, null, true, null),
                Arguments.of("bounded local hosts satisfy a negative requirement", Requirement.FORBID_WILDCARD, false, smtp, strict, null, false, A),
                Arguments.of("negative requirement permits deliberate bounded clear", Requirement.FORBID_WILDCARD, true, smtp, override, A, null, ""),
                Arguments.of("typed bounded requirement supplies an owned gap", Requirement.TYPED_BOUNDED, false, smtp, strict, null, null, null),
                Arguments.of("typed bounded requirement supplies a caller gap", Requirement.TYPED_BOUNDED, true, smtps, override, null, null, null),
                Arguments.of("equivalent raw and typed bounded declarations coexist", Requirement.TYPED_BOUNDED, false, smtp, strict, A, null, null),
                Arguments.of("owned raw declaration cannot bypass typed bounded requirement", Requirement.TYPED_BOUNDED, false, smtps, override, B, null, null),
                Arguments.of("strict mode protects supplied bounded declaration", Requirement.TYPED_BOUNDED, true, smtp, strict, B, null, null),
                Arguments.of("override may align supplied trust with typed requirement", Requirement.TYPED_BOUNDED, true, smtps, override, B, null, null),
                Arguments.of("clear cannot remove typed bounded requirement", Requirement.TYPED_BOUNDED, false, smtp, override, null, null, ""),
                Arguments.of("disabling wildcard retains typed bounded requirement", Requirement.TYPED_BOUNDED, false, smtp, strict, null, false, null),
                Arguments.of("wildcard cannot replace typed bounded requirement", Requirement.TYPED_BOUNDED, true, smtp, override, null, true, null),
                Arguments.of("false flag and equal host choice satisfy typed requirement", Requirement.TYPED_BOUNDED, true, smtps, strict, A, false, A),
                Arguments.of("raw bounded requirement supplies an owned gap", Requirement.RAW_BOUNDED, false, smtp, strict, null, null, null),
                Arguments.of("raw bounded requirement accepts equal supplied trust", Requirement.RAW_BOUNDED, true, smtps, strict, A, null, null),
                Arguments.of("raw bounded requirement rejects different supplied trust in override mode", Requirement.RAW_BOUNDED, true, smtp, override, B, null, null),
                Arguments.of("raw bounded requirement rejects different owned raw trust", Requirement.RAW_BOUNDED, false, smtps, override, B, null, null),
                Arguments.of("raw bounded requirement rejects different local hosts", Requirement.RAW_BOUNDED, false, smtp, override, null, null, B),
                Arguments.of("clear cannot remove raw bounded requirement", Requirement.RAW_BOUNDED, true, smtp, override, null, null, ""),
                Arguments.of("disabling wildcard retains raw bounded requirement", Requirement.RAW_BOUNDED, true, smtps, strict, null, false, null),
                Arguments.of("wildcard cannot replace raw bounded requirement", Requirement.RAW_BOUNDED, false, smtp, strict, null, true, null),
                Arguments.of("raw wildcard requirement supplies an owned gap", Requirement.RAW_WILDCARD, false, smtps, strict, null, null, null),
                Arguments.of("raw wildcard requirement accepts equal supplied trust", Requirement.RAW_WILDCARD, true, smtp, strict, "*", null, null),
                Arguments.of("raw wildcard requirement rejects bounded supplied trust", Requirement.RAW_WILDCARD, true, smtps, override, A, null, null),
                Arguments.of("raw wildcard requirement rejects bounded owned raw trust", Requirement.RAW_WILDCARD, false, smtp, override, A, null, null),
                Arguments.of("disabling wildcard cannot bypass raw wildcard requirement", Requirement.RAW_WILDCARD, true, smtp, override, null, false, null),
                Arguments.of("bounded host choice cannot bypass raw wildcard requirement", Requirement.RAW_WILDCARD, false, smtps, strict, null, null, A),
                Arguments.of("completed true flag makes bounded local hosts inactive", Requirement.RAW_WILDCARD, false, smtp, strict, null, true, B));
    }

    private static void verify(final String scenario, final Requirement requirement, final boolean supplied, final TransportStrategy strategy,
            final ConnectionConflictPolicy policy, final String baseline, final Boolean flag, final String hosts) {
        final String required = requirement == Requirement.TYPED_BOUNDED || requirement == Requirement.RAW_BOUNDED ? A : requirement == Requirement.RAW_WILDCARD ? "*" : null;
        final boolean local = flag != null || hosts != null;
        final String base = required == null ? baseline : required;
        final String requested = Boolean.TRUE.equals(flag) ? "*" : hosts != null ? hosts.isEmpty() ? null : hosts
                : Boolean.FALSE.equals(flag) && "*".equals(base) ? null : base;
        final String expected = requirement == Requirement.FORBID_WILDCARD && "*".equals(requested) ? null : requested;
        boolean conflict = baseline != null && (required != null && !required.equals(baseline) || requirement == Requirement.FORBID_WILDCARD && "*".equals(baseline));
        if (local && baseline != null) {
            conflict |= hosts == null && Boolean.FALSE.equals(flag) ? "*".equals(baseline)
                    : !Objects.equals(baseline, Boolean.TRUE.equals(flag) ? "*" : hosts == null ? baseline : hosts.isEmpty() ? null : hosts);
        }
        final boolean incompatibleLocal = requirement == Requirement.FORBID_WILDCARD && Boolean.TRUE.equals(flag)
                || required != null && local && !Objects.equals(requested, required);
        final boolean incompatibleRaw = baseline != null && (requirement.isRaw() && !baseline.equals(required)
                || !supplied && (requirement == Requirement.TYPED_BOUNDED && !A.equals(baseline) || requirement == Requirement.FORBID_WILDCARD && "*".equals(baseline)));
        final boolean reject = policy == ConnectionConflictPolicy.REJECT_CONFLICTS && conflict || incompatibleLocal || incompatibleRaw;
        final String key = strategy.propertyNameSSLTrust();
        final Map<String, Object> values = new java.util.LinkedHashMap<>();
        if (requirement == Requirement.FORBID_WILDCARD) { values.put("simplejavamail.locked.defaults.trustallhosts", false); }
        if (requirement == Requirement.TYPED_BOUNDED) { values.put("simplejavamail.locked.defaults.trustedhosts", A); }
        if (requirement.isRaw()) { values.put("simplejavamail.locked.extraproperties." + key, required); }
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
        final String context = scenario + ": requirement=" + requirement + " supplied=" + supplied + " " + strategy + " " + policy
                + " baseline=" + baseline + " flag=" + flag + " hosts=" + hosts;
        if (reject) { assertThat(failure).as(context).isInstanceOf(IllegalArgumentException.class); }
        else {
            assertThat(failure).as(context).isNull();
            assertThat(actual).as(context).isEqualTo(expected);
        }
    }
}
