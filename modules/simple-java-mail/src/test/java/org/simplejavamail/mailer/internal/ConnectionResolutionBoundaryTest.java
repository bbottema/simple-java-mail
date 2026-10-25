package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionResolutionBoundaryTest {
    private static final String RELAY = "relay.example.org";

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void inheritedWildcardBehindAnOpaqueDirectEntryCannotSurviveExplicitDisable(final ConnectionConflictPolicy policy) {
        final Properties defaults = properties("mail.smtp.ssl.trust", "*");
        final Properties supplied = new Properties(defaults);
        supplied.put("mail.smtp.ssl.trust", Boolean.FALSE);
        final Session session = Session.getInstance(supplied);
        assertThatThrownBy(() -> closeUnexpected(factory(Map.of()).mailerBuilder(session)
                .withConnectionConflictPolicy(policy).withTlsConfig(TlsConfig.builder().trustingAllHosts(false).build())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mail.smtp.ssl.trust");
        assertThat(supplied).containsOnlyKeys("mail.smtp.ssl.trust");
        assertThat(supplied.get("mail.smtp.ssl.trust")).isSameAs(Boolean.FALSE);
        assertThat(supplied.getProperty("mail.smtp.ssl.trust")).isEqualTo("*");
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void suppliedProxyPortGapDoesNotImportAnOrdinaryFactoryPort(final ConnectionConflictPolicy policy) throws Exception {
        for (String protocol : new String[] {"smtp", "smtps"}) {
            final String prefix = "mail." + protocol;
            final Properties supplied = properties("mail.transport.protocol", protocol);
            supplied.setProperty(prefix + ".socks.host", "proxy.example.org");
            try (Mailer mailer = factory(Map.of("simplejavamail.proxy.port", 4455)).mailerBuilder(Session.getInstance(supplied))
                    .withConnectionConflictPolicy(policy).buildMailer()) {
                assertThat(supplied.getProperty(prefix + ".socks.port")).isNull();
                assertThat(mailer.getProxyConfig().getRemoteProxyPort()).isEqualTo(1080);
            }
        }
    }

    @Test
    void embeddedRawProxyPortIsResolvedBeforeAddingFallbacks() throws Exception {
        for (TransportStrategy strategy : new TransportStrategy[] {TransportStrategy.SMTP, TransportStrategy.SMTPS}) {
            try (Mailer mailer = factory(Map.of()).mailerBuilder().withSMTPServerHost(RELAY).withTransportStrategy(strategy)
                    .withProperty(strategy.propertyNameSocksHost(), "proxy.example.org:1081").buildMailer()) {
                assertThat(mailer.getProxyConfig().getRemoteProxyHost()).isEqualTo("proxy.example.org");
                assertThat(mailer.getProxyConfig().getRemoteProxyPort()).isEqualTo(1081);
                final String separate = mailer.getSession().getProperty(strategy.propertyNameSocksPort());
                assertThat(separate == null ? 1081 : Integer.parseInt(separate)).isEqualTo(1081);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void anOmittedOwnedTrustAllFlagRetainsItsFactoryChoice(final ConnectionConflictPolicy policy) throws Exception {
        for (TlsConfig choices : new TlsConfig[] {TlsConfig.builder().trustingSSLHosts(RELAY).build(),
                TlsConfig.builder().clearTrustedSSLHosts().build()}) {
            try (Mailer mailer = factory(Map.of("simplejavamail.defaults.trustallhosts", true)).mailerBuilder()
                    .withSMTPServerHost(RELAY).withConnectionConflictPolicy(policy).withTlsConfig(choices).buildMailer()) {
                assertThat(mailer.getOperationalConfig().isTrustAllSSLHost()).isTrue();
                assertThat(mailer.getSession().getProperty("mail.smtp.ssl.trust")).isEqualTo("*");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void disablingTrustAllRetainsATypedBoundedTrustLock(final ConnectionConflictPolicy policy) throws Exception {
        try (Mailer mailer = factory(Map.of("simplejavamail.locked.defaults.trustedhosts", RELAY)).mailerBuilder()
                .withSMTPServerHost(RELAY).withConnectionConflictPolicy(policy)
                .withTlsConfig(TlsConfig.builder().trustingAllHosts(false).build()).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getSslHostsToTrust()).containsExactly(RELAY);
            assertThat(mailer.getOperationalConfig().isTrustAllSSLHost()).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void aFalseTrustAllLockRejectsRawWildcardBeforeItCanBeMasked(final ConnectionConflictPolicy policy) {
        assertThatThrownBy(() -> closeUnexpected(factory(Map.of("simplejavamail.locked.defaults.trustallhosts", false))
                .mailerBuilder().withSMTPServerHost(RELAY).withConnectionConflictPolicy(policy)
                .withProperty("mail.smtp.ssl.trust", "*")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("locked.defaults.trustallhosts");
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void rawSettersUseTheSameLockEquivalenceAsTypedSetters(final ConnectionConflictPolicy policy) throws Exception {
        for (String[] values : new String[][] {{"mail.smtp.timeout", "00123", "123"},
                {"mail.smtp.ssl.checkserveridentity", "TRUE", "true"}}) {
            try (Mailer ignored = factory(Map.of("simplejavamail.locked.extraproperties." + values[0], values[1]))
                    .mailerBuilder().withSMTPServerHost(RELAY).withConnectionConflictPolicy(policy)
                    .withProperty(values[0], values[2]).buildMailer()) {
                // The declarations have the same provider-supported meaning.
            }
        }
    }

    @Test
    void emptyImportedLocalAddressIsNotAbsence() {
        assertThatThrownBy(() -> closeUnexpected(factory(Map.of()).mailerBuilder().withSMTPServerHost(RELAY)
                .withProperty("mail.smtp.localaddress", "")
                .withConnectionConfig(ConnectionConfig.builder().clearLocalBindAddress().build())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mail.smtp.localaddress");
    }

    @Test
    void uninterpretableRawNumbersStayOpaqueWhenNoTypedChoiceReplacesThem() throws Exception {
        final Number opaque = new Number() {
            @Override public int intValue() { return 123; }
            @Override public long longValue() { return 123; }
            @Override public float floatValue() { return 123; }
            @Override public double doubleValue() { return 123; }
            @Override public String toString() { throw new AssertionError("Do not render opaque numbers"); }
        };
        final Properties raw = new Properties();
        raw.put("mail.smtp.timeout", opaque);
        try (Mailer mailer = factory(Map.of()).mailerBuilder().withSMTPServerHost(RELAY).withProperties(raw).buildMailer()) {
            assertThat(mailer.getSession().getProperties().get("mail.smtp.timeout")).isSameAs(opaque);
            assertThat(mailer.getOperationalConfig().getReadTimeout()).isNull();
        }
    }

    @Test
    void strictConflictsAreReportedBeforeServerRepresentationIsConstructed() {
        final Properties raw = new Properties();
        raw.put("mail.smtp.host", new Object());
        assertThatThrownBy(() -> closeUnexpected(factory(Map.of("simplejavamail.smtp.host", RELAY)).mailerBuilder().withProperties(raw)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mail.smtp.host").hasMessageContaining("simplejavamail.smtp.host");
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void factoryInstancesUseIdentityWithoutApplicationEquality(final ConnectionConflictPolicy policy) throws Exception {
        final EqualFactory raw = new EqualFactory();
        final EqualFactory typed = new EqualFactory();
        final Properties properties = new Properties();
        properties.put("mail.smtp.ssl.socketFactory", raw);
        final MailerGenericBuilder<?> builder = factory(Map.of()).mailerBuilder().withSMTPServerHost(RELAY)
                .withConnectionConflictPolicy(policy).withProperties(properties)
                .withTlsConfig(TlsConfig.builder().withCustomSSLFactoryInstance(typed).build());
        if (policy == ConnectionConflictPolicy.REJECT_CONFLICTS) {
            assertThatThrownBy(() -> closeUnexpected(builder)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mail.smtp.ssl.socketFactory");
        } else {
            try (Mailer mailer = builder.buildMailer()) {
                assertThat(mailer.getServerConfig().getCustomSSLFactoryInstance()).isSameAs(typed);
            }
        }
        assertThat(raw.equalityCalls + typed.equalityCalls).isZero();
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void numericProviderFormsAgreeWithTypedValuesAndInspection(final ConnectionConflictPolicy policy) throws Exception {
        try (Mailer mailer = factory(Map.of()).mailerBuilder().withSMTPServerHost(RELAY)
                .withConnectionConflictPolicy(policy).withProperty("mail.smtp.timeout", "0x7b")
                .withConnectionConfig(ConnectionConfig.builder().withReadTimeout(Duration.ofMillis(123)).build()).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getReadTimeout()).isEqualTo(Duration.ofMillis(123));
        }
        try (Mailer mailer = factory(Map.of()).mailerBuilder().withSMTPServerHost(RELAY)
                .withProperty("mail.smtp.timeout", "0x7b").withProperty("mail.smtp.socks.host", "proxy.example.org")
                .withProperty("mail.smtp.socks.port", "0x438").buildMailer()) {
            assertThat(mailer.getOperationalConfig().getReadTimeout()).isEqualTo(Duration.ofMillis(123));
            assertThat(mailer.getProxyConfig().getRemoteProxyPort()).isEqualTo(1080);
        }
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void trustedHostMembershipIgnoresOrderingAndDuplicates(final ConnectionConflictPolicy policy) throws Exception {
        for (String prefix : new String[] {"simplejavamail.defaults.", "simplejavamail.locked.defaults."}) {
            try (Mailer mailer = factory(Map.of(prefix + "trustedhosts", "first.example.org;second.example.org"))
                    .mailerBuilder().withSMTPServerHost(RELAY).withConnectionConflictPolicy(policy)
                    .withTlsConfig(TlsConfig.builder().trustingSSLHosts("second.example.org", "first.example.org", "first.example.org").build())
                    .buildMailer()) {
                assertThat(mailer.getOperationalConfig().getSslHostsToTrust()).contains("first.example.org", "second.example.org");
            }
        }
    }

    private static SimpleJavaMail factory(final Map<String, ?> values) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(values).load());
    }

    private static Properties properties(final String key, final String value) {
        final Properties properties = new Properties();
        properties.setProperty(key, value);
        return properties;
    }

    private static void closeUnexpected(final MailerGenericBuilder<?> builder) throws Exception {
        try (Mailer ignored = builder.buildMailer()) {
            // Release an unexpectedly accepted Mailer before the assertion fails.
        }
    }

    private static final class EqualFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate = (SSLSocketFactory) SSLSocketFactory.getDefault();
        private int equalityCalls;
        @Override public boolean equals(final Object other) { equalityCalls++; return other instanceof EqualFactory; }
        @Override public int hashCode() { return 1; }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket(final Socket socket, final String host, final int port, final boolean close) throws IOException { return delegate.createSocket(socket, host, port, close); }
        @Override public Socket createSocket(final String host, final int port) throws IOException { return delegate.createSocket(host, port); }
        @Override public Socket createSocket(final String host, final int port, final InetAddress local, final int localPort) throws IOException { return delegate.createSocket(host, port, local, localPort); }
        @Override public Socket createSocket(final InetAddress host, final int port) throws IOException { return delegate.createSocket(host, port); }
        @Override public Socket createSocket(final InetAddress host, final int port, final InetAddress local, final int localPort) throws IOException { return delegate.createSocket(host, port, local, localPort); }
    }
}
