package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailerBuilderCompanionTest {

    @Test
    void groupedSmtpSettersRetainTheirReleasedReplacementSemantics() {
        final SimpleJavaMail factory = configuredFactory();
        final MailerRegularBuilder<?> twoArguments = factory.mailerBuilder().withSMTPServer("another.example.org", null);
        assertThat(twoArguments.getPort()).isNull();
        assertThat(twoArguments.getUsername()).isEqualTo("configured-user");
        assertThat(twoArguments.getPassword()).isEqualTo("configured-secret");

        final MailerRegularBuilder<?> threeArguments = factory.mailerBuilder().withSMTPServer("another.example.org", 587, null);
        assertThat(threeArguments.getUsername()).isNull();
        assertThat(threeArguments.getPassword()).isEqualTo("configured-secret");

        final MailerRegularBuilder<?> fourArguments = factory.mailerBuilder().withSMTPServer("another.example.org", null, "", "");
        assertThat(fourArguments.getPort()).isNull();
        assertThat(fourArguments.getUsername()).isNull();
        assertThat(fourArguments.getPassword()).isNull();
        final MailerRegularBuilder<?> explicitNulls = factory.mailerBuilder().withSMTPServer("another.example.org", null, null, null);
        assertThat(explicitNulls.getHost()).isEqualTo("another.example.org");
        assertThat(explicitNulls.getPort()).isNull();
        assertThat(explicitNulls.getUsername()).isNull();
        assertThat(explicitNulls.getPassword()).isNull();
        assertThat(factory.mailerBuilder().withSMTPServerUsername("").getUsername()).isEmpty();
    }

    @Test
    void smtpClearingDoesNotRestorePropertiesOrChangeAlreadyBuiltMailers() throws Exception {
        final MailerRegularBuilder<?> builder = configuredFactory().mailerBuilder();
        try (Mailer original = builder.buildMailer()) {
            assertThat(builder.clearSMTPServerPort()).isSameAs(builder);
            assertThat(builder.clearSMTPServerUsername()).isSameAs(builder);
            assertThat(builder.clearSMTPServerPassword()).isSameAs(builder);
            try (Mailer cleared = builder.buildMailer()) {
                assertThat(cleared.getServerConfig().getPort()).isEqualTo(25);
                assertThat(cleared.getServerConfig().getUsername()).isNull();
                assertThat(cleared.getServerConfig().getPassword()).isNull();
            }
            assertThat(original.getServerConfig().getPort()).isEqualTo(2525);
            assertThat(original.getServerConfig().getUsername()).isEqualTo("configured-user");
            assertThat(original.getServerConfig().getPassword()).isEqualTo("configured-secret");
        }
        assertThat(builder.clearSMTPServerHost()).isSameAs(builder);
        assertThat(builder.getHost()).isNull();
        assertThatThrownBy(builder::buildMailer).hasMessageContaining("SMTP server host missing");
    }

    @Test
    void runtimeRegistrationClearsDoNotCloseApplicationObjectsOrEraseSmtpConfiguration() throws Exception {
        final CountingCustomMailer custom = new CountingCustomMailer();
        final CountingTokenProvider tokens = new CountingTokenProvider();
        final MailerRegularBuilder<?> builder = configuredFactory().mailerBuilder().withTransportStrategy(TransportStrategy.SMTP_OAUTH2)
                .withCustomMailer(custom).withOAuth2AccessTokenProvider(tokens);
        try (Mailer original = builder.buildMailer()) {
            assertThat(builder.clearCustomMailer()).isSameAs(builder);
            assertThat(builder.clearOAuth2AccessTokenProvider()).isSameAs(builder);
            builder.clearCustomMailer().clearOAuth2AccessTokenProvider();
            assertThat(builder.getCustomMailer()).isNull();
            assertThat(builder.getOAuth2AccessTokenProvider()).isNull();
            assertThat(builder.getUsername()).isEqualTo("configured-user");
            assertThat(builder.getPassword()).isEqualTo("configured-secret");
            assertThat(builder.getTransportStrategy()).isEqualTo(TransportStrategy.SMTP_OAUTH2);
            try (Mailer cleared = builder.buildMailer()) {
                assertThat(cleared.getServerConfig().getHost()).isEqualTo("relay.example.org");
                assertThat(cleared.getOperationalConfig().getCustomMailer()).isNull();
                assertThat(cleared.getOperationalConfig().getOAuth2AccessTokenProvider()).isNull();
            }
            assertThat(original.getOperationalConfig().getCustomMailer()).isSameAs(custom);
            assertThat(original.getOperationalConfig().getOAuth2AccessTokenProvider()).isSameAs(tokens);
        }
        assertThat(custom.closed).isZero();
        assertThat(tokens.closed).isZero();
        assertThat(tokens.requested).isZero();
    }

    @Test
    void booleanResetsUseLibraryDefaultsRatherThanCapturedProperties() throws Exception {
        final SimpleJavaMail factory = factory(Map.of("smtp.host", "relay.example.org", "opportunistic.tls", "false", "smtp.legacycontentsupport", "true"));
        final MailerRegularBuilder<?> builder = factory.mailerBuilder();
        assertThat(builder.isOpportunisticTLS()).isFalse();
        assertThat(builder.isLegacySmtpContentSupportEnabled()).isTrue();
        assertThat(builder.resetOpportunisticTLS()).isSameAs(builder);
        assertThat(builder.resetLegacySmtpContentSupport()).isSameAs(builder);
        assertThat(builder.isOpportunisticTLS()).isEqualTo(MailerGenericBuilder.DEFAULT_OPPORTUNISTIC_TLS);
        assertThat(builder.isLegacySmtpContentSupportEnabled()).isEqualTo(MailerGenericBuilder.DEFAULT_LEGACY_SMTP_CONTENT_SUPPORT);
        try (Mailer reset = builder.buildMailer()) {
            assertThat(reset.getSession().getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
            assertThat(reset.getOperationalConfig().isLegacySmtpContentSupportEnabled()).isFalse();
        }
        assertThat(factory.mailerBuilder().isOpportunisticTLS()).isFalse();
        assertThat(factory.mailerBuilder().isLegacySmtpContentSupportEnabled()).isTrue();
    }

    @Test
    void clearingAndResettingStillRespectLocks() throws Exception {
        assertThatThrownBy(() -> factory(Map.of("locked.smtp.host", "relay.example.org")).mailerBuilder().clearSMTPServerHost().buildMailer())
                .hasMessageContaining("locked.smtp.host");
        assertThatThrownBy(() -> factory(Map.of("locked.smtp.port", "2525")).mailerBuilder().clearSMTPServerPort().buildMailer())
                .hasMessageContaining("locked.smtp.port");
        assertThatThrownBy(() -> factory(Map.of("locked.smtp.username", "user")).mailerBuilder().clearSMTPServerUsername().buildMailer())
                .hasMessageContaining("locked.smtp.username");
        assertThatThrownBy(() -> factory(Map.of("locked.smtp.password", "secret")).mailerBuilder().clearSMTPServerPassword().buildMailer())
                .hasMessageContaining("locked.smtp.password");
        assertThatThrownBy(() -> factory(Map.of("locked.opportunistic.tls", "false")).mailerBuilder().resetOpportunisticTLS().buildMailer())
                .hasMessageContaining("locked.opportunistic.tls");
        assertThatThrownBy(() -> factory(Map.of("locked.smtp.legacycontentsupport", "true")).mailerBuilder()
                .resetLegacySmtpContentSupport().buildMailer()).hasMessageContaining("locked.smtp.legacycontentsupport");

        final SimpleJavaMail locked = factory(Map.of("locked.smtp.host", "relay.example.org", "locked.opportunistic.tls", "true",
                "locked.smtp.legacycontentsupport", "false", "locked.smtp.username", "user", "locked.smtp.password", "secret"));
        try (Mailer mailer = locked.mailerBuilder().withCustomMailer(new CountingCustomMailer()).clearCustomMailer()
                .withOAuth2AccessTokenProvider(new CountingTokenProvider()).clearOAuth2AccessTokenProvider()
                .resetOpportunisticTLS().resetLegacySmtpContentSupport().buildMailer()) {
            assertThat(mailer.getServerConfig().getUsername()).isEqualTo("user");
        }
    }

    private static SimpleJavaMail configuredFactory() {
        return factory(Map.of("smtp.host", "relay.example.org", "smtp.port", "2525", "smtp.username", "configured-user", "smtp.password", "configured-secret"));
    }

    private static SimpleJavaMail factory(final Map<String, String> values) {
        final Map<String, String> properties = new LinkedHashMap<>();
        values.forEach((key, value) -> properties.put("simplejavamail." + key, value));
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(properties).load());
    }

    private static final class CountingCustomMailer implements CustomMailer, AutoCloseable {
        private int closed;

        @Override
        public void testConnection(final OperationalConfig config, final Session session) { }

        @Override
        public void sendMessage(final OperationalConfig config, final Session session, final Email email, final MimeMessage message) { }

        @Override
        public void close() {
            closed++;
        }
    }

    private static final class CountingTokenProvider implements OAuth2AccessTokenProvider, AutoCloseable {
        private int closed;
        private int requested;

        @Override
        public String getAccessToken() {
            requested++;
            return "token";
        }

        @Override
        public void close() {
            closed++;
        }
    }
}
