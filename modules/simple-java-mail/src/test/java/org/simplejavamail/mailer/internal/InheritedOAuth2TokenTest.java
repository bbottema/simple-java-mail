package org.simplejavamail.mailer.internal;

import jakarta.mail.Authenticator;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.SmtpConnectionReport;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.mailer.internal.util.TransportConnectionHelper;
import testutil.smtp.SmtpConversation;
import testutil.smtp.SmtpTestTls;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROPERTY;
import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROVIDER_PROPERTY;
import static org.simplejavamail.mailer.internal.SmtpsOAuth2Test.acceptMessage;
import static org.simplejavamail.mailer.internal.SmtpsOAuth2Test.establishTls;
import static org.simplejavamail.mailer.internal.SmtpsOAuth2Test.quit;

/** Public operations authenticate against a local peer; no external account, callback mocking or live token is used. */
@Timeout(30)
class InheritedOAuth2TokenTest {
    private static final String USER = "oauth-user@example.test";
    private static final String TOKEN = "synthetic-inherited-oauth-token";
    private static final String PASSWORD = "synthetic-ordinary-password";
    private static SSLSocketFactory tlsFactory;

    enum TokenSource { INHERITED, DIRECT, OPAQUE_SHADOW, REGULAR_BUILDER, CALLBACK, ORDINARY_PASSWORD }
    enum Operation { SEND, TEST_CONNECTION, AUTHENTICATED_PROBE }

    @BeforeAll
    static void prepareLocalTls() throws Exception {
        tlsFactory = SmtpTestTls.tlsContext(SmtpTestTls.testTrustStoreWithSystemRoots()).getSocketFactory();
    }

    static Stream<Arguments> publicRoutes() {
        return Stream.of("smtp", "smtps").flatMap(protocol -> Arrays.stream(TokenSource.values())
                .flatMap(source -> Arrays.stream(Operation.values()).map(operation -> Arguments.of(protocol, source, operation))));
    }

    @ParameterizedTest(name = "{0}: {1} through {2}")
    @MethodSource("publicRoutes")
    void eachPublicRouteUsesProviderVisibleCredentialsWithoutChangingCallerStorage(final String protocol,
            final TokenSource source, final Operation operation) throws Exception {
        final AtomicInteger providerCalls = new AtomicInteger();
        final AtomicInteger authenticationCount = new AtomicInteger();
        final java.util.concurrent.CountDownLatch authenticationObserved = new java.util.concurrent.CountDownLatch(1);
        final Properties defaults = new Properties();
        final Properties properties = new Properties(defaults);
        final Object opaqueTokenEntry = new Object();
        configureCredentials(properties, defaults, source, opaqueTokenEntry, providerCalls);

        try (SmtpsOAuth2Test.Peer server = new SmtpsOAuth2Test.Peer("localhost", 1, peer -> {
            establishTls(peer, protocol.equals("smtp") ? "SMTP_OAUTH2" : "SMTPS_OAUTH2");
            authenticate(peer, source);
            authenticationCount.incrementAndGet();
            authenticationObserved.countDown();
            if (operation == Operation.SEND) {
                acceptMessage(peer);
            }
            quit(peer);
        })) {
            configureConnection(properties, protocol, server.port(), source);
            final Map<Object, Object> originalEntries = new LinkedHashMap<>(properties);
            final Session supplied = Session.getInstance(properties, source == TokenSource.ORDINARY_PASSWORD ? ordinaryAuthenticator() : null);
            try (Mailer mailer = createMailer(supplied, protocol, server.port(), source)) {
                assertThat(providerCalls).as("construction keeps the callback lazy").hasValue(0);
                if (source != TokenSource.REGULAR_BUILDER) {
                    assertThat(mailer.getSession()).isSameAs(supplied);
                    // The live Mailer adds internal ownership metadata, while caller entries keep their values and identities.
                    assertThat(properties).containsAllEntriesOf(originalEntries);
                } else {
                    assertThat(mailer.getSession().getProperties()).containsEntry(OAUTH2_TOKEN_PROPERTY, TOKEN);
                }
                assertThatCode(() -> execute(mailer, operation)).doesNotThrowAnyException();
                assertThat(authenticationObserved.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(authenticationCount).hasValue(1);
                assertThat(providerCalls).hasValue(source == TokenSource.CALLBACK ? 1 : 0);
                if (source != TokenSource.REGULAR_BUILDER) {
                    assertThat(properties).containsAllEntriesOf(originalEntries);
                }
            }
            if (source != TokenSource.REGULAR_BUILDER) {
                // Existing pool-owned aliases may remain; the caller's entries and inheritance must remain unchanged.
                assertThat(properties).containsAllEntriesOf(originalEntries);
            }
            if (source == TokenSource.INHERITED || source == TokenSource.OPAQUE_SHADOW) {
                assertThat(properties.getProperty(OAUTH2_TOKEN_PROPERTY)).isEqualTo(TOKEN);
                assertThat(defaults.getProperty(OAUTH2_TOKEN_PROPERTY)).isEqualTo(TOKEN);
                assertThat(properties.get(OAUTH2_TOKEN_PROPERTY)).isSameAs(source == TokenSource.OPAQUE_SHADOW ? opaqueTokenEntry : null);
            }
        }
    }

    @Test
    void providerObjectsInDefaultsRemainUnavailableAndUninvoked() {
        final AtomicInteger calls = new AtomicInteger();
        final OAuth2AccessTokenProvider provider = () -> { calls.incrementAndGet(); return TOKEN; };
        for (final String protocol : Arrays.asList("smtp", "smtps")) {
            final Properties defaults = new Properties();
            defaults.put(OAUTH2_TOKEN_PROVIDER_PROPERTY, provider);
            final Properties properties = new Properties(defaults);
            properties.setProperty("mail.transport.protocol", protocol);
            properties.setProperty("mail." + protocol + ".auth.mechanisms", "XOAUTH2");
            properties.setProperty("mail." + protocol + ".user", USER);
            final Session session = Session.getInstance(properties);
            assertThatThrownBy(() -> factory().mailerBuilder(session).buildMailer())
                    .isInstanceOf(MailException.class).hasMessage(MailerException.MISSING_OAUTH2_TOKEN);
            assertThat(properties).doesNotContainKey(OAUTH2_TOKEN_PROVIDER_PROPERTY);
            assertThat(defaults.get(OAUTH2_TOKEN_PROVIDER_PROPERTY)).isSameAs(provider);
        }
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"smtp", "smtps"})
    void inheritedTokenReachesTheDirectSendConnectionBoundary(final String protocol) throws Exception {
        final Properties defaults = new Properties();
        defaults.setProperty(OAUTH2_TOKEN_PROPERTY, TOKEN);
        final Properties properties = new Properties(defaults);
        properties.setProperty("mail.transport.protocol", protocol);
        properties.setProperty("mail." + protocol + ".user", USER);
        final Session session = Session.getInstance(properties);
        final AtomicReference<String> observedPassword = new AtomicReference<>();
        try (Transport transport = new Transport(session, new URLName(protocol, "unused.example.test", 2525, null, USER, null)) {
            @Override
            protected boolean protocolConnect(final String host, final int port, final String username, final String password) {
                assertThat(username).isEqualTo(USER);
                observedPassword.set(password);
                return true;
            }

            @Override
            public void sendMessage(final Message message, final Address[] addresses) {
                throw new AssertionError("This direct connection control does not submit a message");
            }
        }) {
            TransportConnectionHelper.connectForSending(transport, session);
            assertThat(observedPassword).hasValue(TOKEN);
        }
        assertThat(properties).doesNotContainKey(OAUTH2_TOKEN_PROPERTY);
        assertThat(properties.getProperty(OAUTH2_TOKEN_PROPERTY)).isEqualTo(TOKEN);
    }

    private static void configureCredentials(final Properties properties, final Properties defaults, final TokenSource source,
            final Object opaqueTokenEntry, final AtomicInteger calls) {
        switch (source) {
            case INHERITED:
            case OPAQUE_SHADOW:
                defaults.setProperty(OAUTH2_TOKEN_PROPERTY, TOKEN);
                if (source == TokenSource.OPAQUE_SHADOW) {
                    properties.put(OAUTH2_TOKEN_PROPERTY, opaqueTokenEntry);
                }
                break;
            case DIRECT:
                properties.setProperty(OAUTH2_TOKEN_PROPERTY, TOKEN);
                break;
            case CALLBACK:
                properties.put(OAUTH2_TOKEN_PROVIDER_PROPERTY, (OAuth2AccessTokenProvider) () -> {
                    calls.incrementAndGet();
                    return TOKEN;
                });
                break;
            default:
                // The regular builder supplies its own fixed token; the ordinary Session uses an Authenticator instead.
        }
    }

    private static void configureConnection(final Properties properties, final String protocol, final int port, final TokenSource source) {
        final String prefix = "mail." + protocol + ".";
        properties.setProperty("mail.host", "localhost");
        properties.setProperty("mail.transport.protocol", protocol);
        properties.setProperty(prefix + "host", "localhost");
        properties.setProperty(prefix + "port", Integer.toString(port));
        properties.setProperty(prefix + "user", USER);
        properties.setProperty(prefix + "auth", "true");
        properties.setProperty(prefix + "auth.mechanisms", source == TokenSource.ORDINARY_PASSWORD ? "PLAIN" : "XOAUTH2");
        properties.setProperty(prefix + "localhost", "probe.example.test");
        properties.setProperty(prefix + "connectiontimeout", "5000");
        properties.setProperty(prefix + "timeout", "5000");
        properties.setProperty(prefix + "writetimeout", "5000");
        properties.setProperty(prefix + "quitwait", "true");
        properties.setProperty(prefix + "ssl.checkserveridentity", "true");
        properties.put(prefix + "ssl.socketFactory", tlsFactory);
        if (protocol.equals("smtp")) {
            properties.setProperty(prefix + "starttls.enable", "true");
            properties.setProperty(prefix + "starttls.required", "true");
        }
    }

    private static Mailer createMailer(final Session supplied, final String protocol, final int port, final TokenSource source) {
        if (source == TokenSource.REGULAR_BUILDER) {
            return factory().mailerBuilder().withSMTPServer("localhost", port, USER, TOKEN)
                    .withProperty("mail.host", "localhost")
                    .withTransportStrategy(protocol.equals("smtp") ? TransportStrategy.SMTP_OAUTH2 : TransportStrategy.SMTPS_OAUTH2)
                    .withTlsConfig(TlsConfig.builder().withCustomSSLFactoryInstance(tlsFactory).build())
                    .withConnectionConfig(ConnectionConfig.builder().withSessionTimeout(Duration.ofSeconds(5))
                            .withSmtpClientHostname("probe.example.test").build())
                    .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).build()).buildMailer();
        }
        return factory().mailerBuilder(supplied)
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).build()).buildMailer();
    }

    private static Authenticator ordinaryAuthenticator() {
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(USER, PASSWORD);
            }
        };
    }

    private static void execute(final Mailer mailer, final Operation operation) {
        switch (operation) {
            case SEND:
                assertThat(mailer.sync().sendMail(factory().emailBuilder().startingBlank().from("sender@example.test")
                        .to("recipient@example.test").withSubject("Inherited OAuth2 token")
                        .withPlainText("Synthetic local SMTP fixture.").buildEmail()).getStatus()).isEqualTo(ACCEPTED);
                break;
            case TEST_CONNECTION:
                mailer.sync().testConnection();
                break;
            case AUTHENTICATED_PROBE:
                final SmtpConnectionReport report = mailer.sync().probeConnection(true);
                assertThat(report.isSuccessful()).as(report.toString()).isTrue();
                assertThat(report.isAuthenticated()).isTrue();
                assertThat(report.isTlsActive()).isTrue();
                assertThat(report.toString()).doesNotContain(TOKEN, PASSWORD, USER);
                break;
            default:
                throw new AssertionError(operation);
        }
    }

    private static SimpleJavaMail factory() {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    }

    private static void authenticate(final SmtpConversation peer, final TokenSource source) throws IOException {
        final String mechanism = source == TokenSource.ORDINARY_PASSWORD ? "PLAIN" : "XOAUTH2";
        final String line = peer.readLine();
        assertThat(line).startsWith("AUTH " + mechanism + " ");
        final String decoded = new String(Base64.getDecoder().decode(line.substring(("AUTH " + mechanism + " ").length())), StandardCharsets.UTF_8);
        if (source == TokenSource.ORDINARY_PASSWORD) {
            assertThat(decoded).isIn("\0" + USER + "\0" + PASSWORD, USER + "\0" + USER + "\0" + PASSWORD);
        } else {
            assertThat(decoded).isEqualTo("user=" + USER + "\1auth=Bearer " + TOKEN + "\1\1");
        }
        peer.reply("235 authenticated");
    }

}
