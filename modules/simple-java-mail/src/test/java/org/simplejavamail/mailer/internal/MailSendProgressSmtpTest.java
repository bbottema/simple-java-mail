package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.*;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import testutil.smtp.RecordingSmtpServer;
import testutil.smtp.ScriptedSmtpServer;

import java.time.Duration;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.mailer.internal.MailSendProgressDispatcherTest.take;

/** Public sending contracts against a real loopback SMTP stream; reporting cannot dictate protocol or completion. */
@Timeout(30)
class MailSendProgressSmtpTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    private MailerRegularBuilder<?> builder(final int port) {
        return mail.mailerBuilder().withSMTPServer("localhost", port).withTransportStrategy(TransportStrategy.SMTP)
                .withOpportunisticTLS(false).withSmtpClientHostname("probe.example.test").withSessionTimeout(20000)
                .withProperty("mail.host", "localhost")
                .withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).withThreadPoolSize(1);
    }

    private Email email() {
        return mail.emailBuilder().startingBlank().from("sender@example.test")
                .withRecipients(mail.recipientBuilder().withAddress("recipient@example.test").withType(jakarta.mail.Message.RecipientType.TO).build())
                .withSubject("progress test").withPlainText("First line\n.dot\nJosé").buildEmail();
    }

    @ParameterizedTest
    @ValueSource(ints = {64, 8192})
    void bdatReportsInputBytesAndWaitsOnlyAtTheFinalReply(final int chunkSize) throws Exception {
        final CountDownLatch waiting = new CountDownLatch(1);
        final CountDownLatch serverReceived = new CountDownLatch(1);
        final CountDownLatch allowReply = new CountDownLatch(1);
        final AtomicReference<MailSendProgress> atFinalReply = new AtomicReference<>();
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        final ByteArrayOutputStream baseline = new ByteArrayOutputStream();
        final AtomicInteger connections = new AtomicInteger();
        final List<MailSendProgressStage> reportedStages = new CopyOnWriteArrayList<>();
        final byte[] exactContent = ("From: sender@example.test\r\nTo: receiver@example.test\r\nMessage-ID: <exact@example.test>\r\n"
                + "Subject: exact upload\r\n\r\n.dot\r\n" + "unchanged body\r\n".repeat(20)).getBytes(StandardCharsets.US_ASCII);
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(2, StandardCharsets.ISO_8859_1, peer -> {
            final boolean observed = connections.incrementAndGet() == 2;
            peer.greet("250-localhost\r\n250-SIZE 1000000\r\n250 CHUNKING");
            String command;
            while ((command = peer.readLine()) != null) {
                if (command.startsWith("BDAT ")) {
                    final String[] parts = command.split(" ");
                    (observed ? received : baseline).write(peer.readOctets(Integer.parseInt(parts[1])));
                    if (observed && command.endsWith(" LAST")) {
                        serverReceived.countDown();
                        await(allowReply);
                    }
                    peer.reply("250 queued");
                } else if (command.equals("QUIT")) {
                    peer.reply("221 bye");
                    return;
                } else {
                    assertThat(command).matches("MAIL FROM:.*|RCPT TO:.*|NOOP|RSET");
                    peer.reply("250 ok");
                }
            }
        }); Mailer mailer = builder(server.port()).withProperty("mail.smtp.chunksize", chunkSize).withMailSendProgressObserver(event -> {
            reportedStages.add(event.getStage());
            if (event.getStage() == MailSendProgressStage.WAITING_FOR_RESPONSE) {
                atFinalReply.set(event);
                waiting.countDown();
            }
        }).buildMailer()) {
            try (Mailer unobserved = builder(server.port()).withProperty("mail.smtp.chunksize", chunkSize).buildMailer()) {
                unobserved.sync().sendMail(SmtpContentNegotiationCharacterizationTest.exactEmail(exactContent));
            }
            try {
                final MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(SmtpContentNegotiationCharacterizationTest.exactEmail(exactContent));
                assertThat(waiting.await(15, SECONDS)).isTrue();
                // Angus 2.0.5 also dot-escapes BDAT input. Progress must not change that baseline or count its extra framing byte.
                assertThat(serverReceived.await(5, SECONDS)).isTrue();
                assertThat(received.toByteArray()).isEqualTo(baseline.toByteArray());
                assertThat(atFinalReply.get().getBytesWritten()).contains((long) exactContent.length);
                assertThat(reportedStages).doesNotContain(MailSendProgressStage.WAITING_FOR_DATA_PERMISSION);
                assertThat(atFinalReply.get().getTotalBytes()).isEqualTo(atFinalReply.get().getBytesWritten());
                assertThat(send.getCompletion().isDone()).isFalse();
                allowReply.countDown();
                send.getCompletion().get(5, SECONDS);
            } finally {
                allowReply.countDown();
            }
        }
    }

    @Test
    void preparationFailureAndLoggingOnlyFinishWithoutFictionalSmtpWork() throws Exception {
        final BlockingQueue<MailSendProgress> progress = new LinkedBlockingQueue<>();
        final List<MailSendOutcome> results = new CopyOnWriteArrayList<>();
        try (Mailer mailer = builder(25).withTransportModeLoggingOnly(true).withMailSendProgressObserver(progress::add)
                .withMailSendResultObserver(results::add).buildMailer()) {
            assertThatThrownBy(() -> mailer.sync().sendMail(mail.emailBuilder().startingBlank().buildEmail())).isInstanceOf(RuntimeException.class);
            final MailSendProgress failed = awaitFinished(progress);
            assertThat(failed.getSubmissionAttempt()).isZero();
            assertThat(failed.getBytesWritten()).isEmpty();
            assertThat(results.get(0).isSuccessful()).isFalse();
            mailer.sync().sendMail(email());
            final MailSendProgress logged = awaitFinished(progress);
            assertThat(logged.getSubmissionAttempt()).isZero();
            assertThat(logged.getObservationId()).isNotEqualTo(failed.getObservationId());
            assertThat(results.get(1).isLoggingOnly()).isTrue();
        }
    }

    @Test
    void sharedScopesObserveOnlyReachedEmailsAndReuseTheirConnection() throws Exception {
        final BlockingQueue<MailSendProgress> progress = new LinkedBlockingQueue<>();
        final List<MailSendOutcome> results = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer server = new RecordingSmtpServer(); Mailer mailer = builder(server.port())
                .withMailSendProgressObserver(progress::add).withMailSendResultObserver(results::add).buildMailer()) {
            mailer.sync().sendMailsInSimpleBatch(Arrays.asList(email(), email()));
            assertThat(server.connectionCount()).isEqualTo(1);
            mailer.withOpenConnection(sender -> sender.sendMail(email()));
            final java.util.Set<java.util.UUID> finished = new java.util.HashSet<>();
            for (int i = 0; i < 3; i++) { finished.add(awaitFinished(progress).getObservationId()); }
            assertThat(finished).hasSize(3);
            assertThat(results).hasSize(3);
            assertThat(server.messages()).hasSize(3);
            assertThat(server.connectionCount()).isEqualTo(2); // The separate open scope owns its own connection; the batch did not open one per email.
        }
    }

    private static MailSendProgress awaitFinished(final BlockingQueue<MailSendProgress> progress) throws InterruptedException {
        MailSendProgress next;
        do {
            next = progress.poll(5, SECONDS);
            assertThat(next).isNotNull();
        } while (next.getStage() != MailSendProgressStage.FINISHED);
        return next;
    }

    @Test
    void closingTheMailerDiscardsUnstartedReportingAndDoesNotOwnTheApplicationExecutor() throws Exception {
        final BlockingQueue<Runnable> executor = new LinkedBlockingQueue<>();
        final AtomicInteger callbacks = new AtomicInteger();
        try (RecordingSmtpServer server = new RecordingSmtpServer()) {
            final Mailer mailer = builder(server.port()).withMailSendProgressObserver(ignored -> callbacks.incrementAndGet(), executor::add).buildMailer();
            try {
                mailer.sync().sendMail(email());
                final Runnable waiting = take(executor);
                mailer.close();
                waiting.run();
                assertThat(callbacks).hasValue(0);
                assertThat(executor).isEmpty();
            } finally {
                mailer.close();
            }
        }
    }

    @Test
    void progressCallbackCanCloseItsMailerAfterSendingHasStarted() throws Exception {
        final AtomicReference<Mailer> reference = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch closed = new CountDownLatch(1);
        try (RecordingSmtpServer server = new RecordingSmtpServer(); Mailer mailer = builder(server.port()).withMailSendProgressObserver(event -> {
            if (closed.getCount() != 0 && event.getStage() != MailSendProgressStage.WAITING_FOR_EXECUTION) {
                try { reference.get().close(); } catch (Exception problem) { failure.set(problem); }
                closed.countDown();
            }
        }).buildMailer()) {
            reference.set(mailer);
            mailer.async().sendMail(email()).getCompletion().get(10, SECONDS);
            assertThat(closed.await(10, SECONDS)).isTrue();
            assertThat(failure).hasNullValue();
        }
    }

    @Test
    void uploadCompletionDoesNotImplyAcceptanceAndBothObserversShareOneId() throws Exception {
        final CountDownLatch waiting = new CountDownLatch(1);
        final CountDownLatch allowReply = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final List<MailSendProgress> progress = new CopyOnWriteArrayList<>();
        final List<MailSendOutcome> results = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250-localhost\r\n250 SIZE 1000000");
            connection.acceptMessages(peer, ignored -> {
                await(allowReply);
                return "250 queued";
            });
        }); Mailer mailer = builder(server.port()).withMailSendProgressObserver(event -> {
            progress.add(event);
            if (event.getStage() == MailSendProgressStage.WAITING_FOR_RESPONSE) { waiting.countDown(); }
            if (event.getStage() == MailSendProgressStage.FINISHED) { finished.countDown(); }
        }).withMailSendResultObserver(results::add).buildMailer()) {
            try {
                final MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(email());
                assertThat(waiting.await(15, SECONDS)).isTrue();
                assertThat(send.getCompletion().isDone()).isFalse();
                final MailSendProgress uploading = progress.get(progress.size() - 1);
                assertThat(uploading.getSubmissionAttempt()).isEqualTo(1);
                assertThat(uploading.getBytesWritten()).isPresent();
                assertThat(uploading.getTotalBytes()).isEqualTo(uploading.getBytesWritten());
                allowReply.countDown();
                final MailSubmissionReceipt receipt = send.getCompletion().get(5, SECONDS);
                assertThat(finished.await(5, SECONDS)).isTrue();
                assertThat(results).hasSize(1);
                assertThat(results.get(0).getSubmissionReceipt()).containsSame(receipt);
                assertThat(progress).extracting(MailSendProgress::getObservationId).containsOnly(results.get(0).getObservationId());
                assertThat(progress).extracting(MailSendProgress::getSequence).isSorted().doesNotHaveDuplicates();
            } finally {
                allowReply.countDown();
            }
        }
    }

    @Test
    void aRetryResetsCountersButKeepsItsObservationAndReusesIdenticalContent() throws Exception {
        final AtomicInteger submissions = new AtomicInteger();
        final CountDownLatch waiting = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final List<MailSendProgress> progress = new CopyOnWriteArrayList<>();
        final List<MailSendOutcome> results = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250-localhost\r\n250 SIZE 1000000");
            connection.acceptMessages(peer, ignored -> submissions.incrementAndGet() == 1 ? "451 retry later" : "250 queued");
        }); Mailer mailer = builder(server.port())
                .withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumAttempts(2).withRetryDelay(Duration.ofMillis(500)).build())
                .withMailSendResultObserver(results::add).withMailSendProgressObserver(event -> {
                    progress.add(event);
                    if (event.getStage() == MailSendProgressStage.WAITING_TO_RETRY) { waiting.countDown(); }
                    if (event.getStage() == MailSendProgressStage.FINISHED) { finished.countDown(); }
                }).buildMailer()) {
            final MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(email());
            assertThat(waiting.await(5, SECONDS)).isTrue();
            send.getCompletion().get(5, SECONDS);
            assertThat(finished.await(5, SECONDS)).isTrue();
            assertThat(server.messages()).hasSize(2);
            assertThat(server.messages().get(1)).isEqualTo(server.messages().get(0));
            assertThat(progress.get(progress.size() - 1).getSubmissionAttempt()).isEqualTo(2);
            assertThat(progress).extracting(MailSendProgress::getObservationId).containsOnly(results.get(0).getObservationId());
            assertThat(progress.get(progress.size() - 1).getBytesWritten()).isEqualTo(progress.get(progress.size() - 1).getTotalBytes());
        }
    }

    @Test
    void resultCanArriveBeforeLateProgressAndReportingDoesNotOwnCompletion() throws Exception {
        final BlockingQueue<Runnable> progressExecutor = new LinkedBlockingQueue<>();
        final CountDownLatch progressQueued = new CountDownLatch(1);
        final List<MailSendProgress> progress = new CopyOnWriteArrayList<>();
        final List<MailSendOutcome> results = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            // ADR 0032 permits coalescing straight to FINISHED; capture a nonterminal handoff before letting SMTP complete.
            await(progressQueued);
            connection.greet(peer, "250 localhost");
            connection.acceptMessages(peer);
        });
                Mailer mailer = builder(server.port()).withMailSendResultObserver(results::add)
                        .withMailSendProgressObserver(progress::add, notification -> {
                            progressExecutor.add(notification);
                            progressQueued.countDown();
                        }).buildMailer()) {
            final Email email = email();
            mailer.sync().sendMail(email);
            mailer.async().sendMail(email).getCompletion().get(5, SECONDS);
            assertThat(progress).isEmpty();
            assertThat(results).hasSize(2);
            assertThat(results.get(0).getObservationId()).isNotNull().isNotEqualTo(results.get(1).getObservationId());
            take(progressExecutor).run();
            assertThat(progress).hasSize(1);
            assertThat(progress.get(0).getObservationId()).isEqualTo(results.get(0).getObservationId());
            assertThat(progress.get(0).getStage()).isNotEqualTo(MailSendProgressStage.FINISHED);
        }
    }

    @Test
    void blockedProgressCanCloseItsMailerWithoutDeadlockingSendCompletion() throws Exception {
        final CountDownLatch callbackEntered = new CountDownLatch(1);
        final CountDownLatch releaseCallback = new CountDownLatch(1);
        final CountDownLatch callbackReturned = new CountDownLatch(1);
        try (RecordingSmtpServer server = new RecordingSmtpServer()) {
            final Mailer mailer = builder(server.port()).withMailSendProgressObserver(ignored -> {
                callbackEntered.countDown();
                await(releaseCallback);
                callbackReturned.countDown();
            }, Runnable::run).buildMailer();
            try {
                final MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(email());
                assertThat(callbackEntered.await(5, SECONDS)).isTrue();
                send.getCompletion().get(5, SECONDS);
                mailer.close();
                assertThat(callbackReturned.getCount()).isEqualTo(1);
            } finally {
                releaseCallback.countDown();
                mailer.close();
            }
            assertThat(callbackReturned.await(5, SECONDS)).isTrue();
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertThat(latch.await(20, SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
