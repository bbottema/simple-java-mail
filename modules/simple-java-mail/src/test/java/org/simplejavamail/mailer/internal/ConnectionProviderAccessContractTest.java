package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.eclipse.angus.mail.util.PropUtil;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.config.ConfigLoader;
import testutil.configcontracts.ConfigurationContractCoverage;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionProviderAccessContractTest {
    private static final int UNSET_INTEGER = -777;

    @Test
    void everyRegisteredAliasHasAnAccessAndGrammarWitnessThroughPreparedConsumer() {
        final Set<String> expected = new LinkedHashSet<>();
        for (String protocol : new String[] {"smtp", "smtps"}) {
            for (String member : ConnectionCoverageCatalogue.MEMBERS.keySet()) { expected.add("mail." + protocol + "." + member); }
        }
        expected.add("mail.transport.protocol");
        expected.add("simplejavamail.oauth2.token");
        expected.add("simplejavamail.oauth2.token.provider");
        assertThat(ConnectionCoverageCatalogue.ACCESS.keySet()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(ConnectionCoverageCatalogue.GRAMMAR.keySet()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(ConnectionCoverageCatalogue.GRAMMAR.get("mail.smtp.socks.host")).isEqualTo(ConnectionCoverageCatalogue.Grammar.SOCKS_ENDPOINT);
        for (Map.Entry<String, ConnectionCoverageCatalogue.Access> alias : ConnectionCoverageCatalogue.ACCESS.entrySet()) {
            final String key = alias.getKey();
            final Properties properties = base(key.startsWith("mail.smtps.") ? "smtps" : "smtp");
            final Object supplied = sample(key, alias.getValue());
            properties.put(key, supplied);
            if (key.equals("simplejavamail.oauth2.token.provider")) {
                properties.setProperty("mail.smtp.auth.mechanisms", "XOAUTH2");
                properties.setProperty("mail.smtp.user", "test-user");
            }
            final MailerSessionSettings prepared = prepare(properties);
            final Properties effective = prepared.copyEffectiveProperties();
            final Object expectedValue = alias.getValue() == ConnectionCoverageCatalogue.Access.PROPERTY_INTEGER ? 123
                    : alias.getValue() == ConnectionCoverageCatalogue.Access.PROPERTY_BOOLEAN ? Boolean.TRUE : supplied;
            assertThat(nativeValue(properties, key, alias.getValue())).as("native %s", key).isEqualTo(expectedValue);
            assertThat(nativeValue(effective, key, alias.getValue())).as("prepared native %s", key).isEqualTo(expectedValue);
            if (alias.getValue() == ConnectionCoverageCatalogue.Access.DIRECT_RESOURCE) {
                assertThat(effective.get(key)).isSameAs(supplied);
            }
            ConfigurationContractCoverage.record("alias", key);
        }
    }

    @Test
    void nativeTextAccessKeepsDirectInheritedOpaqueAndEmptyDistinctions() {
        final String key = "mail.smtp.localhost";
        final Object opaque = new NoRendering();
        final Object[][] cases = {{null, null, null}, {"inherited.example", null, "inherited.example"},
                {"inherited.example", "direct.example", "direct.example"}, {"inherited.example", opaque, "inherited.example"},
                {null, opaque, null}, {"inherited.example", "", ""}};
        for (Object[] sample : cases) {
            final Properties properties = layered(key, sample[0], sample[1]);
            assertThat(Session.getInstance(properties).getProperty(key)).isEqualTo(sample[2]);
            final MailerSessionSettings prepared = prepare(properties);
            assertThat(prepared.getSmtpClientHostname()).isEqualTo(sample[2]);
            assertThat(Session.getInstance(prepared.copyEffectiveProperties()).getProperty(key)).isEqualTo(sample[2]);
            if (sample[1] == opaque) { assertThat(properties.get(key)).isSameAs(opaque); }
        }
        ConfigurationContractCoverage.record("rule", "provider.access.session-text");
    }

    @Test
    void realPropUtilIntegerGrammarAndStorageAccessAgreeWithPreparedTimeoutInspection() {
        final String key = "mail.smtp.timeout";
        final Object opaque = new NoRendering();
        final Object[][] cases = {{null, null, UNSET_INTEGER}, {"123", null, 123}, {"123", "456", 456},
                {"123", opaque, UNSET_INTEGER}, {null, "", UNSET_INTEGER}, {null, Integer.valueOf(123), 123},
                {null, "00123", 123}, {null, "0x7b", 123}, {null, "invalid", UNSET_INTEGER},
                {null, "2147483648", UNSET_INTEGER}, {null, Long.valueOf(123), UNSET_INTEGER}};
        for (Object[] sample : cases) {
            final Properties properties = layered(key, sample[0], sample[1]);
            final int expected = (Integer) sample[2];
            assertThat(PropUtil.getIntProperty(properties, key, UNSET_INTEGER)).isEqualTo(expected);
            final MailerSessionSettings prepared = prepare(properties);
            assertThat(PropUtil.getIntProperty(prepared.copyEffectiveProperties(), key, UNSET_INTEGER)).isEqualTo(expected);
            assertThat(prepared.getReadTimeout()).isEqualTo(expected == UNSET_INTEGER ? null : Duration.ofMillis(expected));
        }
        ConfigurationContractCoverage.record("rule", "provider.access.property-integer");
        ConfigurationContractCoverage.record("rule", "provider.grammar.decimal-hex-integer");
    }

    @Test
    void realPropUtilBooleanDefaultsAndOpaqueStorageArePreserved() {
        final String key = "mail.smtp.auth";
        final Object[][] cases = {{null, null, false, true}, {"true", null, true, true}, {"true", "FALSE", false, false},
                {"true", new NoRendering(), false, true}, {null, "", false, true}, {null, "invalid", false, true},
                {null, "TrUe", true, true}, {null, Boolean.FALSE, false, false}};
        for (Object[] sample : cases) {
            final Properties properties = layered(key, sample[0], sample[1]);
            final Properties prepared = prepare(properties).copyEffectiveProperties();
            for (boolean fallback : new boolean[] {false, true}) {
                final boolean expected = (Boolean) sample[fallback ? 3 : 2];
                assertThat(PropUtil.getBooleanProperty(properties, key, fallback)).isEqualTo(expected);
                assertThat(PropUtil.getBooleanProperty(prepared, key, fallback)).isEqualTo(expected);
            }
        }
        ConfigurationContractCoverage.record("rule", "provider.access.property-boolean");
        ConfigurationContractCoverage.record("rule", "provider.grammar.boolean");
    }

    @Test
    void unknownExtensionsKeepDirectObjectsAndInheritedStringsWithoutRendering() {
        final String key = "mail.smtp.future-provider-setting";
        final Object opaque = new NoRendering();
        final Properties properties = layered(key, "inherited", opaque);
        final Properties effective = prepare(properties).copyEffectiveProperties();
        assertThat(effective.get(key)).isSameAs(opaque);
        assertThat(Session.getInstance(effective).getProperty(key)).isEqualTo("inherited");
        ConfigurationContractCoverage.record("rule", "provider.unknown-pass-through");
    }

    static MailerSessionSettings prepare(final Properties properties) {
        final Session session = Session.getInstance(properties);
        return new MailerFromSessionBuilderImpl(ConfigLoader.builder().load()).usingSession(session).buildSessionSettings(session);
    }

    static Properties base(final String protocol) {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", protocol);
        return properties;
    }

    private static Properties layered(final String key, final Object inherited, final Object direct) {
        final Properties defaults = new Properties();
        if (inherited != null) { defaults.put(key, inherited); }
        final Properties properties = new Properties(defaults);
        properties.setProperty("mail.transport.protocol", "smtp");
        if (direct != null) { properties.put(key, direct); }
        return properties;
    }

    private static Object sample(final String key, final ConnectionCoverageCatalogue.Access access) {
        switch (access) {
            case PROPERTY_INTEGER: return "123";
            case PROPERTY_BOOLEAN: return "true";
            case DIRECT_RESOURCE: return key.endsWith(".provider") ? (OAuth2AccessTokenProvider) () -> "test-token"
                    : new ConnectionProviderSocketWitness.RecordingFactory();
            case SESSION_STRING:
                if (key.equals("mail.transport.protocol")) { return "smtp"; }
                if (key.endsWith(".class")) { return ConnectionProviderSocketWitness.RecordingFactory.class.getName(); }
                if (key.endsWith(".mechanisms")) { return "LOGIN"; }
                return key.endsWith(".host") || key.endsWith(".localaddress") || key.endsWith(".trust") ? "127.0.0.1" : "test-value";
            default: throw new AssertionError(access);
        }
    }

    private static Object nativeValue(final Properties properties, final String key, final ConnectionCoverageCatalogue.Access access) {
        switch (access) {
            case SESSION_STRING: return Session.getInstance(properties).getProperty(key);
            case PROPERTY_INTEGER: return PropUtil.getIntProperty(properties, key, UNSET_INTEGER);
            case PROPERTY_BOOLEAN: return PropUtil.getBooleanProperty(properties, key, false);
            case DIRECT_RESOURCE: return properties.get(key);
            default: throw new AssertionError(access);
        }
    }

    private static final class NoRendering {
        @Override public String toString() { throw new AssertionError("Opaque provider input was rendered"); }
    }
}
