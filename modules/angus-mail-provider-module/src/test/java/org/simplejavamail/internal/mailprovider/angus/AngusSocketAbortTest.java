package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Safety gate for the supported Angus socket-factory hook, before making any public cancellation promises.
 * The peer deliberately withholds replies; socket/read timeouts are much longer than the asserted abort time.
 */
class AngusSocketAbortTest {

    @ParameterizedTest
    @ValueSource(strings = {"GREETING", "EHLO", "AUTH", "MAIL FROM:", "RCPT TO:", "DATA", ".", "QUIT",
            "STARTTLS_HANDSHAKE", "IMPLICIT_TLS_HANDSHAKE"})
    void capturedSocketAbortsBlockedAngusWithoutItsMonitor(final String blockedPhase) throws Exception {
        final ExecutorService workers = Executors.newCachedThreadPool();
        final CountDownLatch awaitingReply = new CountDownLatch(1);
        final CountDownLatch releasePeer = new CountDownLatch(1);
        final boolean implicitTls = blockedPhase.equals("IMPLICIT_TLS_HANDSHAKE");
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            final CompletableFuture<Void> peer = CompletableFuture.runAsync(
                    () -> serveBlockedTransaction(server, blockedPhase, awaitingReply, releasePeer), workers);
            final Properties properties = transportProperties(implicitTls);
            if (blockedPhase.equals("STARTTLS_HANDSHAKE")) {
                properties.setProperty("mail.smtp.starttls.required", "true");
            }
            if (blockedPhase.equals("AUTH")) {
                properties.setProperty("mail.smtp.auth", "true");
                properties.setProperty("mail.smtp.auth.mechanisms", "LOGIN");
            }
            final Session session = Session.getInstance(properties);
            MailTransportLifecycleResolver.configureOwnedSession(session);
            final SMTPTransport transport = (SMTPTransport) session.getTransport();
            final Runnable abort = MailTransportLifecycleResolver.findAbortAction(transport).orElseThrow(AssertionError::new);
            final CompletableFuture<Throwable> send = CompletableFuture.supplyAsync(() -> {
                try {
                    transport.connect(server.getInetAddress().getHostAddress(), server.getLocalPort(),
                            blockedPhase.equals("AUTH") ? "user" : null, blockedPhase.equals("AUTH") ? "password" : null);
                    final MimeMessage message = message(session);
                    transport.sendMessage(message, message.getAllRecipients());
                    transport.close();
                    return null;
                } catch (Exception failure) {
                    return failure;
                }
            }, workers);
            try {
                assertThat(awaitingReply.await(5, TimeUnit.SECONDS)).as(blockedPhase).isTrue();
                CompletableFuture.runAsync(abort, workers).get(2, TimeUnit.SECONDS);
                final Throwable failure = send.get(2, TimeUnit.SECONDS);
                if (!blockedPhase.equals("QUIT")) {
                    assertThat(failure).as(blockedPhase).isNotNull();
                }
            } finally {
                abort.run();
                releasePeer.countDown();
                send.get(5, TimeUnit.SECONDS);
                transport.close();
            }
            peer.get(5, TimeUnit.SECONDS);
        } finally {
            releasePeer.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(7, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void abortBeforeCreationClosesTheLateSocket() throws Exception {
        final Session session = Session.getInstance(transportProperties(false));
        MailTransportLifecycleResolver.configureOwnedSession(session);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SMTPTransport transport = (SMTPTransport) session.getTransport()) {
            MailTransportLifecycleResolver.findAbortAction(transport).orElseThrow(AssertionError::new).run();
            assertThatThrownBy(() -> transport.connect(server.getInetAddress().getHostAddress(), server.getLocalPort(), null, null))
                    .isInstanceOf(MessagingException.class);
            server.setSoTimeout(100);
            assertThatThrownBy(server::accept).isInstanceOf(java.net.SocketTimeoutException.class);
        }
    }

    @Test
    void abortInterruptsSocksNegotiationBeforeSmtpCanConnect() throws Exception {
        final ExecutorService workers = Executors.newCachedThreadPool();
        final CountDownLatch negotiating = new CountDownLatch(1);
        final CountDownLatch releasePeer = new CountDownLatch(1);
        try (ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            proxy.setSoTimeout(5000);
            final CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
                try (Socket socket = proxy.accept()) {
                    socket.setSoTimeout(5000);
                    assertThat(socket.getInputStream().read()).isEqualTo(5);
                    negotiating.countDown();
                    assertThat(releasePeer.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }, workers);
            final Properties properties = transportProperties(false);
            properties.setProperty("mail.smtp.socks.host", proxy.getInetAddress().getHostAddress());
            properties.setProperty("mail.smtp.socks.port", Integer.toString(proxy.getLocalPort()));
            final Session session = Session.getInstance(properties);
            MailTransportLifecycleResolver.configureOwnedSession(session);
            final SMTPTransport transport = (SMTPTransport) session.getTransport();
            final Runnable abort = MailTransportLifecycleResolver.findAbortAction(transport).orElseThrow(AssertionError::new);
            final CompletableFuture<Throwable> connect = CompletableFuture.supplyAsync(() -> {
                try {
                    transport.connect("127.0.0.1", 2525, null, null);
                    return null;
                } catch (Exception failure) {
                    return failure;
                }
            }, workers);
            try {
                assertThat(negotiating.await(5, TimeUnit.SECONDS)).isTrue();
                CompletableFuture.runAsync(abort, workers).get(2, TimeUnit.SECONDS);
                assertThat(connect.get(2, TimeUnit.SECONDS)).isNotNull();
            } finally {
                releasePeer.countDown();
                abort.run();
                connect.get(5, TimeUnit.SECONDS);
                transport.close();
            }
            peer.get(5, TimeUnit.SECONDS);
        } finally {
            releasePeer.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(7, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static Properties transportProperties(final boolean implicitTls) {
        final String prefix = implicitTls ? "mail.smtps" : "mail.smtp";
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", implicitTls ? "smtps" : "smtp");
        properties.setProperty(prefix + ".connectiontimeout", "30000");
        properties.setProperty(prefix + ".timeout", "30000");
        return properties;
    }

    private static MimeMessage message(final Session session) throws MessagingException {
        final MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("sender@example.org"));
        message.setRecipients(Message.RecipientType.TO, "receiver@example.org");
        message.setText("Socket abort safety gate");
        message.saveChanges();
        return message;
    }

    private static void serveBlockedTransaction(final ServerSocket server, final String blockedPhase,
            final CountDownLatch awaitingReply, final CountDownLatch releasePeer) {
        try (Socket socket = server.accept();
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.US_ASCII)) {
            socket.setSoTimeout(5000);
            if (blockedPhase.equals("GREETING") || blockedPhase.equals("IMPLICIT_TLS_HANDSHAKE")) {
                if (blockedPhase.equals("IMPLICIT_TLS_HANDSHAKE")) {
                    assertThat(socket.getInputStream().read()).as("TLS ClientHello").isEqualTo(22);
                }
                awaitAbort(awaitingReply, releasePeer);
                return;
            }
            reply(writer, "220 abort test peer");
            boolean readingContent = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if ((!readingContent || line.equals(".")) && line.startsWith(blockedPhase)) {
                    awaitAbort(awaitingReply, releasePeer);
                    return;
                }
                if (readingContent && !line.equals(".")) {
                    continue;
                }
                if (line.startsWith("EHLO")) {
                    reply(writer, "250-abort test peer\r\n250-STARTTLS\r\n250 AUTH LOGIN");
                } else if (line.equals("STARTTLS")) {
                    reply(writer, "220 begin TLS");
                    assertThat(socket.getInputStream().read()).as("TLS ClientHello").isEqualTo(22);
                    awaitAbort(awaitingReply, releasePeer);
                    return;
                } else if (line.equals("DATA")) {
                    readingContent = true;
                    reply(writer, "354 continue");
                } else {
                    readingContent = false;
                    reply(writer, line.equals("QUIT") ? "221 bye" : "250 accepted");
                    if (line.equals("QUIT")) {
                        return;
                    }
                }
            }
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void reply(final PrintWriter writer, final String response) {
        writer.print(response + "\r\n");
        writer.flush();
    }

    private static void awaitAbort(final CountDownLatch awaitingReply, final CountDownLatch releasePeer) throws InterruptedException {
        awaitingReply.countDown();
        assertThat(releasePeer.await(5, TimeUnit.SECONDS)).isTrue();
    }

}
