package org.simplejavamail.mailer.internal;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TlsIdentityInspectionTest {

    @ParameterizedTest
    @ValueSource(strings = {"smtp", "smtps"})
    void suppliedSessionWithoutIdentityPropertyRemainsUnspecified(final String protocol) throws Exception {
        final Properties properties = sessionProperties(protocol);
        final Session session = Session.getInstance(properties);
        final String key = identityKey(protocol);
        try (Mailer mailer = factory().mailerBuilder(session).buildMailer()) {
            assertThat(session.getProperty(key)).isNull();
            assertThat(identityVerification(mailer)).as("An omitted property remains unspecified").isNull();
            assertThat(session.getProperty(key)).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"smtp", "smtps"})
    void supportedRawValuesKeepTheirMeaningAndRepresentation(final String protocol) throws Exception {
        for (Object value : List.of(Boolean.TRUE, Boolean.FALSE, "true", "false", "TRUE", "FaLsE")) {
            final Properties properties = sessionProperties(protocol);
            properties.put(identityKey(protocol), value);
            try (Mailer mailer = factory().mailerBuilder(Session.getInstance(properties)).buildMailer()) {
                assertThat(identityVerification(mailer)).as("configured value %s", value)
                        .isEqualTo(Boolean.valueOf(value.toString()));
                assertThat(properties.get(identityKey(protocol))).isSameAs(value);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"smtp", "smtps"})
    void inheritedStringValuesStayVisibleWithoutBecomingDirectProperties(final String protocol) throws Exception {
        for (String value : List.of("true", "false")) {
            final Properties defaults = sessionProperties(protocol);
            defaults.setProperty(identityKey(protocol), value);
            final Properties properties = new Properties(defaults);
            try (Mailer mailer = factory().mailerBuilder(Session.getInstance(properties)).buildMailer()) {
                assertThat(identityVerification(mailer)).isEqualTo(Boolean.valueOf(value));
                assertThat(properties).doesNotContainKey(identityKey(protocol));
                assertThat(properties.getProperty(identityKey(protocol))).isEqualTo(value);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"smtp", "smtps"})
    void unrecognizedValuesStayUnspecifiedWithoutBeingRewritten(final String protocol) throws Exception {
        for (Object value : List.of("", "invalid", " true ", 1)) {
            final Properties properties = sessionProperties(protocol);
            properties.put(identityKey(protocol), value);
            try (Mailer mailer = factory().mailerBuilder(Session.getInstance(properties)).buildMailer()) {
                assertThat(identityVerification(mailer)).as("unrecognized value %s", value).isNull();
                assertThat(properties.get(identityKey(protocol))).isSameAs(value);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(TransportStrategy.class)
    void ownedSessionsReportTheirAppliedDefault(final TransportStrategy strategy) throws Exception {
        try (Mailer mailer = factory().mailerBuilder().withSMTPServer("smtp.example.com", strategy.getDefaultServerPort(), "user", "token")
                .withTransportStrategy(strategy).buildMailer()) {
            assertThat(identityVerification(mailer)).isTrue();
            assertThat(mailer.getSession().getProperty(strategy.propertyNameCheckServerIdentity())).isEqualTo("true");
        }
    }

    @Test
    void opaqueProtocolDoesNotBorrowSmtpIdentityProperties() throws Exception {
        final Properties properties = sessionProperties("custom");
        properties.setProperty(identityKey("smtp"), "true");
        properties.setProperty(identityKey("custom"), "false");
        try (Mailer mailer = factory().mailerBuilder(Session.getInstance(properties)).buildMailer()) {
            assertThat(identityVerification(mailer)).isNull();
            assertThat(properties.getProperty(identityKey("custom"))).isEqualTo("false");
        }
    }

    @Test
    void inspectionKeepsItsConstructionSnapshotWithoutCreatingATransport() throws Exception {
        final Session session = Session.getInstance(sessionProperties("smtp"));
        session.setProvider(new Provider(Provider.Type.TRANSPORT, "smtp", InspectionTrapTransport.class.getName(), "test", "1"));
        InspectionTrapTransport.constructions.set(0);
        try (Mailer mailer = factory().mailerBuilder(session).buildMailer()) {
            final Properties copy = mailer.getOperationalConfig().getProperties();
            copy.setProperty(identityKey("smtp"), "false");
            session.getProperties().setProperty(identityKey("smtp"), "true");
            assertThat(identityVerification(mailer)).isNull();
            assertThat(InspectionTrapTransport.constructions).hasValue(0);
        }
    }

    @Test
    void unknownSnapshotDoesNotBecomeAnExplicitRemovalWhenReconstructed() throws Exception {
        try (Mailer mailer = factory().mailerBuilder(Session.getInstance(sessionProperties("smtp"))).buildMailer()) {
            final MailerSessionSettings reconstructed = MailerSessionSettings.fromOperationalConfig(mailer.getOperationalConfig(), TransportStrategy.SMTP);
            assertThat(reconstructed.getVerifyingServerIdentity()).isNull();
            assertThat(reconstructed.getConnectionOverrides()).doesNotContainKey(identityKey("smtp"));
            final Properties baseline = sessionProperties("smtp");
            baseline.setProperty(identityKey("smtp"), "true");
            assertThat(reconstructed.finalProperties(Session.getInstance(baseline), new Properties()).getProperty(identityKey("smtp")))
                    .isEqualTo("true");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void knownSnapshotKeepsItsIdentityChoiceWhenReconstructed(final boolean configured) throws Exception {
        final Properties properties = sessionProperties("smtp");
        properties.setProperty(identityKey("smtp"), Boolean.toString(configured));
        try (Mailer mailer = factory().mailerBuilder(Session.getInstance(properties)).buildMailer()) {
            final MailerSessionSettings reconstructed = MailerSessionSettings.fromOperationalConfig(mailer.getOperationalConfig(), TransportStrategy.SMTP);
            assertThat(reconstructed.getVerifyingServerIdentity()).isEqualTo(configured);
            assertThat(reconstructed.getConnectionOverrides()).containsEntry(identityKey("smtp"), Boolean.toString(configured));
        }
    }

    private static SimpleJavaMail factory() {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    }

    private static Properties sessionProperties(final String protocol) {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", protocol);
        properties.setProperty("mail." + protocol + ".host", "smtp.example.com");
        properties.setProperty("mail." + protocol + ".port", protocol.equals("smtps") ? "465" : "25");
        return properties;
    }

    private static String identityKey(final String protocol) {
        return "mail." + protocol + ".ssl.checkserveridentity";
    }

    private static Boolean identityVerification(final Mailer mailer) {
        return mailer.getOperationalConfig().getVerifyingServerIdentity();
    }

    public static final class InspectionTrapTransport extends Transport {
        private static final AtomicInteger constructions = new AtomicInteger();

        public InspectionTrapTransport(final Session session, final URLName url) {
            super(session, url);
            constructions.incrementAndGet();
            throw new AssertionError("Configuration inspection must not create a Transport");
        }

        @Override
        public void sendMessage(final Message message, final Address[] addresses) {
            throw new AssertionError("Configuration inspection must not send mail");
        }
    }
}
