package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
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

/** Proves why cancelling a future or calling Angus close cannot implement an in-flight SMTP abort. */
class AngusCancellationBoundaryTest {

    @ParameterizedTest
    @ValueSource(strings = {"MAIL FROM:", "."})
    void closeWaitsBehindSendingAndFutureCancellationDoesNotStopAcceptance(final String blockedCommand) throws Exception {
        final CountDownLatch awaitingReply = new CountDownLatch(1);
        final CountDownLatch releaseReply = new CountDownLatch(1);
        final CountDownLatch sendReturned = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final ExecutorService workers = Executors.newCachedThreadPool();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            final CompletableFuture<Void> peer = CompletableFuture.runAsync(
                    () -> serveTransaction(server, blockedCommand, awaitingReply, releaseReply), workers);
            final Properties properties = new Properties();
            properties.setProperty("mail.smtp.timeout", "5000");
            properties.setProperty("mail.smtp.connectiontimeout", "5000");
            final Session session = Session.getInstance(properties);
            try (SMTPTransport transport = new SMTPTransport(session, null)) {
                transport.connect(server.getInetAddress().getHostAddress(), server.getLocalPort(), null, null);
                final MimeMessage message = new MimeMessage(session);
                message.setFrom(new InternetAddress("sender@example.org"));
                message.setRecipients(Message.RecipientType.TO, "receiver@example.org");
                message.setText("Cancellation boundary test");
                message.saveChanges();
                final CompletableFuture<Void> send = CompletableFuture.runAsync(() -> {
                    try {
                        transport.sendMessage(message, message.getAllRecipients());
                        sendReturned.countDown();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }, workers);
                assertThat(awaitingReply.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(send.cancel(true)).isTrue();
                final CompletableFuture<Thread> closeThread = new CompletableFuture<>();
                final CompletableFuture<Void> close = CompletableFuture.runAsync(() -> {
                    closeThread.complete(Thread.currentThread());
                    closeEntered.countDown();
                    try {
                        transport.close();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }, workers);
                assertThat(closeEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertMonitorBlocked(closeThread.get(5, TimeUnit.SECONDS));
                assertThat(close).isNotDone();

                releaseReply.countDown();
                assertThat(sendReturned.await(5, TimeUnit.SECONDS))
                        .as("SMTP still returns success after the caller-facing future was cancelled").isTrue();
                close.get(5, TimeUnit.SECONDS);
                peer.get(5, TimeUnit.SECONDS);
                assertThat(send).isCancelled();
            } finally {
                releaseReply.countDown();
            }
        } finally {
            releaseReply.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(7, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void assertMonitorBlocked(final Thread thread) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(thread.getState()).isEqualTo(Thread.State.BLOCKED);
    }

    private static void serveTransaction(final ServerSocket server, final String blockedCommand,
                                         final CountDownLatch awaitingReply, final CountDownLatch releaseReply) {
        try (Socket socket = server.accept();
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.US_ASCII)) {
            socket.setSoTimeout(5000);
            writer.print("220 test peer\r\n");
            writer.flush();
            boolean readingContent = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(blockedCommand)) {
                    awaitingReply.countDown();
                    assertThat(releaseReply.await(5, TimeUnit.SECONDS)).isTrue();
                }
                if (readingContent && !line.equals(".")) {
                    continue;
                }
                if (line.equals("DATA")) {
                    readingContent = true;
                    writer.print("354 continue\r\n");
                } else if (line.equals("QUIT")) {
                    writer.print("221 bye\r\n");
                    writer.flush();
                    return;
                } else {
                    readingContent = false;
                    writer.print("250 accepted\r\n");
                }
                writer.flush();
            }
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
