package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;
import org.simplejavamail.api.mailer.SmtpConnectionReport;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.ConfigLoaderTestHelper;
import testutil.smtp.SmtpConversation;
import testutil.smtp.SmtpTestTls;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROPERTY;
import static org.simplejavamail.mailer.internal.SmtpsOAuth2Test.acceptMessage;
import static org.simplejavamail.mailer.internal.SmtpsOAuth2Test.establishTls;
import static org.simplejavamail.mailer.internal.SmtpsOAuth2Test.quit;

/** Checks the authenticated account on real TLS connections, including ordinary pooled sends. */
@Timeout(30)
class PooledOAuthProtocolUsernameTest {
    private static final String USER = "active-user@example.test";
    private static final String INACTIVE_USER = "inactive-user@example.test";
    private static final String TOKEN = "synthetic-protocol-token";

    private enum Route { POOLED_SEND, DEDICATED_SEND, CONNECTION_TEST, AUTHENTICATED_PROBE }

    static Stream<Arguments> connectionRoutes() {
        return Stream.of("smtp", "smtps").flatMap(protocol -> Stream.of(false, true)
                .flatMap(callback -> Arrays.stream(Route.values()).map(route -> Arguments.of(protocol, callback, route))));
    }

    @ParameterizedTest(name = "{0}: callback={1}, {2}")
    @MethodSource("connectionRoutes")
    void suppliedSessionUsesOnlyTheSelectedProtocolsUsername(final String protocol, final boolean callback,
            final Route route) throws Exception {
        assertThat(ModuleLoader.batchModuleAvailable()).as("ordinary sends must exercise the optional pool").isTrue();
        final AtomicInteger providerCalls = new AtomicInteger();
        final List<String> authenticationFrames = new CopyOnWriteArrayList<>();
        try (SmtpsOAuth2Test.Peer server = new SmtpsOAuth2Test.Peer("localhost", 1, peer -> {
            establishTls(peer, protocol.equals("smtp") ? "SMTP_OAUTH2" : "SMTPS_OAUTH2");
            observeAuthentication(peer, authenticationFrames);
            if (route == Route.POOLED_SEND || route == Route.DEDICATED_SEND) {
                acceptMessage(peer);
                acceptMessage(peer);
            }
            quit(peer);
        })) {
            final Properties properties = connectionProperties(protocol, server.port());
            if (!callback) {
                properties.setProperty(OAUTH2_TOKEN_PROPERTY, TOKEN);
            }
            final Map<Object, Object> originalEntries = new LinkedHashMap<>(properties);
            final Session supplied = Session.getInstance(properties);
            final MailerFromSessionBuilder<?> builder = factory().mailerBuilder(supplied)
                    .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1).build());
            if (callback) {
                builder.withOAuth2AccessTokenProvider(() -> { providerCalls.incrementAndGet(); return TOKEN; });
            }
            try (Mailer mailer = builder.buildMailer()) {
                assertThat(mailer.getSession()).isSameAs(supplied);
                assertThat(providerCalls).as("construction must not obtain a token").hasValue(0);
                assertThat(properties).containsAllEntriesOf(originalEntries);

                execute(mailer, route);

                assertThat(authenticationFrames).containsExactly("user=" + USER + "\1auth=Bearer " + TOKEN + "\1\1");
                assertThat(providerCalls).as("two messages reuse one authenticated physical connection")
                        .hasValue(callback ? 1 : 0);
                assertThat(properties).containsAllEntriesOf(originalEntries);
            }
            assertThat(properties).containsAllEntriesOf(originalEntries);
        }
    }

    private static Properties connectionProperties(final String protocol, final int port) throws Exception {
        final Properties properties = new Properties();
        final String prefix = "mail." + protocol + ".";
        properties.setProperty("mail.transport.protocol", protocol);
        properties.setProperty(prefix + "host", "localhost");
        properties.setProperty(prefix + "port", Integer.toString(port));
        properties.setProperty(prefix + "user", USER);
        properties.setProperty("mail." + (protocol.equals("smtp") ? "smtps" : "smtp") + ".user", INACTIVE_USER);
        properties.setProperty(prefix + "auth.mechanisms", "XOAUTH2");
        properties.setProperty(prefix + "localhost", "probe.example.test");
        properties.setProperty(prefix + "connectiontimeout", "5000");
        properties.setProperty(prefix + "timeout", "5000");
        properties.setProperty(prefix + "writetimeout", "5000");
        properties.setProperty(prefix + "ssl.checkserveridentity", "true");
        properties.put(prefix + "ssl.socketFactory", SmtpTestTls.tlsContext(SmtpTestTls.testTrustStoreWithSystemRoots()).getSocketFactory());
        if (protocol.equals("smtp")) {
            properties.setProperty(prefix + "starttls.enable", "true");
            properties.setProperty(prefix + "starttls.required", "true");
        }
        return properties;
    }

    private static void observeAuthentication(final SmtpConversation peer, final List<String> frames) throws IOException {
        final String line = peer.readLine();
        assertThat(line).startsWith("AUTH XOAUTH2 ");
        frames.add(new String(Base64.getDecoder().decode(line.substring("AUTH XOAUTH2 ".length())), StandardCharsets.UTF_8));
        peer.reply("235 authenticated");
    }

    private static void execute(final Mailer mailer, final Route route) {
        switch (route) {
            case POOLED_SEND:
                // Separate ordinary sends must share the one physical connection accepted by the peer.
                assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
                assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
                break;
            case DEDICATED_SEND:
                mailer.withOpenConnection(sender -> {
                    assertThat(sender.sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
                    assertThat(sender.sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
                });
                break;
            case CONNECTION_TEST:
                mailer.sync().testConnection();
                break;
            case AUTHENTICATED_PROBE:
                final SmtpConnectionReport report = mailer.sync().probeConnection(true);
                assertThat(report.isSuccessful()).as(report.toString()).isTrue();
                assertThat(report.isAuthenticated()).isTrue();
                assertThat(report.isTlsActive()).isTrue();
                break;
            default:
                throw new AssertionError(route);
        }
    }

    private static Email email() {
        return factory().emailBuilder().startingBlank().from("sender@example.test").to("recipient@example.test")
                .withSubject("Protocol-specific OAuth account").withPlainText("Synthetic local SMTP fixture.").buildEmail();
    }

    private static SimpleJavaMail factory() {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    }
}
