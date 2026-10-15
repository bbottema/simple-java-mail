package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.*;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import testutil.smtp.RecordingSmtpServer;
import testutil.smtp.ScriptedSmtpServer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.mailer.internal.MailSendProgressDispatcherTest.take;

/** Progress follows the owning logical operation, not whichever destination or executor happens to perform its next step. */
@Timeout(30)
class MailSendProgressBoundaryTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @Test
    void concurrentPooledSendsAndLaterReuseKeepSeparateObservations() throws Exception {
        final CountDownLatch waiting = new CountDownLatch(2);
        final CountDownLatch allowReplies = new CountDownLatch(1);
        final Set<UUID> waitingIds = ConcurrentHashMap.newKeySet();
        final BlockingQueue<MailSendProgress> progress = new LinkedBlockingQueue<>();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250-localhost\r\n250 SIZE 1000000");
            connection.acceptMessages(peer, ignored -> {
                await(allowReplies);
                return "250 queued";
            });
        }); Mailer mailer = builder(server.port()).withThreadPoolSize(2).withConnectionPoolMaxSize(2)
                .withMailSendResultObserver(outcomes::add).withMailSendProgressObserver(event -> {
                    progress.add(event);
                    if (event.getStage() == MailSendProgressStage.WAITING_FOR_RESPONSE && waitingIds.add(event.getObservationId())) {
                        waiting.countDown();
                    }
                }).buildMailer()) {
            try {
                final Email original = email();
                final MailSend<MailSubmissionReceipt> first = mailer.async().sendMail(original);
                final MailSend<MailSubmissionReceipt> second = mailer.async().sendMail(original);
                assertThat(waiting.await(15, SECONDS)).isTrue();
                assertThat(waitingIds).hasSize(2);
                allowReplies.countDown();
                first.getCompletion().get(5, SECONDS);
                second.getCompletion().get(5, SECONDS);
                mailer.sync().sendMail(original);

                final Map<UUID, Long> sequences = new HashMap<>();
                final Set<UUID> finished = new HashSet<>();
                while (finished.size() < 3) {
                    final MailSendProgress event = take(progress);
                    final Long previous = sequences.put(event.getObservationId(), event.getSequence());
                    if (previous != null) {
                        assertThat(event.getSequence()).isGreaterThan(previous);
                    }
                    if (event.getStage() == MailSendProgressStage.FINISHED) {
                        finished.add(event.getObservationId());
                        assertThat(event.getSubmissionAttempt()).isEqualTo(1);
                        assertThat(event.getBytesWritten()).isPresent().isEqualTo(event.getTotalBytes());
                    }
                }
                assertThat(outcomes).extracting(MailSendOutcome::getObservationId).containsExactlyInAnyOrderElementsOf(finished);
                assertThat(server.messages()).hasSize(3);
                assertThat(server.connectionCount()).isEqualTo(2);
            } finally {
                allowReplies.countDown();
            }
        }
    }

    @Test
    void admissionRejectionFinishesWithoutInventingAPreparationOrSubmission() throws Exception {
        final CountDownLatch invoked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final BlockingQueue<MailSendProgress> progress = new LinkedBlockingQueue<>();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final CustomMailer custom = new CustomMailer() {
            @Override public void testConnection(final OperationalConfig config, final Session session) { }
            @Override public void sendMessage(final OperationalConfig config, final Session session, final Email email, final MimeMessage message) {
                invoked.countDown();
                await(release);
            }
        };
        try (Mailer mailer = builder(25).withCustomMailer(custom).withAsyncQueueCapacity(0)
                .withMailSendProgressObserver(progress::add).withMailSendResultObserver(outcomes::add).buildMailer()) {
            try {
                final MailSend<MailSubmissionReceipt> first = mailer.async().sendMail(email());
                assertThat(invoked.await(10, SECONDS)).isTrue();
                final Throwable rejected = mailer.async().sendMail(email()).getCompletion().handle((receipt, failure) -> failure).get(5, SECONDS);
                assertThat(rejected).isInstanceOf(MailSendRejectedException.class);
                final List<MailSendProgress> delivered = throughFinished(progress);
                final MailSendProgress terminal = delivered.get(delivered.size() - 1);
                assertThat(terminal.getSubmissionAttempt()).isZero();
                assertThat(terminal.getBytesWritten()).isEmpty();
                assertThat(outcomes).hasSize(1);
                assertThat(outcomes.get(0).getObservationId()).isEqualTo(terminal.getObservationId());
                assertThat(outcomes.get(0).getFailure()).containsSame(rejected);
                assertThat(outcomes.get(0).getSubmissionReceipt()).isEmpty();
                assertThat(delivered).filteredOn(event -> event.getObservationId().equals(terminal.getObservationId()))
                        .extracting(MailSendProgress::getStage)
                        .doesNotContain(MailSendProgressStage.PREPARING, MailSendProgressStage.SUBMITTING);
                release.countDown();
                first.getCompletion().get(5, SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void progressAddsNoAttachmentReadsOrSmtpCommands() throws Exception {
        final AtomicInteger reads = new AtomicInteger();
        final jakarta.activation.DataSource content = new jakarta.activation.DataSource() {
            @Override public java.io.InputStream getInputStream() {
                reads.incrementAndGet();
                return new java.io.ByteArrayInputStream(new byte[]{1, 2, 3, 4});
            }
            @Override public java.io.OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "application/octet-stream"; }
            @Override public String getName() { return "report"; }
        };
        final Email email = mail.emailBuilder().copying(email()).withAttachment("report.bin", content).buildEmail();
        try (RecordingSmtpServer baseline = new RecordingSmtpServer(); RecordingSmtpServer observed = new RecordingSmtpServer();
                Mailer plainMailer = builder(baseline.port()).buildMailer();
                Mailer progressMailer = builder(observed.port()).withMailSendProgressObserver(ignored -> { }).buildMailer()) {
            plainMailer.sync().sendMail(email);
            final int withoutProgress = reads.getAndSet(0);
            progressMailer.sync().sendMail(email);
            assertThat(reads).hasValue(withoutProgress);
            assertThat(withoutProgress).isPositive();
            // Dynamic MIME boundaries can differ; the SMTP command categories/count cannot.
            assertThat(observed.commands()).extracting(command -> command.split(" ")[0])
                    .containsExactlyElementsOf(baseline.commands().stream().map(command -> command.split(" ")[0])
                            .collect(java.util.stream.Collectors.toList()));
            assertThat(observed.messages()).hasSize(1);
            assertThat(baseline.messages()).hasSize(1);
        }
    }

    @Test
    void replacingOneRegistrationDoesNotReplaceTheOtherOrItsDispatchChoice() throws Exception {
        final BlockingQueue<Runnable> results = new LinkedBlockingQueue<>();
        final BlockingQueue<Runnable> progress = new LinkedBlockingQueue<>();
        final AtomicInteger replaced = new AtomicInteger();
        final AtomicInteger reported = new AtomicInteger();
        final AtomicInteger displayed = new AtomicInteger();
        try (Mailer mailer = builder(25).withTransportModeLoggingOnly(true)
                .withMailSendResultObserver(ignored -> replaced.incrementAndGet())
                .withMailSendProgressObserver(ignored -> replaced.incrementAndGet())
                .withMailSendResultObserver(ignored -> reported.incrementAndGet(), results::add)
                .withMailSendProgressObserver(ignored -> displayed.incrementAndGet(), progress::add).buildMailer()) {
            mailer.sync().sendMail(email());
            assertThat(replaced).hasValue(0);
            assertThat(reported).hasValue(0);
            assertThat(displayed).hasValue(0);
            take(results).run();
            take(progress).run();
            assertThat(reported).hasValue(1);
            assertThat(displayed).hasValue(1);
        }
    }

    @Test
    void failoverKeepsOneObservationAndDoesNotIncrementTheSubmissionAttempt() throws Exception {
        final BlockingQueue<MailSendProgress> progress = new LinkedBlockingQueue<>();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final AtomicInteger destinationCallbacks = new AtomicInteger();
        try (ScriptedSmtpServer unavailable = new ScriptedSmtpServer(1, peer -> peer.reply("421 try another relay"));
                RecordingSmtpServer available = new RecordingSmtpServer();
                Mailer destination = builder(available.port()).withMailSendProgressObserver(ignored -> destinationCallbacks.incrementAndGet())
                        .withMailSendResultObserver(ignored -> destinationCallbacks.incrementAndGet()).buildMailer();
                Mailer initiator = builder(unavailable.port()).withFailoverMailer(destination, FailoverPolicy.builder().build())
                        .withMailSendProgressObserver(progress::add).withMailSendResultObserver(outcomes::add).buildMailer()) {
            final MailSubmissionReceipt receipt = initiator.async().sendMail(email()).getCompletion().get(15, SECONDS);
            final List<MailSendProgress> delivered = throughFinished(progress);
            assertThat(delivered).extracting(MailSendProgress::getObservationId).containsOnly(outcomes.get(0).getObservationId());
            assertThat(delivered.get(delivered.size() - 1).getSubmissionAttempt()).isEqualTo(1);
            assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(receipt);
            assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getConnectionHistory()).hasSize(2);
            assertThat(destinationCallbacks).hasValue(0);
            assertThat(available.messages()).hasSize(1);
        }
    }

    @Test
    void cancelledRateWaitFinishesBeforeAnySecondAcquisitionOrSubmission() throws Exception {
        final BlockingQueue<MailSendProgress> progress = new LinkedBlockingQueue<>();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer server = new RecordingSmtpServer(); Mailer mailer = builder(server.port())
                .withMessageRateLimit(1, Duration.ofHours(1)).withMailSendProgressObserver(progress::add)
                .withMailSendResultObserver(outcomes::add).buildMailer()) {
            mailer.sync().sendMail(email());
            throughFinished(progress);
            final MailSend<MailSubmissionReceipt> waiting = mailer.async().sendMail(email());
            MailSendProgress next;
            do {
                next = progress.poll(10, SECONDS);
                assertThat(next).isNotNull();
            } while (next.getStage() != MailSendProgressStage.WAITING_FOR_RATE_LIMIT);
            waiting.requestCancellation();
            assertThatThrownBy(() -> waiting.getCompletion().get(5, SECONDS)).hasCauseInstanceOf(MailSendCancelledException.class);
            final List<MailSendProgress> terminal = throughFinished(progress);
            assertThat(terminal.get(terminal.size() - 1).getSubmissionAttempt()).isZero();
            assertThat(terminal).extracting(MailSendProgress::getStage).doesNotContain(MailSendProgressStage.ACQUIRING_CONNECTION);
            assertThat(outcomes.get(1).getSubmissionReceipt()).isEmpty();
            assertThat(server.messages()).hasSize(1);
        }
    }

    @Test
    void customMailerHasOnlyCoarseSubmissionProgressAndAlreadyKnowsItsEffectiveId() throws Exception {
        final CountDownLatch invoked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final BlockingQueue<MailSendProgress> progress = new LinkedBlockingQueue<>();
        final CustomMailer custom = new CustomMailer() {
            @Override public void testConnection(final OperationalConfig config, final Session session) { }
            @Override public void sendMessage(final OperationalConfig config, final Session session, final Email email, final MimeMessage message) {
                invoked.countDown();
                try {
                    assertThat(release.await(15, SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        };
        try (Mailer mailer = builder(25).withCustomMailer(custom).withMailSendProgressObserver(progress::add).buildMailer()) {
            try {
                final MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(email());
                assertThat(invoked.await(10, SECONDS)).isTrue();
                MailSendProgress submitting;
                do {
                    submitting = progress.poll(5, SECONDS);
                    assertThat(submitting).isNotNull();
                } while (submitting.getSubmissionAttempt() == 0);
                assertThat(submitting.getStage()).isEqualTo(MailSendProgressStage.SUBMITTING);
                assertThat(submitting.getEffectiveMessageId()).isNotBlank();
                assertThat(submitting.getBytesWritten()).isEmpty();
                assertThat(submitting.getTotalBytes()).isEmpty();
                release.countDown();
                send.getCompletion().get(5, SECONDS);
                assertThat(throughFinished(progress)).extracting(MailSendProgress::getStage)
                        .doesNotContain(MailSendProgressStage.UPLOADING, MailSendProgressStage.WAITING_FOR_RESPONSE);
            } finally {
                release.countDown();
            }
        }
    }

    private MailerRegularBuilder<?> builder(final int port) {
        return mail.mailerBuilder().withSMTPServer("localhost", port).withTransportStrategy(TransportStrategy.SMTP)
                .withOpportunisticTLS(false).withSmtpClientHostname("probe.example.test").withSessionTimeout(20000)
                .withProperty("mail.host", "localhost").withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).withThreadPoolSize(1);
    }

    private Email email() {
        return mail.emailBuilder().startingBlank().from("sender@example.test")
                .withRecipients(mail.recipientBuilder().withAddress("receiver@example.test").withType(jakarta.mail.Message.RecipientType.TO).build())
                .withSubject("progress boundary").withPlainText("unchanged").buildEmail();
    }

    private static List<MailSendProgress> throughFinished(final BlockingQueue<MailSendProgress> queue) throws InterruptedException {
        final List<MailSendProgress> result = new ArrayList<>();
        MailSendProgress next;
        do {
            next = queue.poll(5, SECONDS);
            assertThat(next).isNotNull();
            result.add(next);
        } while (next.getStage() != MailSendProgressStage.FINISHED);
        return result;
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
