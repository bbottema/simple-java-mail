package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequiredMailerInputTest {

    @Test
    void rejectedCallbackReplacementKeepsCustomDeliveryForExistingAndFutureMailers() throws Exception {
        final SimpleJavaMail factory = factory();
        final CountingDelivery delivery = new CountingDelivery();
        final MailerRegularBuilder<?> builder = factory.mailerBuilder()
                .withSMTPServer("unused.example.org", 25).withCustomMailer(delivery);
        try (Mailer original = builder.buildMailer()) {
            assertThatThrownBy(() -> builder.withCustomMailer(null)).isInstanceOf(NullPointerException.class);

            assertThat(builder.getCustomMailer()).isSameAs(delivery);
            try (Mailer replacement = builder.buildMailer()) {
                replacement.sync().sendMail(message(factory));
                assertThat(replacement.getOperationalConfig().getCustomMailer()).isSameAs(delivery);
            }
            original.sync().sendMail(message(factory));
            assertThat(original.getOperationalConfig().getCustomMailer()).isSameAs(delivery);
        }
        assertThat(delivery.submitted).isEqualTo(2);
        assertThat(delivery.closed).isZero();
    }

    @Test
    void rejectedTokenProviderReplacementKeepsItsBorrowedIdentityWithoutRequestingTokens() throws Exception {
        final CountingTokens tokens = new CountingTokens();
        final MailerRegularBuilder<?> builder = factory().mailerBuilder()
                .withSMTPServer("unused.example.org", 587, "user")
                .withTransportStrategy(TransportStrategy.SMTP_OAUTH2)
                .withOAuth2AccessTokenProvider(tokens);
        try (Mailer original = builder.buildMailer()) {
            assertThatThrownBy(() -> builder.withOAuth2AccessTokenProvider(null)).isInstanceOf(NullPointerException.class);

            assertThat(builder.getOAuth2AccessTokenProvider()).isSameAs(tokens);
            try (Mailer replacement = builder.buildMailer()) {
                assertThat(replacement.getOperationalConfig().getOAuth2AccessTokenProvider()).isSameAs(tokens);
            }
            assertThat(original.getOperationalConfig().getOAuth2AccessTokenProvider()).isSameAs(tokens);
        }
        assertThat(tokens.requested).isZero();
        assertThat(tokens.closed).isZero();
    }

    @Test
    void rejectedDefaultsReplacementPreservesGovernanceForExistingAndFutureMailers() throws Exception {
        final SimpleJavaMail factory = factory();
        final Email defaults = factory.emailBuilder().startingBlank().withSubject("Default subject").buildEmail();
        final MailerRegularBuilder<?> builder = factory.mailerBuilder()
                .withCustomMailer(new CountingDelivery()).withEmailDefaults(defaults);
        try (Mailer original = builder.buildMailer()) {
            assertThatThrownBy(() -> builder.withEmailDefaults(null)).isInstanceOf(NullPointerException.class);

            assertThat(builder.getEmailDefaults()).isSameAs(defaults);
            try (Mailer replacement = builder.buildMailer()) {
                assertThat(replacement.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(null).getSubject())
                        .isEqualTo("Default subject");
            }
            assertThat(original.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(null).getSubject())
                    .isEqualTo("Default subject");
        }
    }

    @Test
    void rejectedOverridesReplacementPreservesGovernanceForExistingAndFutureMailers() throws Exception {
        final SimpleJavaMail factory = factory();
        final Email overrides = factory.emailBuilder().startingBlank().withSubject("Required subject").buildEmail();
        final MailerRegularBuilder<?> builder = factory.mailerBuilder()
                .withCustomMailer(new CountingDelivery()).withEmailOverrides(overrides);
        try (Mailer original = builder.buildMailer()) {
            assertThatThrownBy(() -> builder.withEmailOverrides(null)).isInstanceOf(NullPointerException.class);

            assertThat(builder.getEmailOverrides()).isSameAs(overrides);
            try (Mailer replacement = builder.buildMailer()) {
                assertThat(replacement.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(message(factory)).getSubject())
                        .isEqualTo("Required subject");
            }
            assertThat(original.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(message(factory)).getSubject())
                    .isEqualTo("Required subject");
        }
    }

    @Test
    void rejectedSessionReplacementPreservesTheSessionAndItsCurrentOwner() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", "unused.example.org");
        properties.setProperty("mail.smtp.port", "25");
        final Session session = Session.getInstance(properties);
        final MailerFromSessionBuilder<?> builder = factory().mailerBuilder(session);
        try (Mailer original = builder.buildMailer()) {
            final Properties before = new Properties();
            before.putAll(properties);

            assertThatThrownBy(() -> builder.usingSession(null)).isInstanceOf(NullPointerException.class);

            assertThat(builder.getSession()).isSameAs(session);
            assertThat(original.getSession()).isSameAs(session);
            assertThat(properties).isEqualTo(before);
        }
        try (Mailer replacement = builder.buildMailer()) {
            assertThat(replacement.getSession()).isSameAs(session);
        }
    }

    @Test
    void missingRequiredChoicesAreRejectedOnAnUnconfiguredBuilder() {
        final MailerRegularBuilder<?> builder = factory().mailerBuilder();

        assertThatThrownBy(() -> builder.withCustomMailer(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.withOAuth2AccessTokenProvider(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.withEmailDefaults(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.withEmailOverrides(null)).isInstanceOf(NullPointerException.class);

        assertThat(builder.getCustomMailer()).isNull();
        assertThat(builder.getOAuth2AccessTokenProvider()).isNull();
        assertThat(builder.getEmailDefaults()).isNull();
        assertThat(builder.getEmailOverrides()).isNull();
        assertThatThrownBy(() -> factory().mailerBuilder((Session) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void acceptedReplacementsAndNamedTemplateRemovalKeepTheirExistingMeaning() throws Exception {
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoader.builder()
                .withMap(java.util.Map.of("simplejavamail.defaults.subject", "Configured subject")).load());
        final CountingDelivery first = new CountingDelivery();
        final CountingDelivery second = new CountingDelivery();
        final Email defaults = factory.emailBuilder().startingBlank().withSubject("Default subject").buildEmail();
        final Email overrides = factory.emailBuilder().startingBlank().withSubject("Override subject").buildEmail();
        final MailerRegularBuilder<?> builder = factory.mailerBuilder().withCustomMailer(first).withCustomMailer(second)
                .withEmailDefaults(defaults).withEmailOverrides(overrides);

        assertThat(builder.getCustomMailer()).isSameAs(second);
        builder.clearEmailOverrides().useConfiguredEmailDefaults();
        try (Mailer configured = builder.buildMailer()) {
            assertThat(configured.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(null).getSubject())
                    .isEqualTo("Configured subject");
        }
        builder.withoutEmailDefaults();
        try (Mailer noDefaults = builder.buildMailer()) {
            assertThat(noDefaults.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(null).getSubject()).isNull();
        }
        assertThat(first.closed).isZero();
        assertThat(second.closed).isZero();
    }

    private static SimpleJavaMail factory() {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    }

    private static Email message(final SimpleJavaMail factory) {
        return factory.emailBuilder().startingBlank().from("sender@example.org").to("receiver@example.org")
                .withSubject("Submitted subject").withPlainText("Body").buildEmail();
    }

    private static final class CountingDelivery implements CustomMailer, AutoCloseable {
        private int submitted;
        private int closed;

        @Override
        public void testConnection(final OperationalConfig config, final Session session) { }

        @Override
        public void sendMessage(final OperationalConfig config, final Session session, final Email email, final MimeMessage message) {
            submitted++;
        }

        @Override
        public void close() {
            closed++;
        }
    }

    private static final class CountingTokens implements OAuth2AccessTokenProvider, AutoCloseable {
        private int requested;
        private int closed;

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
