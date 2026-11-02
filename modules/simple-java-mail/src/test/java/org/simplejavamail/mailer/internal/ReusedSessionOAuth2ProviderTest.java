package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROPERTY;
import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROVIDER_PROPERTY;
import static org.simplejavamail.mailer.internal.EmailGovernanceImpl.NO_GOVERNANCE;
import static testutil.EmailHelper.createDummyOperationalConfig;

class ReusedSessionOAuth2ProviderTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @ParameterizedTest
    @MethodSource("ordinaryStrategiesAndInputRoutes")
    void currentMechanismsPermitALazyProviderAfterOrdinaryMailerShutdown(final TransportStrategy originalStrategy,
            final boolean directSessionEdit) throws Exception {
        final Session session = releasedSession(originalStrategy);
        final String mechanismProperty = mechanismProperty(session);
        final AtomicInteger tokenRequests = new AtomicInteger();
        final OAuth2AccessTokenProvider provider = () -> "token-" + tokenRequests.incrementAndGet();
        final MailerFromSessionBuilder<?> builder = mail.mailerBuilder(session);
        if (directSessionEdit) {
            session.getProperties().setProperty(mechanismProperty, "XOAUTH2");
        } else {
            builder.withProperty(mechanismProperty, "XOAUTH2");
        }

        try (Mailer reused = builder.withOAuth2AccessTokenProvider(provider).buildMailer()) {
            assertThat(reused.getSession()).isSameAs(session);
            assertThat(session.getProperty(mechanismProperty)).isEqualTo("XOAUTH2");
            assertThat(TransportStrategy.findStrategyForSession(session)).isEqualTo(originalStrategy);
            assertThat(session.getProperties().get(OAUTH2_TOKEN_PROVIDER_PROPERTY)).isSameAs(provider);
            assertThat(tokenRequests).hasValue(0);
        }
        assertThat(tokenRequests).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(value = TransportStrategy.class, names = {"SMTP_OAUTH2", "SMTPS_OAUTH2"})
    void historicalOAuthMarkerDoesNotPermitAProviderForCurrentPlainAuthentication(final TransportStrategy originalStrategy)
            throws Exception {
        final Session session = releasedSession(originalStrategy);
        session.getProperties().remove(OAUTH2_TOKEN_PROPERTY);
        session.getProperties().setProperty(mechanismProperty(session), "PLAIN");
        final Properties before = copy(session.getProperties());
        final AtomicInteger tokenRequests = new AtomicInteger();

        assertThatThrownBy(() -> {
            try (Mailer ignored = mail.mailerBuilder(session)
                    .withOAuth2AccessTokenProvider(() -> "token-" + tokenRequests.incrementAndGet()).buildMailer()) {
                // An incorrectly accepted build still closes its owned resources.
            }
        }).isInstanceOf(MailException.class).hasMessage(MailerException.OAUTH2_PROVIDER_REQUIRES_OAUTH2_STRATEGY);

        assertThat(session.getProperties()).isEqualTo(before);
        assertThat(tokenRequests).hasValue(0);
        try (Mailer accepted = mail.mailerBuilder(session).buildMailer()) {
            assertThat(accepted.getSession()).isSameAs(session);
            assertThat(session.getProperty(mechanismProperty(session))).isEqualTo("PLAIN");
        }
    }

    @ParameterizedTest
    @EnumSource(value = TransportStrategy.class, names = {"SMTP_OAUTH2", "SMTPS_OAUTH2"}, mode = EnumSource.Mode.EXCLUDE)
    void selectingOAuthWithoutCredentialsRetainsTheMissingTokenFailure(final TransportStrategy originalStrategy) throws Exception {
        final Session session = releasedSession(originalStrategy);
        final Properties before = copy(session.getProperties());
        assertThatThrownBy(() -> {
            try (Mailer ignored = mail.mailerBuilder(session).withProperty(mechanismProperty(session), "XOAUTH2").buildMailer()) {
                // An incorrectly accepted build still closes its owned resources.
            }
        }).isInstanceOf(MailException.class).hasMessage(MailerException.MISSING_OAUTH2_TOKEN);
        assertThat(session.getProperties()).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(value = TransportStrategy.class, names = {"SMTP_OAUTH2", "SMTPS_OAUTH2"}, mode = EnumSource.Mode.EXCLUDE)
    void selectingOAuthRetainsTheFixedTokenProviderConflict(final TransportStrategy originalStrategy) throws Exception {
        final Session session = releasedSession(originalStrategy);
        final Properties before = copy(session.getProperties());
        final AtomicInteger tokenRequests = new AtomicInteger();
        assertThatThrownBy(() -> mail.mailerBuilder(session)
                .withProperty(mechanismProperty(session), "XOAUTH2")
                .withProperty(OAUTH2_TOKEN_PROPERTY, "fixed-token")
                .withOAuth2AccessTokenProvider(() -> "token-" + tokenRequests.incrementAndGet()).buildMailer())
                .isInstanceOf(MailException.class).hasMessage(MailerException.CONFLICTING_OAUTH2_CREDENTIALS);
        assertThat(session.getProperties()).isEqualTo(before);
        assertThat(tokenRequests).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(value = TransportStrategy.class, names = {"SMTP_OAUTH2", "SMTPS_OAUTH2"})
    void anInactiveProtocolsOAuthMechanismDoesNotPermitTheProvider(final TransportStrategy originalStrategy) throws Exception {
        final Session session = releasedSession(originalStrategy);
        session.getProperties().remove(OAUTH2_TOKEN_PROPERTY);
        final String activeProtocol = "smtp".equals(session.getProperty("mail.transport.protocol")) ? "smtps" : "smtp";
        session.getProperties().setProperty("mail.transport.protocol", activeProtocol);
        session.getProperties().setProperty("mail." + activeProtocol + ".auth.mechanisms", "PLAIN");
        final Properties before = copy(session.getProperties());

        assertThatThrownBy(() -> {
            try (Mailer ignored = mail.mailerBuilder(session).withOAuth2AccessTokenProvider(() -> "unused-token").buildMailer()) {
                // An incorrectly accepted build still closes its owned resources.
            }
        }).isInstanceOf(MailException.class).hasMessage(MailerException.OAUTH2_PROVIDER_REQUIRES_OAUTH2_STRATEGY);
        assertThat(session.getProperties()).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(value = TransportStrategy.class, names = {"SMTP_OAUTH2", "SMTPS_OAUTH2"}, mode = EnumSource.Mode.EXCLUDE)
    void internalCompletedFactsConstructionUsesTheSameSuppliedSessionRule(final TransportStrategy originalStrategy) throws Exception {
        final Session session = releasedSession(originalStrategy);
        session.getProperties().setProperty(mechanismProperty(session), "XOAUTH2");
        final AtomicInteger tokenRequests = new AtomicInteger();
        final OAuth2AccessTokenProvider provider = () -> "token-" + tokenRequests.incrementAndGet();
        session.getProperties().put(OAUTH2_TOKEN_PROVIDER_PROPERTY, provider);
        final OperationalConfig operational = createDummyOperationalConfig(Collections.emptyList(), false, true);
        try {
            try (Mailer internal = new MailerImpl(null, originalStrategy, NO_GOVERNANCE(), new ProxyConfigImpl(null, null, null, null, null),
                    session, operational)) {
                assertThat(internal.getSession()).isSameAs(session);
                assertThat(session.getProperties().get(OAUTH2_TOKEN_PROVIDER_PROPERTY)).isSameAs(provider);
                assertThat(tokenRequests).hasValue(0);
            }
        } finally {
            operational.getExecutorService().shutdownNow();
        }
    }

    private Session releasedSession(final TransportStrategy originalStrategy) throws Exception {
        try (Mailer original = mail.mailerBuilder()
                .withSMTPServer("smtp.example.test", originalStrategy.getDefaultServerPort(), "account@example.test",
                        originalStrategy.name().contains("OAUTH2") ? "original-fixed-token" : null)
                .withTransportStrategy(originalStrategy).buildMailer()) {
            return original.getSession();
        }
    }

    private static Stream<Arguments> ordinaryStrategiesAndInputRoutes() {
        return Arrays.stream(TransportStrategy.values()).filter(strategy -> !strategy.name().contains("OAUTH2"))
                .flatMap(strategy -> Stream.of(Arguments.of(strategy, true), Arguments.of(strategy, false)));
    }

    private static String mechanismProperty(final Session session) {
        return "mail." + session.getProperty("mail.transport.protocol") + ".auth.mechanisms";
    }

    private static Properties copy(final Properties source) {
        final Properties result = new Properties();
        result.putAll(source);
        return result;
    }
}
