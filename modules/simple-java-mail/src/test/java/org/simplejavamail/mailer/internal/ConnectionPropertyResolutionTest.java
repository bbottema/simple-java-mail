package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.eclipse.angus.mail.util.PropUtil;
import org.jetbrains.annotations.Nullable;
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

import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionPropertyResolutionTest {

    private static final String RELAY = "relay.example.org";
    private static final TransportStrategy[] PROTOCOLS = {TransportStrategy.SMTP, TransportStrategy.SMTPS};

    @Test
    void strictComparisonDoesNotParseUnsupportedRawTimeoutObjects() {
        for (boolean supplied : new boolean[] {false, true}) {
            final Properties raw = new Properties();
            raw.put("mail.smtp.timeout", 123L);
            final Properties sessionProperties = new Properties();
            final MailerGenericBuilder<?> builder = builder(supplied ? Session.getInstance(sessionProperties) : null)
                    .withProperties(raw).withConnectionConfig(readTimeout(123));

            assertThatThrownBy(() -> buildAndClose(builder)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mail.smtp.timeout");
            assertThat(sessionProperties).isEmpty();
        }
    }

    @Test
    void explicitTypedTimeoutReplacesUnsupportedRawObjectsInEitherCallOrder() throws Exception {
        for (boolean supplied : new boolean[] {false, true}) {
            for (boolean typedLast : new boolean[] {false, true}) {
                final Properties raw = new Properties();
                raw.put("mail.smtp.timeout", 123L);
                final MailerGenericBuilder<?> builder = builder(supplied ? Session.getInstance(new Properties()) : null)
                        .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
                if (typedLast) {
                    builder.withProperties(raw).withConnectionConfig(readTimeout(123));
                } else {
                    builder.withConnectionConfig(readTimeout(123)).withProperties(raw);
                }

                try (Mailer mailer = builder.buildMailer()) {
                    assertThat(mailer.getOperationalConfig().getReadTimeout()).isEqualTo(Duration.ofMillis(123));
                    assertThat(PropUtil.getIntProperty(mailer.getSession().getProperties(), "mail.smtp.timeout", -1)).isEqualTo(123);
                }
            }
        }
    }

    @Test
    void strictComparisonDoesNotParseOpaqueBooleanLikeObjects() {
        for (boolean supplied : new boolean[] {false, true}) {
            final Properties sessionProperties = new Properties();
            final MailerGenericBuilder<?> builder = builder(supplied ? Session.getInstance(sessionProperties) : null)
                    .withProperties(opaqueIdentityCheck()).withTlsConfig(identityCheck());

            assertThatThrownBy(() -> buildAndClose(builder)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mail.smtp.ssl.checkserveridentity");
            assertThat(sessionProperties).isEmpty();
        }
    }

    @Test
    void explicitIdentityCheckReplacesOpaqueRawObjectsInEitherCallOrder() throws Exception {
        for (boolean supplied : new boolean[] {false, true}) {
            for (boolean typedLast : new boolean[] {false, true}) {
                final MailerGenericBuilder<?> builder = builder(supplied ? Session.getInstance(new Properties()) : null)
                        .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
                if (typedLast) {
                    builder.withProperties(opaqueIdentityCheck()).withTlsConfig(identityCheck());
                } else {
                    builder.withTlsConfig(identityCheck()).withProperties(opaqueIdentityCheck());
                }

                try (Mailer mailer = builder.buildMailer()) {
                    assertThat(mailer.getOperationalConfig().getVerifyingServerIdentity()).isTrue();
                    assertThat(PropUtil.getBooleanProperty(mailer.getSession().getProperties(), "mail.smtp.ssl.checkserveridentity", false)).isTrue();
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void numericRawLocksAcceptEquivalentTypedChoices(final ConnectionConflictPolicy policy) throws Exception {
        for (TransportStrategy strategy : PROTOCOLS) {
            final String timeoutKey = strategy.propertyNameTimeout();
            try (Mailer mailer = factory(Map.of(rawLock(timeoutKey), "00123")).mailerBuilder()
                    .withSMTPServerHost(RELAY).withTransportStrategy(strategy).withConnectionConflictPolicy(policy)
                    .withConnectionConfig(readTimeout(123)).buildMailer()) {
                assertThat(mailer.getSession().getProperty(timeoutKey)).isEqualTo("00123");
                assertThat(mailer.getOperationalConfig().getReadTimeout()).isEqualTo(Duration.ofMillis(123));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void booleanRawLocksAcceptEquivalentTypedChoices(final ConnectionConflictPolicy policy) throws Exception {
        for (TransportStrategy strategy : PROTOCOLS) {
            final String identityKey = strategy.propertyNameCheckServerIdentity();
            try (Mailer mailer = factory(Map.of(rawLock(identityKey), "TRUE")).mailerBuilder()
                    .withSMTPServerHost(RELAY).withTransportStrategy(strategy).withConnectionConflictPolicy(policy)
                    .withTlsConfig(identityCheck()).buildMailer()) {
                assertThat(mailer.getSession().getProperty(identityKey)).isEqualTo("TRUE");
                assertThat(mailer.getOperationalConfig().getVerifyingServerIdentity()).isTrue();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void differentScalarValuesStillViolateRawLocks(final ConnectionConflictPolicy policy) {
        final MailerGenericBuilder<?> timeout = factory(Map.of(rawLock("mail.smtp.timeout"), "124")).mailerBuilder()
                .withSMTPServerHost(RELAY).withConnectionConflictPolicy(policy).withConnectionConfig(readTimeout(123));
        assertThatThrownBy(() -> buildAndClose(timeout)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("locked.extraproperties.mail.smtp.timeout");

        final MailerGenericBuilder<?> identity = factory(Map.of(rawLock("mail.smtp.ssl.checkserveridentity"), "false")).mailerBuilder()
                .withSMTPServerHost(RELAY).withConnectionConflictPolicy(policy).withTlsConfig(identityCheck());
        assertThatThrownBy(() -> buildAndClose(identity)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("locked.extraproperties.mail.smtp.ssl.checkserveridentity");
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void disablingTrustAllPreservesBoundedRawTrustLocks(final ConnectionConflictPolicy policy) throws Exception {
        for (TransportStrategy strategy : PROTOCOLS) {
            try (Mailer mailer = factory(Map.of(rawLock(strategy.propertyNameSSLTrust()), RELAY)).mailerBuilder()
                    .withSMTPServerHost(RELAY).withTransportStrategy(strategy).withConnectionConflictPolicy(policy)
                    .withTlsConfig(TlsConfig.builder().trustingAllHosts(false).build()).buildMailer()) {
                assertThat(mailer.getSession().getProperty(strategy.propertyNameSSLTrust())).isEqualTo(RELAY);
                assertThat(mailer.getOperationalConfig().isTrustAllSSLHost()).isFalse();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void trustAllAndHostChoicesAreCheckedTogetherAgainstRawLocks(final ConnectionConflictPolicy policy) throws Exception {
        for (TransportStrategy strategy : PROTOCOLS) {
            try (Mailer mailer = factory(Map.of(rawLock(strategy.propertyNameSSLTrust()), "*")).mailerBuilder()
                    .withSMTPServerHost(RELAY).withTransportStrategy(strategy).withConnectionConflictPolicy(policy)
                    .withTlsConfig(TlsConfig.builder().trustingAllHosts(true).trustingSSLHosts(RELAY).build()).buildMailer()) {
                assertThat(mailer.getSession().getProperty(strategy.propertyNameSSLTrust())).isEqualTo("*");
                assertThat(mailer.getOperationalConfig().isTrustAllSSLHost()).isTrue();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void disablingLockedWildcardTrustIsRejectedBeforeChangingTheSession(final ConnectionConflictPolicy policy) {
        for (TransportStrategy strategy : PROTOCOLS) {
            final Properties sessionProperties = new Properties();
            sessionProperties.setProperty("mail.transport.protocol", strategy == TransportStrategy.SMTP ? "smtp" : "smtps");
            final Properties before = (Properties) sessionProperties.clone();
            final MailerGenericBuilder<?> builder = factory(Map.of(rawLock(strategy.propertyNameSSLTrust()), "*"))
                    .mailerBuilder(Session.getInstance(sessionProperties)).withConnectionConflictPolicy(policy)
                    .withTlsConfig(TlsConfig.builder().trustingAllHosts(false).build());

            assertThatThrownBy(() -> buildAndClose(builder)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(rawLock(strategy.propertyNameSSLTrust()));
            assertThat(sessionProperties).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void changingOrClearingBoundedTrustStillViolatesRawLocks(final ConnectionConflictPolicy policy) {
        for (TlsConfig choices : new TlsConfig[] {
                TlsConfig.builder().trustingAllHosts(false).trustingSSLHosts("other.example.org").build(),
                TlsConfig.builder().clearTrustedSSLHosts().build()}) {
            final MailerGenericBuilder<?> builder = factory(Map.of(rawLock("mail.smtp.ssl.trust"), RELAY)).mailerBuilder()
                    .withSMTPServerHost(RELAY).withConnectionConflictPolicy(policy).withTlsConfig(choices);
            assertThatThrownBy(() -> buildAndClose(builder)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("locked.extraproperties.mail.smtp.ssl.trust");
        }
    }

    private static ConnectionConfig readTimeout(final int millis) {
        return ConnectionConfig.builder().withReadTimeout(Duration.ofMillis(millis)).build();
    }

    private static TlsConfig identityCheck() {
        return TlsConfig.builder().verifyingServerIdentity(true).build();
    }

    private static Properties opaqueIdentityCheck() {
        final Properties raw = new Properties();
        raw.put("mail.smtp.ssl.checkserveridentity", new Object() {
            @Override
            public String toString() {
                return "true";
            }
        });
        return raw;
    }

    private static MailerGenericBuilder<?> builder(@Nullable final Session session) {
        final SimpleJavaMail factory = factory(Map.of());
        return session == null ? factory.mailerBuilder().withSMTPServerHost(RELAY) : factory.mailerBuilder(session);
    }

    private static SimpleJavaMail factory(final Map<String, ?> settings) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(settings).load());
    }

    private static String rawLock(final String key) {
        return "simplejavamail.locked.extraproperties." + key;
    }

    private static void buildAndClose(final MailerGenericBuilder<?> builder) throws Exception {
        try (Mailer ignored = builder.buildMailer()) {
            // Close an unexpectedly accepted configuration before failing its assertion.
        }
    }
}
