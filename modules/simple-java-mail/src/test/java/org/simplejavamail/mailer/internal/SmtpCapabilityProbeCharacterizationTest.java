package org.simplejavamail.mailer.internal;

import jakarta.mail.Authenticator;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Finds out which connection facts Angus actually exposes before the capability probe promises them publicly.
 * These tests use the project's existing TLS fixture and only talk to a scripted loopback peer; no mail is sent.
 */
@Timeout(30)
class SmtpCapabilityProbeCharacterizationTest {
    private static final String GREETING = "220 localhost capability test peer";
    private static final String PLAIN_EHLO = "250-localhost\r\n250-STARTTLS\r\n250-SIZE 1024\r\n250 AUTH LOGIN";
    private static final String TLS_EHLO = "250-localhost\r\n250-SIZE 4096\r\n250-DSN\r\n250 AUTH PLAIN";

    @Test
    void supportedHooksRetainGreetingAndEhloAfterQuitOverwritesTheLastResponse() throws Exception {
        final String extensions = "250-localhost\r\n250-size 123\r\n250-SIZE 456\r\n250-X-EXPERIMENTAL one two\r\n"
                + "250-\r\n250-DSN\r\n250-SMTPUTF8\r\n250-8BITMIME\r\n250-PIPELINING\r\n250-CHUNKING\r\n"
                + "250-BINARYMIME\r\n250 REQUIRETLS";
        withPeer(session(false), false, peer -> {
            peer.greet(extensions);
            peer.quit();
        }, (transport, port) -> {
            transport.connect("localhost", port, null, null);
            assertThat(transport.greeting).isEqualTo(GREETING + "\n");
            assertThat(transport.ehloReplies).containsExactly(extensions.replace("\r\n", "\n") + "\n");
            assertThat(transport.encryptedEhlo).containsExactly(false);
            assertThat(transport.getExtensionParameter("SIZE")).isEqualTo("456");
            assertThat(transport.getExtensionParameter("X-EXPERIMENTAL")).isEqualTo("one two");
            assertThat(transport.supportsExtension("DSN")).isTrue();
            transport.close();
            assertThat(transport.getLastServerResponse()).isEqualTo("221 bye\n");
            assertThat(transport.greeting).isEqualTo(GREETING + "\n");
            assertThat(transport.ehloReplies).hasSize(1);
        });
    }

    @Test
    void failedEhloFallsBackToHeloWithoutInventingExtensions() throws Exception {
        withPeer(session(false), false, peer -> {
            peer.greet("500 EHLO unsupported");
            peer.expect("HELO probe.example.test");
            peer.reply("250 hello");
            peer.quit();
        }, (transport, port) -> {
            transport.connect("localhost", port, null, null);
            assertThat(transport.ehloSucceeded).containsExactly(false);
            assertThat(transport.supportsExtension("SIZE")).isFalse();
        });
    }

    @Test
    void successfulStartTlsExposesTwoDistinctEhloSnapshots() throws Exception {
        final Session session = session(true);
        session.getProperties().setProperty("mail.smtp.starttls.required", "true");
        withPeer(session, false, peer -> {
            peer.startTls(TLS_EHLO);
            peer.quit();
        }, (transport, port) -> {
            transport.connect("localhost", port, null, null);
            assertThat(transport.startTlsAttempted).isTrue();
            assertThat(transport.startTlsCompleted).isTrue();
            assertThat(transport.encryptedEhlo).containsExactly(false, true);
            assertThat(transport.ehloSucceeded).containsExactly(true, true);
            assertThat(transport.ehloReplies).containsExactly(
                    PLAIN_EHLO.replace("\r\n", "\n") + "\n", TLS_EHLO.replace("\r\n", "\n") + "\n");
            assertThat(transport.getExtensionParameter("SIZE")).isEqualTo("4096");
            assertThat(transport.getExtensionParameter("AUTH")).isEqualTo("PLAIN");
            assertThat(transport.supportsExtension("STARTTLS")).isFalse();
        });
    }

    @Test
    void failedPostTlsEhloLeavesAngusPreTlsExtensionsBehind() throws Exception {
        final Session session = session(true);
        session.getProperties().setProperty("mail.smtp.starttls.required", "true");
        withPeer(session, false, peer -> {
            peer.startTls("500 EHLO unavailable after TLS");
            peer.quit();
        }, (transport, port) -> {
            transport.connect("localhost", port, null, null);
            assertThat(transport.startTlsCompleted).isTrue();
            assertThat(transport.encryptedEhlo).containsExactly(false, true);
            assertThat(transport.ehloSucceeded).containsExactly(true, false);
            // Characterization, not the desired probe behavior: never present this stale map as post-TLS facts.
            assertThat(transport.getExtensionParameter("SIZE")).isEqualTo("1024");
            assertThat(transport.supportsExtension("STARTTLS")).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void startTlsUnavailableOrRefusedPreservesPreTlsFactsAndClosesTheConnection(final boolean offered) throws Exception {
        final Session session = session(false);
        session.getProperties().setProperty("mail.smtp.starttls.required", "true");
        withPeer(session, false, peer -> {
            peer.greet(offered ? PLAIN_EHLO : "250-localhost\r\n250 SIZE 1024");
            if (offered) {
                peer.expect("STARTTLS");
                peer.reply("454 TLS temporarily unavailable");
            }
            peer.expectClosed();
        }, (transport, port) -> {
            assertThatThrownBy(() -> transport.connect("localhost", port, null, null)).isInstanceOf(MessagingException.class);
            assertThat(transport.greeting).isEqualTo(GREETING + "\n");
            assertThat(transport.startTlsAttempted).isEqualTo(offered);
            assertThat(transport.startTlsCompleted).isFalse();
            assertThat(transport.encryptedEhlo).containsExactly(false);
        });
    }

    @Test
    void untrustedCertificateFailsBeforePostTlsEhlo() throws Exception {
        final Session session = session(false);
        final KeyStore emptyTrustStore = KeyStore.getInstance("JKS");
        emptyTrustStore.load(null, null);
        session.getProperties().put("mail.smtp.ssl.socketFactory", tlsContext(emptyTrustStore).getSocketFactory());
        session.getProperties().setProperty("mail.smtp.starttls.required", "true");
        withPeer(session, false, peer -> {
            peer.greet(PLAIN_EHLO);
            peer.expect("STARTTLS");
            peer.reply("220 begin TLS");
            assertThatThrownBy(peer::upgradeToTls).isInstanceOf(IOException.class);
        }, (transport, port) -> {
            assertThatThrownBy(() -> transport.connect("localhost", port, null, null)).isInstanceOf(MessagingException.class);
            assertThat(transport.startTlsAttempted).isTrue();
            assertThat(transport.startTlsCompleted).isFalse();
            assertThat(transport.encryptedEhlo).containsExactly(false);
        });
    }

    @Test
    void implicitTlsHasOnlyAnEncryptedEhloAndNoStartTlsAttempt() throws Exception {
        final Session session = session(false);
        session.getProperties().setProperty("mail.smtp.ssl.enable", "true");
        // This case checks hook ordering, not certificate trust. Local mail shields can re-sign even loopback TLS.
        // Keep hostname checking enabled; the separate untrusted-certificate case deliberately keeps strict trust.
        session.getProperties().setProperty("mail.smtp.ssl.trust", "*");
        withPeer(session, true, peer -> {
            peer.greet(TLS_EHLO);
            peer.quit();
        }, (transport, port) -> {
            transport.connect("localhost", port, null, null);
            assertThat(transport.isSSL()).isTrue();
            assertThat(transport.encryptedEhlo).containsExactly(true);
            assertThat(transport.startTlsAttempted).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuredHostnameVerifierGetsTlsFactsAndKeepsItsVeto(final boolean accepted) throws Exception {
        final Session session = session(true);
        final AtomicReference<SSLSession> negotiated = new AtomicReference<>();
        final HostnameVerifier verifier = (host, tlsSession) -> {
            assertThat(host).isEqualTo("localhost");
            negotiated.set(tlsSession);
            return accepted;
        };
        session.getProperties().setProperty("mail.smtp.starttls.required", "true");
        session.getProperties().put("mail.smtp.ssl.hostnameverifier", verifier);
        withPeer(session, false, peer -> {
            peer.greet(PLAIN_EHLO);
            peer.expect("STARTTLS");
            peer.reply("220 begin TLS");
            peer.upgradeToTls();
            if (accepted) {
                peer.expect("EHLO probe.example.test");
                peer.reply(TLS_EHLO);
                peer.quit();
            } else {
                peer.expectClosed();
            }
        }, (transport, port) -> {
            if (accepted) {
                transport.connect("localhost", port, null, null);
            } else {
                assertThatThrownBy(() -> transport.connect("localhost", port, null, null)).isInstanceOf(MessagingException.class);
            }
            assertThat(negotiated.get()).isNotNull();
            assertThat(negotiated.get().getProtocol()).startsWith("TLS");
            assertThat(negotiated.get().getCipherSuite()).doesNotContain("NULL");
            assertThat(negotiated.get().getPeerCertificates()).isNotEmpty();
            assertThat(transport.startTlsCompleted).isEqualTo(accepted);
            assertThat(session.getProperties().get("mail.smtp.ssl.hostnameverifier")).isSameAs(verifier);
        });
    }

    @Test
    void supplyingCredentialsCanAuthenticateEvenWhenAuthPropertyIsFalse() throws Exception {
        final Session session = session(false);
        session.getProperties().setProperty("mail.smtp.auth", "false");
        withPeer(session, false, peer -> {
            peer.greet(PLAIN_EHLO);
            peer.authenticate();
            peer.quit();
        }, (transport, port) -> {
            transport.connect("localhost", port, "probe-user", "probe-secret");
            assertThat(transport.ehloReplies).allSatisfy(reply -> assertThat(reply).doesNotContain("probe-user", "probe-secret", "334"));
            assertThat(transport.greeting).isEqualTo(GREETING + "\n");
        });
    }

    @Test
    void nullCredentialsCanStillInvokeTheCallerSessionAuthenticator() throws Exception {
        final Properties properties = session(false).getProperties();
        properties.setProperty("mail.smtp.auth", "true");
        final AtomicInteger authenticationRequests = new AtomicInteger();
        final Session session = Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                authenticationRequests.incrementAndGet();
                return new PasswordAuthentication("probe-user", "probe-secret");
            }
        });
        withPeer(session, false, peer -> {
            peer.greet(PLAIN_EHLO);
            peer.authenticate();
            peer.quit();
        }, (transport, port) -> {
            transport.connect("localhost", port, null, null);
            assertThat(authenticationRequests).hasValue(1);
        });
    }

    @Test
    void successfulConnectDoesNotProveRequestedAuthenticationHappened() throws Exception {
        final Session session = session(false);
        session.getProperties().setProperty("mail.smtp.auth", "true");
        withPeer(session, false, peer -> {
            peer.greet("250-localhost\r\n250 SIZE 1024");
            peer.quit();
        }, (transport, port) -> {
            transport.connect("localhost", port, "probe-user", "probe-secret");
            assertThat(transport.supportsExtension("AUTH")).isFalse();
        });
    }

    static Session session(final boolean trustTestCertificate) throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.smtp.localhost", "probe.example.test");
        properties.setProperty("mail.smtp.connectiontimeout", "10000");
        properties.setProperty("mail.smtp.timeout", "10000");
        properties.setProperty("mail.smtp.socketFactory.fallback", "false");
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        if (trustTestCertificate) {
            properties.put("mail.smtp.ssl.socketFactory", tlsContext(testKeyStore()).getSocketFactory());
        }
        return Session.getInstance(properties);
    }

    private static KeyStore testKeyStore() throws Exception {
        final KeyStore keyStore = KeyStore.getInstance("JKS");
        try (InputStream source = requireNonNull(SmtpCapabilityProbeCharacterizationTest.class.getResourceAsStream("/smtp_test_server.jks"))) {
            keyStore.load(source, "changeit".toCharArray());
        }
        return keyStore;
    }

    static SSLContext tlsContext(final KeyStore trustStore) throws Exception {
        final KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(testKeyStore(), "changeit".toCharArray());
        final TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trustStore);
        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

    private static void withPeer(final Session session, final boolean implicitTls, final ServerScript script,
                                 final ClientScript assertions) throws Exception {
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("localhost"))) {
            server.setSoTimeout(10000);
            final CompletableFuture<Void> serving = CompletableFuture.runAsync(() -> {
                try (Conversation peer = new Conversation(server.accept())) {
                    if (implicitTls) {
                        peer.upgradeToTls();
                    }
                    script.run(peer);
                } catch (Exception failure) {
                    throw new AssertionError("Scripted SMTP peer failed", failure);
                }
            }, worker);
            try (ObservedTransport transport = new ObservedTransport(session)) {
                assertions.run(transport, server.getLocalPort());
            } catch (Exception | AssertionError failure) {
                try {
                    serving.get(15, SECONDS);
                } catch (Exception peerFailure) {
                    failure.addSuppressed(peerFailure);
                }
                throw failure;
            }
            serving.get(15, SECONDS);
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(15, SECONDS)).isTrue();
        }
    }

    @FunctionalInterface
    private interface ServerScript {
        void run(Conversation peer) throws Exception;
    }

    @FunctionalInterface
    private interface ClientScript {
        void run(ObservedTransport transport, int port) throws Exception;
    }

    /** Stores only greeting/EHLO replies, never the authentication exchange or a debug transcript. */
    private static final class ObservedTransport extends SMTPTransport {
        private String greeting;
        private final List<String> ehloReplies = new ArrayList<>();
        private final List<Boolean> encryptedEhlo = new ArrayList<>();
        private final List<Boolean> ehloSucceeded = new ArrayList<>();
        private boolean startTlsAttempted;
        private boolean startTlsCompleted;

        private ObservedTransport(final Session session) {
            super(session, new URLName("smtp", null, -1, null, null, null));
        }

        @Override
        protected int readServerResponse() throws MessagingException {
            final int code = super.readServerResponse();
            if (greeting == null) {
                greeting = getLastServerResponse();
            }
            return code;
        }

        @Override
        protected boolean ehlo(final String domain) throws MessagingException {
            final boolean succeeded = super.ehlo(domain);
            ehloReplies.add(getLastServerResponse());
            encryptedEhlo.add(isSSL());
            ehloSucceeded.add(succeeded);
            return succeeded;
        }

        @Override
        protected void startTLS() throws MessagingException {
            startTlsAttempted = true;
            super.startTLS();
            startTlsCompleted = true;
        }
    }

    static final class Conversation implements AutoCloseable {
        private Socket socket;
        private BufferedReader reader;
        private PrintWriter writer;

        Conversation(final Socket socket) throws IOException {
            this.socket = socket;
            socket.setSoTimeout(10000);
            useCurrentSocketStreams();
        }

        void greet(final String ehloReply) throws IOException {
            reply(GREETING);
            expect("EHLO probe.example.test");
            reply(ehloReply);
        }

        void startTls(final String ehloReply) throws Exception {
            greet(PLAIN_EHLO);
            expect("STARTTLS");
            reply("220 begin TLS");
            upgradeToTls();
            expect("EHLO probe.example.test");
            reply(ehloReply);
        }

        void upgradeToTls() throws Exception {
            final SSLSocket encrypted = (SSLSocket) tlsContext(testKeyStore()).getSocketFactory()
                    .createSocket(socket, "localhost", socket.getPort(), true);
            socket = encrypted;
            encrypted.setUseClientMode(false);
            // TLS 1.2 makes rejection visible during this fixture's handshake instead of a later application read.
            encrypted.setEnabledProtocols(new String[]{"TLSv1.2"});
            encrypted.startHandshake();
            useCurrentSocketStreams();
        }

        void authenticate() throws IOException {
            expect("AUTH LOGIN");
            reply("334 VXNlcm5hbWU6");
            assertThat(reader.readLine()).isNotBlank();
            reply("334 UGFzc3dvcmQ6");
            assertThat(reader.readLine()).isNotBlank();
            reply("235 authenticated");
        }

        void quit() throws IOException {
            expect("QUIT");
            reply("221 bye");
            expectClosed();
        }

        void expect(final String command) throws IOException {
            assertThat(reader.readLine()).isEqualTo(command);
        }

        void expectClosed() throws IOException {
            assertThat(reader.readLine()).as("peer must close without sending mail").isNull();
        }

        void reply(final String response) {
            writer.print(response + "\r\n");
            writer.flush();
            assertThat(writer.checkError()).as("SMTP response written").isFalse();
        }

        private void useCurrentSocketStreams() throws IOException {
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            writer = new PrintWriter(socket.getOutputStream(), false, StandardCharsets.US_ASCII);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
