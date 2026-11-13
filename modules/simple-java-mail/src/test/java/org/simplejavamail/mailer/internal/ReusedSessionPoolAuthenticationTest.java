package org.simplejavamail.mailer.internal;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.smtpconnectionpool.SmtpConnectionPool;
import testutil.ConfigLoaderTestHelper;
import testutil.smtp.SmtpConversation;
import testutil.smtp.SmtpTestTls;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROPERTY;
import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROVIDER_PROPERTY;
import static org.simplejavamail.mailer.internal.SmtpsOAuth2Test.acceptMessage;
import static org.simplejavamail.mailer.internal.SmtpsOAuth2Test.establishTls;
import static org.simplejavamail.mailer.internal.SmtpsOAuth2Test.quit;

/** Exercises a retained Session after the previous Mailer's authenticated connections have fully retired. */
@Timeout(45)
class ReusedSessionPoolAuthenticationTest {
    private static final String USER = "reused-account@example.test";
    private static final String OLD_TOKEN = "synthetic-retired-token";
    private static final String CURRENT_TOKEN = "synthetic-current-token";
    private static final String PASSWORD = "synthetic-session-password";

    private enum Replacement { FIXED_TOKEN, CALLBACK, SESSION_AUTHENTICATION }

    static Stream<Arguments> replacements() {
        return Stream.of("smtp", "smtps").flatMap(protocol -> Stream.of(
                Arguments.of(protocol, Replacement.FIXED_TOKEN, false),
                Arguments.of(protocol, Replacement.CALLBACK, false),
                Arguments.of(protocol, Replacement.SESSION_AUTHENTICATION, false),
                Arguments.of(protocol, Replacement.FIXED_TOKEN, true)));
    }

    @ParameterizedTest(name = "{0}: {1}, retiredCallbackThrows={2}")
    @MethodSource("replacements")
    void retiredPoolAliasesCannotReplaceTheNewMailersAuthentication(final String protocol,
            final Replacement replacement, final boolean retiredCallbackThrows) throws Exception {
        assertThat(ModuleLoader.batchModuleAvailable()).as("ordinary sends must use the optional pool").isTrue();
        final AtomicInteger oldProviderCalls = new AtomicInteger();
        final AtomicInteger currentProviderCalls = new AtomicInteger();
        final AtomicInteger authenticatorCalls = new AtomicInteger();
        final AtomicBoolean previousOwnerRetired = new AtomicBoolean();
        final OAuth2AccessTokenProvider oldProvider = () -> {
            oldProviderCalls.incrementAndGet();
            if (previousOwnerRetired.get() && retiredCallbackThrows) {
                throw new IllegalStateException("The retired token service must not be consulted");
            }
            return OLD_TOKEN;
        };
        final List<String> frames = new CopyOnWriteArrayList<>();
        final AtomicInteger connections = new AtomicInteger();
        try (SmtpsOAuth2Test.Peer server = new SmtpsOAuth2Test.Peer("localhost", 2, peer -> {
            final boolean originalConnection = connections.getAndIncrement() == 0;
            establishTls(peer, protocol.equals("smtp") ? "SMTP_OAUTH2" : "SMTPS_OAUTH2");
            observeAuthentication(peer, originalConnection || replacement != Replacement.SESSION_AUTHENTICATION ? "XOAUTH2" : "PLAIN", frames);
            acceptMessage(peer);
            acceptMessage(peer);
            quit(peer);
        })) {
            final Properties properties = connectionProperties(protocol, server.port());
            final Authenticator authenticator = new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    authenticatorCalls.incrementAndGet();
                    return new PasswordAuthentication(USER, PASSWORD);
                }
            };
            final Session retained = Session.getInstance(properties, authenticator);
            try (Mailer original = factory().mailerBuilder(retained)
                    .withConnectionPoolConfig(poolSettings())
                    .withOAuth2AccessTokenProvider(oldProvider).buildMailer()) {
                assertThat(oldProviderCalls).as("configuration must not fetch tokens").hasValue(0);
                sendTwoMessages(original);
            }
            assertThat(oldProviderCalls).hasValue(1);
            previousOwnerRetired.set(true);

            // Model a Session carrying aliases installed by an older SJM, without requiring new SJM to create them.
            properties.put(SmtpConnectionPool.OAUTH2_TOKEN_PROVIDER_PROPERTY, oldProvider);
            properties.setProperty(SmtpConnectionPool.OAUTH2_TOKEN_PROPERTY, OLD_TOKEN);
            properties.remove(OAUTH2_TOKEN_PROVIDER_PROPERTY);
            properties.remove(OAUTH2_TOKEN_PROPERTY);
            if (replacement == Replacement.FIXED_TOKEN) {
                properties.setProperty(OAUTH2_TOKEN_PROPERTY, CURRENT_TOKEN);
            } else if (replacement == Replacement.CALLBACK) {
                properties.put(OAUTH2_TOKEN_PROVIDER_PROPERTY, (OAuth2AccessTokenProvider) () -> {
                    currentProviderCalls.incrementAndGet();
                    return CURRENT_TOKEN;
                });
            } else {
                properties.setProperty("mail." + protocol + ".auth.mechanisms", "PLAIN");
            }
            final Map<Object, Object> callerEntries = new LinkedHashMap<>(properties);

            try (Mailer current = factory().mailerBuilder(retained).withConnectionPoolConfig(poolSettings()).buildMailer()) {
                assertThat(current.getSession()).isSameAs(retained);
                assertThat(currentProviderCalls).as("replacement callback remains lazy").hasValue(0);
                sendTwoMessages(current);
            }

            assertThat(frames).containsExactly(oauthFrame(OLD_TOKEN), replacement == Replacement.SESSION_AUTHENTICATION
                    ? "PLAIN:" + "\0" + USER + "\0" + PASSWORD : oauthFrame(CURRENT_TOKEN));
            assertThat(oldProviderCalls).as("only the retired owner's physical connection used its callback").hasValue(1);
            assertThat(currentProviderCalls).hasValue(replacement == Replacement.CALLBACK ? 1 : 0);
            assertThat(authenticatorCalls).hasValue(replacement == Replacement.SESSION_AUTHENTICATION ? 1 : 0);
            assertThat(connections).hasValue(2);
            assertThat(properties).containsAllEntriesOf(callerEntries);
            assertThat(properties.get(SmtpConnectionPool.OAUTH2_TOKEN_PROVIDER_PROPERTY)).isSameAs(oldProvider);
        }
    }

    private static Properties connectionProperties(final String protocol, final int port) throws Exception {
        final Properties properties = new Properties();
        final String prefix = "mail." + protocol + ".";
        properties.setProperty("mail.transport.protocol", protocol);
        properties.setProperty("mail.host", "localhost");
        properties.setProperty(prefix + "host", "localhost");
        properties.setProperty(prefix + "port", Integer.toString(port));
        properties.setProperty(prefix + "user", USER);
        properties.setProperty(prefix + "auth", "true");
        properties.setProperty(prefix + "auth.mechanisms", "XOAUTH2");
        properties.setProperty(prefix + "localhost", "probe.example.test");
        properties.setProperty(prefix + "connectiontimeout", "5000");
        properties.setProperty(prefix + "timeout", "5000");
        properties.setProperty(prefix + "writetimeout", "5000");
        properties.setProperty(prefix + "quitwait", "true");
        properties.setProperty(prefix + "ssl.checkserveridentity", "true");
        properties.put(prefix + "ssl.socketFactory", SmtpTestTls.tlsContext(SmtpTestTls.testTrustStoreWithSystemRoots()).getSocketFactory());
        if (protocol.equals("smtp")) {
            properties.setProperty(prefix + "starttls.enable", "true");
            properties.setProperty(prefix + "starttls.required", "true");
        }
        return properties;
    }

    private static void observeAuthentication(final SmtpConversation peer, final String mechanism,
            final List<String> frames) throws IOException {
        final String line = peer.readLine();
        assertThat(line).startsWith("AUTH " + mechanism + " ");
        final String decoded = new String(Base64.getDecoder().decode(line.substring(("AUTH " + mechanism + " ").length())), StandardCharsets.UTF_8);
        // Jakarta Mail permits either an empty authorization identity or the same identity as the authentication user.
        final String normalized = mechanism.equals("PLAIN") && decoded.startsWith(USER + "\0")
                ? decoded.substring(USER.length()) : decoded;
        frames.add(mechanism + ":" + normalized);
        peer.reply("235 authenticated");
    }

    private static String oauthFrame(final String token) {
        return "XOAUTH2:user=" + USER + "\1auth=Bearer " + token + "\1\1";
    }

    private static void sendTwoMessages(final Mailer mailer) {
        final Email email = factory().emailBuilder().startingBlank().from("sender@example.test").to("recipient@example.test")
                .withSubject("Retained Session credentials").withPlainText("Synthetic local SMTP fixture.").buildEmail();
        assertThat(mailer.sync().sendMail(email).getStatus()).isEqualTo(ACCEPTED);
        assertThat(mailer.sync().sendMail(email).getStatus()).isEqualTo(ACCEPTED);
    }

    private static ConnectionPoolConfig poolSettings() {
        return ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1).build();
    }

    private static SimpleJavaMail factory() {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    }
}
