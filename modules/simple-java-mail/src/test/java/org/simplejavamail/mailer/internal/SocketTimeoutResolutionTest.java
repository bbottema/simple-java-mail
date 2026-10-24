package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import jakarta.mail.Session;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SocketTimeoutResolutionTest {

    @Test
    void explicitRawPropertiesOverrideOnlyTheirInheritedSocketDefaults() throws Exception {
        try (Mailer mailer = factory(Map.of()).mailerBuilder().withSMTPServer("localhost", 25)
                .withProperty("mail.smtp.connectiontimeout", 10_000)
                .withProperty("mail.smtp.timeout", 30_000).buildMailer()) {
            assertThat(mailer.getSession().getProperties().get("mail.smtp.connectiontimeout").toString()).isEqualTo("10000");
            assertThat(mailer.getSession().getProperties().get("mail.smtp.timeout").toString()).isEqualTo("30000");
            assertThat(mailer.getSession().getProperty("mail.smtp.writetimeout"))
                    .isEqualTo(String.valueOf(MailerGenericBuilder.DEFAULT_SESSION_TIMEOUT.toMillis()));
            assertThat(mailer.getOperationalConfig().getSessionTimeout()).isNull();
        }
    }

    @ParameterizedTest
    @EnumSource(TransportStrategy.class)
    void individualTypedChoicesOverrideOnlyTheirRawFieldForTheSelectedProtocol(final TransportStrategy strategy) throws Exception {
        try (Mailer mailer = factory(Map.of()).mailerBuilder().withSMTPServer("localhost", strategy.getDefaultServerPort(), "test-user", "test-token")
                .withTransportStrategy(strategy)
                .withProperty(strategy.propertyNameConnectionTimeout(), 10_000)
                .withProperty(strategy.propertyNameTimeout(), 30_000)
                .withConnectionConfig(ConnectionConfig.builder().withReadTimeout(Duration.ofSeconds(45)).build()).buildMailer()) {
            assertTimeouts(mailer, strategy, 10_000, 45_000, 60_000);
            assertThat(mailer.getOperationalConfig().getSessionTimeout()).isNull();
        }
    }

    @Test
    void combinedConvenienceAndIndividualSettersShareTheSameThreeChoices() throws Exception {
        final ConnectionConfig choices = ConnectionConfig.builder().withSessionTimeout(Duration.ofSeconds(30))
                .withConnectTimeout(Duration.ofSeconds(10)).withReadTimeout(Duration.ofSeconds(45)).build();
        try (Mailer fineTuned = factory(Map.of()).mailerBuilder().withSMTPServer("localhost", 25)
                .withConnectionConfig(choices).withProperty("mail.smtp.timeout", 99_000).buildMailer()) {
            assertTimeouts(fineTuned, TransportStrategy.SMTP, 10_000, 45_000, 30_000);
            assertThat(fineTuned.getOperationalConfig().getSessionTimeout()).isNull();
        }
        try (Mailer combinedAgain = factory(Map.of()).mailerBuilder().withSMTPServer("localhost", 25)
                .withConnectionConfig(choices.toBuilder().withSessionTimeout(Duration.ofSeconds(20)).build()).buildMailer()) {
            assertTimeouts(combinedAgain, TransportStrategy.SMTP, 20_000, 20_000, 20_000);
            assertThat(combinedAgain.getOperationalConfig().getSessionTimeout()).isEqualTo(Duration.ofSeconds(20));
        }
    }

    @Test
    void individualConfiguredValuesFallBackToTheSharedPropertyAndYieldToRawProperties() throws Exception {
        final SimpleJavaMail mail = factory(Map.of("defaults.sessiontimeoutmillis", "12000",
                "defaults.connecttimeoutmillis", "1000", "defaults.readtimeoutmillis", "2000"));
        try (Mailer configured = mail.mailerBuilder().withSMTPServer("localhost", 25).buildMailer()) {
            assertTimeouts(configured, TransportStrategy.SMTP, 1_000, 2_000, 12_000);
        }
        try (Mailer raw = mail.mailerBuilder().withSMTPServer("localhost", 25)
                .withProperty("mail.smtp.timeout", 3000).buildMailer()) {
            assertTimeouts(raw, TransportStrategy.SMTP, 1_000, 3_000, 12_000);
        }
        try (Mailer typed = mail.mailerBuilder().withSMTPServer("localhost", 25)
                .withProperty("mail.smtp.timeout", 3000)
                .withConnectionConfig(ConnectionConfig.builder().withReadTimeout(Duration.ofSeconds(4)).build()).buildMailer()) {
            assertTimeouts(typed, TransportStrategy.SMTP, 1_000, 4_000, 12_000);
        }
    }

    @Test
    void separateFactoriesRetainTheirCapturedTimeoutsAfterTheSourceMapChanges() throws Exception {
        final Map<String, Object> source = new LinkedHashMap<>();
        source.put("simplejavamail.connection.conflictpolicy", "ALLOW_EXPLICIT_OVERRIDES");
        source.put("simplejavamail.defaults.sessiontimeoutmillis", "5000");
        source.put("simplejavamail.defaults.readtimeoutmillis", "1000");
        source.put("simplejavamail.extraproperties.mail.smtp.connectiontimeout", "2000");
        final SimpleJavaMail first = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(source).load());
        source.put("simplejavamail.defaults.sessiontimeoutmillis", "6000");
        source.put("simplejavamail.defaults.readtimeoutmillis", "9000");
        source.put("simplejavamail.extraproperties.mail.smtp.connectiontimeout", "8000");
        final SimpleJavaMail second = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(source).load());
        source.clear();
        try (Mailer firstMailer = first.mailerBuilder().withSMTPServer("localhost", 25).buildMailer();
                Mailer secondMailer = second.mailerBuilder().withSMTPServer("localhost", 25).buildMailer()) {
            assertTimeouts(firstMailer, TransportStrategy.SMTP, 2_000, 1_000, 5_000);
            assertTimeouts(secondMailer, TransportStrategy.SMTP, 8_000, 9_000, 6_000);
        }
    }

    @Test
    void perFieldResetAndConfiguredCompanionsLeaveOtherChoicesAlone() throws Exception {
        final SimpleJavaMail mail = factory(Map.of("defaults.sessiontimeoutmillis", "12000", "defaults.readtimeoutmillis", "22000"));
        final ConnectionConfig choices = ConnectionConfig.builder().withSessionTimeout(Duration.ofSeconds(30))
                .resetConnectTimeout().useConfiguredReadTimeout().build();
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 25).withConnectionConfig(choices).buildMailer()) {
            assertTimeouts(mailer, TransportStrategy.SMTP, 60_000, 22_000, 30_000);
        }
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 25)
                .withConnectionConfig(choices.toBuilder().useConfiguredSessionTimeout().build()).buildMailer()) {
            assertTimeouts(mailer, TransportStrategy.SMTP, 12_000, 22_000, 12_000);
        }
    }

    @Test
    void replacingTheGroupRemovesEarlierExplicitFieldProvenance() throws Exception {
        final MailerRegularBuilder<?> builder = factory(Map.of("defaults.readtimeoutmillis", "22000")).mailerBuilder()
                .withSMTPServer("localhost", 25)
                .withConnectionConfig(ConnectionConfig.builder().withReadTimeout(Duration.ofSeconds(45)).build())
                .withConnectionConfig(ConnectionConfig.builder().withConnectTimeout(Duration.ofSeconds(10)).build())
                .withProperty("mail.smtp.timeout", 30_000);
        assertThat(builder.getConnectionConfig().getReadTimeout()).isNull();
        try (Mailer mailer = builder.buildMailer()) {
            assertTimeouts(mailer, TransportStrategy.SMTP, 10_000, 30_000, 60_000);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"smtp", "smtps"})
    void suppliedSessionTimeoutsRemainTheBaselineExceptForExplicitIndividualChoices(final String protocol) throws Exception {
        final TransportStrategy strategy = "smtp".equals(protocol) ? TransportStrategy.SMTP : TransportStrategy.SMTPS;
        final SimpleJavaMail mail = factory(Map.of("defaults.sessiontimeoutmillis", "12000",
                "defaults.connecttimeoutmillis", "13000", "defaults.readtimeoutmillis", "14000", "defaults.writetimeoutmillis", "15000"));
        for (final boolean marked : List.of(false, true)) {
            final Session baseline = suppliedSession(strategy, marked);
            try (Mailer mailer = mail.mailerBuilder(baseline).buildMailer()) {
                assertTimeouts(mailer, strategy, 1_000, 2_000, 3_000);
            }
            final Session fineTuned = suppliedSession(strategy, marked);
            try (Mailer mailer = mail.mailerBuilder(fineTuned)
                    .withConnectionConfig(ConnectionConfig.builder().withReadTimeout(Duration.ofSeconds(45)).build()).buildMailer()) {
                assertTimeouts(mailer, strategy, 1_000, 45_000, 3_000);
            }
            final Session inheritedAgain = suppliedSession(strategy, marked);
            try (Mailer mailer = mail.mailerBuilder(inheritedAgain)
                    .withConnectionConfig(ConnectionConfig.builder().withReadTimeout(Duration.ofSeconds(45)).useConfiguredReadTimeout().build()).buildMailer()) {
                assertTimeouts(mailer, strategy, 1_000, 2_000, 3_000);
            }
        }
    }

    @Test
    void effectiveIndividualGettersRetainTheirConstructionSnapshotAndDescribeMissingOrUnusableValues() throws Exception {
        final Session session = suppliedSession(TransportStrategy.SMTP, false);
        session.getProperties().remove("mail.smtp.connectiontimeout");
        session.getProperties().put("mail.smtp.writetimeout", "provider-specific");
        try (Mailer mailer = factory(Map.of()).mailerBuilder(session).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getConnectTimeout()).isNull();
            assertThat(mailer.getOperationalConfig().getReadTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(mailer.getOperationalConfig().getWriteTimeout()).isNull();
            assertThat(mailer.getOperationalConfig().getSessionTimeout()).isNull();
            session.getProperties().put("mail.smtp.timeout", "99000");
            assertThat(mailer.getOperationalConfig().getReadTimeout()).isEqualTo(Duration.ofSeconds(2));
        }
    }

    @Test
    void customProtocolsRejectTypedMappingsBeforeMutationAndDoNotInventEffectiveTimeouts() throws Exception {
        final Session session = suppliedSession(TransportStrategy.SMTP, false);
        session.getProperties().put("mail.transport.protocol", "custom-smtp");
        final Properties original = (Properties) session.getProperties().clone();
        assertThatThrownBy(() -> factory(Map.of()).mailerBuilder(session)
                .withConnectionConfig(ConnectionConfig.builder().withReadTimeout(Duration.ofSeconds(1)).build()).buildMailer())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("withReadTimeout").hasMessageContaining("withProperty");
        assertThat(session.getProperties()).isEqualTo(original);
        try (Mailer mailer = factory(Map.of("defaults.readtimeoutmillis", "55000")).mailerBuilder(session)
                .withTransportModeLoggingOnly(true).withProperty("mail.custom-smtp.timeout", "9000").buildMailer()) {
            assertThat(mailer.getOperationalConfig().getConnectTimeout()).isNull();
            assertThat(mailer.getOperationalConfig().getReadTimeout()).isNull();
            assertThat(mailer.getOperationalConfig().getWriteTimeout()).isNull();
        }
    }

    @Test
    void individualLocksApplyOnlyToTheirOperationAndStillPermitOtherFineTuning() throws Exception {
        final SimpleJavaMail mail = factory(Map.of("locked.defaults.readtimeoutmillis", "5000"));
        try (Mailer owned = mail.mailerBuilder().withSMTPServer("localhost", 25)
                .withConnectionConfig(ConnectionConfig.builder().withConnectTimeout(Duration.ofSeconds(10)).build()).buildMailer();
                Mailer supplied = mail.mailerBuilder(suppliedSession(TransportStrategy.SMTP, false)).buildMailer()) {
            assertTimeouts(owned, TransportStrategy.SMTP, 10_000, 5_000, 60_000);
            assertTimeouts(supplied, TransportStrategy.SMTP, 1_000, 5_000, 3_000);
        }
        assertThatThrownBy(() -> mail.mailerBuilder().withSMTPServer("localhost", 25)
                .withConnectionConfig(ConnectionConfig.builder().withReadTimeout(Duration.ofSeconds(6)).build()).buildMailer())
                .hasMessageContaining("locked.defaults.readtimeoutmillis");
        assertThatThrownBy(() -> mail.mailerBuilder().withSMTPServer("localhost", 25)
                .withProperty("mail.smtp.timeout", "6000").buildMailer()).hasMessageContaining("locked.defaults.readtimeoutmillis");
        assertThatThrownBy(() -> mail.mailerBuilder().withSMTPServer("localhost", 25)
                .withConnectionConfig(ConnectionConfig.builder().resetReadTimeout().build()).buildMailer())
                .hasMessageContaining("locked.defaults.readtimeoutmillis");
    }

    @Test
    void commonLocksStillRequireEveryOperationAndCannotConflictWithIndividualLocks() throws Exception {
        final SimpleJavaMail shared = factory(Map.of("locked.defaults.sessiontimeoutmillis", "15000", "defaults.readtimeoutmillis", "35000"));
        try (Mailer mailer = shared.mailerBuilder().withSMTPServer("localhost", 25).buildMailer()) {
            assertTimeouts(mailer, TransportStrategy.SMTP, 15_000, 15_000, 15_000);
            assertThat(mailer.getOperationalConfig().getSessionTimeout()).isEqualTo(Duration.ofSeconds(15));
        }
        assertThatThrownBy(() -> shared.mailerBuilder().withSMTPServer("localhost", 25)
                .withConnectionConfig(ConnectionConfig.builder().withConnectTimeout(Duration.ofSeconds(10)).build()).buildMailer())
                .hasMessageContaining("locked.defaults.sessiontimeoutmillis");
        final Session session = suppliedSession(TransportStrategy.SMTP, false);
        final Properties original = (Properties) session.getProperties().clone();
        assertThatThrownBy(() -> factory(Map.of("locked.defaults.sessiontimeoutmillis", "1000", "locked.defaults.readtimeoutmillis", "2000"))
                .mailerBuilder(session).buildMailer()).hasMessageContaining("locked.defaults.readtimeoutmillis").hasMessageContaining("same SMTP setting");
        assertThat(session.getProperties()).isEqualTo(original);
        try (Mailer compatible = factory(Map.of("locked.defaults.sessiontimeoutmillis", "1000", "locked.defaults.readtimeoutmillis", "1000"))
                .mailerBuilder(session).buildMailer()) {
            assertTimeouts(compatible, TransportStrategy.SMTP, 1_000, 1_000, 1_000);
        }
    }

    @Test
    void individualTypedChoicesRespectLockedRawPropertiesWithoutConstrainingUnrelatedOperations() throws Exception {
        final SimpleJavaMail mail = factory(Map.of("locked.extraproperties.mail.smtp.timeout", "5000"));
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 25)
                .withConnectionConfig(ConnectionConfig.builder().withConnectTimeout(Duration.ofSeconds(10))
                        .withReadTimeout(Duration.ofSeconds(5)).build()).buildMailer()) {
            assertTimeouts(mailer, TransportStrategy.SMTP, 10_000, 5_000, 60_000);
        }
        assertThatThrownBy(() -> mail.mailerBuilder().withSMTPServer("localhost", 25)
                .withConnectionConfig(ConnectionConfig.builder().withReadTimeout(Duration.ofSeconds(6)).build()).buildMailer())
                .hasMessageContaining("locked.extraproperties.mail.smtp.timeout");
        assertThatThrownBy(() -> factory(Map.of("locked.defaults.readtimeoutmillis", "5000", "locked.extraproperties.mail.smtp.timeout", "6000"))
                .mailerBuilder().withSMTPServer("localhost", 25).buildMailer()).hasMessageContaining("locked").hasMessageContaining("values differ");
    }

    @Test
    void individualTimeoutLocksRejectCustomProtocolsAndCustomTransports() {
        final SimpleJavaMail mail = factory(Map.of("locked.defaults.readtimeoutmillis", "5000"));
        final Session session = suppliedSession(TransportStrategy.SMTP, false);
        session.getProperties().put("mail.transport.protocol", "custom-smtp");
        assertThatThrownBy(() -> mail.mailerBuilder(session).withTransportModeLoggingOnly(true).buildMailer())
                .hasMessageContaining("locked.defaults.readtimeoutmillis").hasMessageContaining("neither smtp nor smtps");
        assertThatThrownBy(() -> mail.mailerBuilder().withSMTPServer("localhost", 25)
                .withCustomMailer(mock(CustomMailer.class)).buildMailer())
                .hasMessageContaining("locked.defaults.readtimeoutmillis").hasMessageContaining("withCustomMailer");
    }

    private static void assertTimeouts(final Mailer mailer, final TransportStrategy strategy, final int connect, final int read, final int write) {
        assertThat(mailer.getSession().getProperties().get(strategy.propertyNameConnectionTimeout()).toString()).isEqualTo(String.valueOf(connect));
        assertThat(mailer.getSession().getProperties().get(strategy.propertyNameTimeout()).toString()).isEqualTo(String.valueOf(read));
        assertThat(mailer.getSession().getProperties().get(strategy.propertyNameWriteTimeout()).toString()).isEqualTo(String.valueOf(write));
        assertThat(mailer.getOperationalConfig().getConnectTimeout()).isEqualTo(Duration.ofMillis(connect));
        assertThat(mailer.getOperationalConfig().getReadTimeout()).isEqualTo(Duration.ofMillis(read));
        assertThat(mailer.getOperationalConfig().getWriteTimeout()).isEqualTo(Duration.ofMillis(write));
    }

    private static Session suppliedSession(final TransportStrategy strategy, final boolean marked) {
        final Properties properties = new Properties();
        properties.put("mail.transport.protocol", strategy == TransportStrategy.SMTPS ? "smtps" : "smtp");
        properties.put(strategy.propertyNameHost(), "localhost");
        properties.put(strategy.propertyNameConnectionTimeout(), "1000");
        properties.put(strategy.propertyNameTimeout(), "2000");
        properties.put(strategy.propertyNameWriteTimeout(), "3000");
        if (marked) {
            properties.put("simplejavamail.transportstrategy", strategy.name());
        }
        return Session.getInstance(properties);
    }

    private static SimpleJavaMail factory(final Map<String, String> settings) {
        final Properties properties = new Properties();
        // These controls exercise deliberate per-operation overrides of imported settings.
        properties.setProperty("simplejavamail.connection.conflictpolicy", "ALLOW_EXPLICIT_OVERRIDES");
        settings.forEach((key, value) -> properties.put("simplejavamail." + key, value));
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withProperties(properties).load());
    }
}
