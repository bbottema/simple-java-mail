package org.simplejavamail.mailer.internal;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.config.ConfigLoader;
import testutil.configcontracts.ConfigurationContractCoverage;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderSelectorContractTest {
    @Test
    void inheritedSelectorTextRetainsNativeCaseSensitiveProviderSelectionAndOpaqueStorage() throws Exception {
        for (String protocol : new String[] {"smtp", "smtps"}) {
            final String key = "mail." + protocol + ".class";
            final Properties defaults = new Properties();
            defaults.setProperty(key, FirstTransport.class.getName());
            final Properties properties = properties(protocol, defaults);
            final Object opaque = new NoApplicationMethods();
            properties.put(key, opaque);
            final Session session = registeredSession(protocol, properties);
            assertThat(session.getProvider(protocol).getClassName()).isEqualTo(FirstTransport.class.getName());
            CONSTRUCTIONS.set(0);
            try (Mailer mailer = factory(Map.of()).mailerBuilder(session).withProperty(key, FirstTransport.class.getName()).buildMailer()) {
                assertThat(CONSTRUCTIONS).hasValue(0);
                assertThat(mailer.getSession()).isSameAs(session);
                try (Transport actual = session.getTransport()) {
                    assertThat(actual).isInstanceOf(FirstTransport.class);
                }
                assertThat(CONSTRUCTIONS).hasValue(1);
            }
            final Properties unchanged = properties(protocol, defaults);
            unchanged.put(key, opaque);
            try (Mailer mailer = factory(Map.of()).mailerBuilder(registeredSession(protocol, unchanged)).buildMailer()) {
                assertThat(mailer.getSession().getProperties().get(key)).isSameAs(opaque);
                assertThat(mailer.getSession().getProperty(key)).isEqualTo(FirstTransport.class.getName());
            }
            final Session differentCase = registeredSession(protocol, properties(protocol, defaults));
            assertThatThrownBy(() -> {
                try (Mailer ignored = factory(Map.of()).mailerBuilder(differentCase)
                        .withProperty(key, FirstTransport.class.getName().toUpperCase(java.util.Locale.ROOT)).buildMailer()) {
                }
            }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key);
        }
        ConfigurationContractCoverage.record("rule", "provider.grammar.provider-selection");
    }

    @Test
    void providerLocksBindBothPoliciesAndConfiguredDeclarationsParticipateInConflicts() throws Exception {
        for (String protocol : new String[] {"smtp", "smtps"}) {
            final String key = "mail." + protocol + ".class";
            for (ConnectionConflictPolicy policy : ConnectionConflictPolicy.values()) {
                final Session session = registeredSession(protocol, properties(protocol, new Properties()));
                final SimpleJavaMail locked = factory(Map.of("simplejavamail.locked.extraproperties." + key, FirstTransport.class.getName()));
                assertThatThrownBy(() -> {
                    try (Mailer ignored = locked.mailerBuilder(session).withConnectionConflictPolicy(policy)
                            .withProperty(key, SecondTransport.class.getName()).buildMailer()) {
                    }
                }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("locked.extraproperties." + key);
            }
            final Session session = registeredSession(protocol, properties(protocol, new Properties()));
            session.setProvider(new Provider(Provider.Type.TRANSPORT, protocol, FirstTransport.class.getName(), "fixture", "1"));
            assertThatThrownBy(() -> {
                try (Mailer ignored = factory(Map.of("simplejavamail.extraproperties." + key, SecondTransport.class.getName()))
                        .mailerBuilder(session).buildMailer()) {
                }
            }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key);
        }
    }

    @Test
    void inactiveSelectorsAndImplicitDefaultsDoNotCreateConflictingDeclarations() throws Exception {
        final Properties properties = properties("smtp", new Properties());
        properties.setProperty("mail.smtps.class", FirstTransport.class.getName());
        final Session supplied = registeredSession("smtp", properties);
        try (Mailer mailer = factory(Map.of()).mailerBuilder(supplied)
                .withProperty("mail.smtps.class", SecondTransport.class.getName())
                .withProperty("mail.smtp.class", SecondTransport.class.getName()).buildMailer()) {
            assertThat(mailer.getSession()).isSameAs(supplied);
            try (Transport actual = supplied.getTransport()) {
                assertThat(actual).isInstanceOf(SecondTransport.class);
            }
        }
        final Session implicit = registeredSession("smtp", properties("smtp", new Properties()));
        try (Mailer ignored = factory(Map.of()).mailerBuilder(implicit).buildMailer()) {
            assertThat(implicit.getProperties().containsKey("mail.smtp.class")).isFalse();
        }
        final Properties opaque = properties("smtp", new Properties());
        final Object entry = new NoApplicationMethods();
        opaque.put("mail.smtp.class", entry);
        final Session ignoredSelector = registeredSession("smtp", opaque);
        try (Mailer mailer = factory(Map.of()).mailerBuilder(ignoredSelector).buildMailer()) {
            assertThat(mailer.getSession().getProperties().get("mail.smtp.class")).isSameAs(entry);
            assertThat(mailer.getSession().getProperty("mail.smtp.class")).isNull();
        }
        try (Mailer mailer = factory(Map.of()).mailerBuilder(ignoredSelector)
                .withProperty("mail.smtp.class", SecondTransport.class.getName()).buildMailer()) {
            try (Transport actual = mailer.getSession().getTransport()) {
                assertThat(actual).isInstanceOf(SecondTransport.class);
            }
        }
    }

    private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    @Test
    void aMissingProtocolProbeCreatesNoTemporaryTransportForEitherNamespace() throws Exception {
        final Session session = Session.getInstance(new Properties());
        for (String protocol : new String[] {"smtp", "smtps"}) {
            session.setProvider(new Provider(Provider.Type.TRANSPORT, protocol, FirstTransport.class.getName(), "fixture", "1"));
        }
        session.setProtocolForAddress("rfc822", "smtps");
        CONSTRUCTIONS.set(0);
        try (Mailer mailer = factory(Map.of()).mailerBuilder(session).buildMailer()) {
            assertThat(mailer.sync().probeConnection().getFailureDescription()).contains("mail.transport.protocol");
            assertThat(CONSTRUCTIONS).hasValue(0);
            assertThat(session.getProperty("mail.transport.protocol")).isNull();
        }
    }

    @Test
    void opaqueJavaSelectorOverlaysCompareTheirEffectiveTextOrRemovalAndRetainStorageIdentity() throws Exception {
        for (String protocol : new String[] {"smtp", "smtps"}) {
            final String key = "mail." + protocol + ".class";
            for (String inheritedClass : new String[] {null, FirstTransport.class.getName(), SecondTransport.class.getName()}) {
                final Properties defaults = new Properties();
                if (inheritedClass != null) { defaults.setProperty(key, inheritedClass); }
                final Properties properties = properties(protocol, defaults);
                final Session session = registeredSession(protocol, properties);
                // Establish the native implicit fallback independently before adding a visible explicit selector.
                final Provider implicit = session.getProvider(protocol);
                properties.setProperty(key, FirstTransport.class.getName());
                final Object entry = new NoApplicationMethods();
                final Properties overlay = new Properties();
                overlay.put(key, entry);
                if (!FirstTransport.class.getName().equals(inheritedClass)) {
                    assertThatThrownBy(() -> {
                        try (Mailer ignored = factory(Map.of()).mailerBuilder(session).withProperties(overlay).buildMailer()) { }
                    }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key);
                    assertThat(properties.getProperty(key)).isEqualTo(FirstTransport.class.getName());
                }
                try (Mailer mailer = factory(Map.of()).mailerBuilder(session).withProperties(overlay)
                        .withConnectionConflictPolicy(FirstTransport.class.getName().equals(inheritedClass)
                                ? ConnectionConflictPolicy.REJECT_CONFLICTS : ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES).buildMailer()) {
                    assertThat(mailer.getSession().getProperties().get(key)).isSameAs(entry);
                    assertThat(session.getProperty(key)).isEqualTo(inheritedClass);
                    assertThat(session.getProvider(protocol).getClassName()).isEqualTo(inheritedClass == null ? implicit.getClassName() : inheritedClass);
                }
            }
        }
    }

    private static Session registeredSession(final String protocol, final Properties properties) {
        final Session session = Session.getInstance(properties);
        session.addProvider(new Provider(Provider.Type.TRANSPORT, protocol, FirstTransport.class.getName(), "fixture", "1"));
        session.addProvider(new Provider(Provider.Type.TRANSPORT, protocol, SecondTransport.class.getName(), "fixture", "1"));
        return session;
    }

    private static Properties properties(final String protocol, final Properties defaults) {
        final Properties properties = new Properties(defaults);
        properties.setProperty("mail.transport.protocol", protocol);
        return properties;
    }

    private static SimpleJavaMail factory(final Map<String, ?> values) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(values).load());
    }

    private static final class NoApplicationMethods {
        @Override public String toString() { throw new AssertionError("Opaque selector rendered"); }
        @Override public boolean equals(final Object other) { throw new AssertionError("Opaque selector compared"); }
        @Override public int hashCode() { throw new AssertionError("Opaque selector hashed"); }
    }

    public static class FirstTransport extends Transport {
        public FirstTransport(final Session session, final URLName url) { super(session, url); CONSTRUCTIONS.incrementAndGet(); }
        @Override public void sendMessage(final Message message, final Address[] addresses) { }
    }

    public static final class SecondTransport extends FirstTransport {
        public SecondTransport(final Session session, final URLName url) { super(session, url); }
    }
}
