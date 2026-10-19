package org.simplejavamail.mailer.failover;

import org.simplejavamail.api.mailer.MailSendHandlers;
import jakarta.mail.Message.RecipientType;

import jakarta.activation.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendTimeoutException;
import org.simplejavamail.api.mailer.MailSender;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.config.ConfigLoader;
import testutil.smtp.RecordingSmtpServer;
import testutil.testrules.SmtpServerExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.*;
import static org.simplejavamail.mailer.failover.FailoverSmtpContractTest.unavailableServer;



@Timeout(45)
class FailoverScopeContractTest {

    @Test
    void selectedTemplatesAndMandatoryArchiveReachWiserWithoutMutatingTheOriginal() throws Exception {
        final SmtpServerExtension inbox = new SmtpServerExtension(0, null, null, false);
        inbox.beforeEach(null);
        final SimpleJavaMail locked = SimpleJavaMail.withConfig(ConfigLoader.builder()
                .withMap(Map.of("simplejavamail.locked.defaults.bcc.address", "archive@example.test")).load());
        final Email original = MAIL.emailBuilder().startingBlank().withRecipients(SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress("receiver@example.test").withType(RecipientType.TO).build())
                .withPlainText("one shared original").buildEmail();
        try (RecordingSmtpServer primary = unavailableServer();
             Mailer selected = builder(inbox.getPort()).withEmailOverrides(template("selected")).buildMailer();
             Mailer initiating = builder(locked, primary.port()).withEmailOverrides(template("primary"))
                     .withFailoverMailer(selected, policy()).buildMailer()) {
            final var first = initiating.async().sendMail(original);
            final var second = initiating.async().sendMail(original);
            first.getCompletion().get(10, SECONDS);
            second.getCompletion().get(10, SECONDS);
            // Wiser records one delivery per recipient, not one DATA transaction.
            assertThat(inbox.getMessages()).hasSize(6);
            assertThat(inbox.getMessages()).extracting(message -> message.getEnvelopeReceiver())
                    .containsOnly("receiver@example.test", "selected-maintainer@example.test", "archive@example.test");
            for (final var delivery : inbox.getMessages()) {
                assertThat(delivery.getEnvelopeSender()).isEqualTo("selected@example.test");
                assertThat(delivery.getMimeMessage().getHeader("X-Relay")).containsExactly("selected");
            }
            assertThat(original.getFromRecipient()).isNull();
            assertThat(original.getHeaders()).isEmpty();
            assertThat(original.getRecipients()).hasSize(1);
        } finally {
            inbox.afterEach(null);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactProtectedBytesSurviveDestinationSelection(final boolean asynchronous) throws Exception {
        final byte[] authoritative;
        try (InputStream input = getClass().getResourceAsStream("/openpgpjs/signed-mixed.eml")) {
            assertThat(input).isNotNull();
            authoritative = input.readAllBytes();
        }
        final Email exact = MAIL.emailBuilder().startingFromExactEml(authoritative).withEnvelopeSender("sender@example.test")
                .withEnvelopeRecipients("receiver@example.test").buildEmail();
        try (RecordingSmtpServer primary = unavailableServer(); RecordingSmtpServer secondary = new RecordingSmtpServer();
             Mailer selected = builder(secondary.port()).withEmailOverrides(template("must-not-rewrite-exact")).buildMailer();
             Mailer initiating = builder(primary.port()).withFailoverMailer(selected, policy()).buildMailer()) {
            send(initiating, exact, asynchronous);
            assertThat(secondary.messages()).hasSize(1);
            assertThat(secondary.messages().get(0)).isEqualTo(authoritative);
        }
    }

    @Test
    void failedConnectionDoesNotAddAttachmentReads() throws Exception {
        final CountingAttachment attachment = new CountingAttachment();
        final Email original = MAIL.emailBuilder().copying(email()).withAttachment("payload.txt", attachment).buildEmail();
        try (RecordingSmtpServer primary = unavailableServer(); RecordingSmtpServer secondary = new RecordingSmtpServer();
             Mailer selected = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withFailoverMailer(selected, policy()).buildMailer()) {
            selected.sync().sendMail(original);
            final int baseline = attachment.reads.get();
            attachment.reads.set(0);
            initiating.sync().sendMail(original);
            assertThat(attachment.reads).hasValue(baseline);
            assertThat(baseline).isPositive();
            assertThat(primary.messages()).isEmpty();
            assertThat(secondary.messages()).hasSize(2);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lazyBatchSwitchesOnlyUntouchedEmailsAfterRecovery(final boolean asynchronous) throws Exception {
        final AtomicInteger iterators = new AtomicInteger();
        final AtomicInteger reached = new AtomicInteger();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer primary = new RecordingSmtpServer((peer, connection) -> {
            if (connection.number() == 1) {
                peer.reply("421 unavailable");
            } else {
                connection.greet(peer, "250 localhost");
                connection.acceptMessages(peer);
            }
        }); RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer selected = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withFailoverMailer(selected,
                     FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofNanos(1)).build())
                     .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer()) {
            final Iterable<Email> batch = trackedBatch(iterators, reached);
            if (asynchronous) {
                initiating.async().sendMailsInSimpleBatch(batch).getCompletion().get(12, SECONDS);
            } else {
                initiating.sync().sendMailsInSimpleBatch(batch);
            }
            assertThat(iterators).hasValue(1);
            assertThat(reached).hasValue(3);
            assertThat(outcomes).hasSize(3).allMatch(MailSendOutcome::isSuccessful);
            assertThat(secondary.messages()).hasSize(1);
            assertThat(primary.messages()).hasSize(2);
            assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.getDiagnostics().orElseThrow().getConnectionHistory()).isEmpty());
        }
    }

    @Test
    void batchStopsAtItsFirstSubmissionFailure() throws Exception {
        final AtomicInteger iterators = new AtomicInteger();
        final AtomicInteger reached = new AtomicInteger();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer primary = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250 localhost");
            connection.acceptMessages(peer, count -> count == 1 ? "250 queued" : "554 rejected");
        }); RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer selected = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withFailoverMailer(selected, policy()).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer()) {
            assertThat(catchThrowable(() -> initiating.sync().sendMailsInSimpleBatch(trackedBatch(iterators, reached))))
                    .isInstanceOf(MailSubmissionException.class);
            assertThat(iterators).hasValue(1);
            assertThat(reached).hasValue(2);
            assertThat(outcomes).hasSize(2);
            assertThat(primary.messages()).hasSize(2);
            assertThat(secondary.connectionCount()).isZero();
        }
    }

    @Test
    void callbackExecutesOnceRemainsPinnedAndItsEscapedSenderExpires() throws Exception {
        final AtomicInteger callbacks = new AtomicInteger();
        final AtomicReference<MailSender> escaped = new AtomicReference<>();
        try (RecordingSmtpServer primary = unavailableServer(); RecordingSmtpServer secondary = new RecordingSmtpServer();
             Mailer selected = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withFailoverMailer(selected,
                     FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofNanos(1)).build()).buildMailer()) {
            initiating.withOpenConnection(sender -> {
                callbacks.incrementAndGet();
                escaped.set(sender);
                sender.sendMail(email());
                sender.sendMail(email());
            });
            assertThat(callbacks).hasValue(1);
            assertThat(primary.connectionCount()).isEqualTo(1);
            assertThat(secondary.connectionCount()).isEqualTo(1);
            assertThat(secondary.messages()).hasSize(2);
            assertThat(catchThrowable(() -> escaped.get().sendMail(email()))).isInstanceOf(IllegalStateException.class);
            assertThat(secondary.messages()).hasSize(2);
        }
    }

    @Test
    void queueRejectionDoesNotOpenBatchIteratorOrContactFailover() throws Exception {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger iterators = new AtomicInteger();
        try (RecordingSmtpServer primary = new RecordingSmtpServer((peer, connection) -> {
            connected.countDown();
            await(release);
            connection.greet(peer, "250 localhost");
            connection.acceptMessages(peer);
        }); RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer selected = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withThreadPoolSize(1).withAsyncQueueCapacity(0)
                     .withAsyncQueueOverflowPolicy(AsyncQueueOverflowPolicy.REJECT).withFailoverMailer(selected, policy()).buildMailer()) {
            try {
                final var occupied = initiating.async().sendMail(email());
                await(connected);
                final var rejected = initiating.async().sendMailsInSimpleBatch(trackedBatch(iterators, new AtomicInteger()));
                assertThat(catchThrowable(() -> rejected.getCompletion().get(10, SECONDS))).isNotNull();
                assertThat(iterators).hasValue(0);
                assertThat(secondary.connectionCount()).isZero();
                release.countDown();
                occupied.getCompletion().get(10, SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void rateWaitingAndItsCancellationDoNotContactFailover() throws Exception {
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer primary = new RecordingSmtpServer(); RecordingSmtpServer secondary = new RecordingSmtpServer();
             Mailer selected = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withMessageRateLimit(1, Duration.ofHours(1))
                     .withFailoverMailer(selected, policy()).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer()) {
            initiating.sync().sendMail(email());
            final var waiting = initiating.async().sendMail(email());
            try {
                eventually(() -> initiating.getAsyncQueueSnapshot().orElseThrow().getActiveCount() == 1);
                assertThat(waiting.getCompletion().isDone()).isFalse();
                assertThat(primary.messages()).hasSize(1);
                assertThat(secondary.connectionCount()).isZero();
            } finally {
                waiting.requestCancellation();
            }
            assertThat(catchThrowable(() -> waiting.getCompletion().get(10, SECONDS))).isNotNull();
            assertThat(outcomes).hasSize(2);
            assertThat(outcomes.get(1).getSubmissionReceipt()).isEmpty();
            assertThat(secondary.connectionCount()).isZero();
        }
    }

    @Test
    void deadlineDuringMeasuredRateWaitingDoesNotContactFailover() throws Exception {
        final SimpleJavaMail factory = factory();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer primary = new RecordingSmtpServer(); RecordingSmtpServer secondary = new RecordingSmtpServer();
             Mailer seed = builder(factory, primary.port()).withMessageRateLimit(1, Duration.ofHours(1)).withRateLimitGroup("shared").buildMailer();
             Mailer selected = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(factory, primary.port()).withMessageRateLimit(1, Duration.ofHours(1)).withRateLimitGroup("shared")
                     .withMailSendTimeout(Duration.ofSeconds(1)).withFailoverMailer(selected, policy())
                     .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer()) {
            // Spend the shared allowance without imposing the waiting send's deadline on the initial connection setup.
            seed.sync().sendMail(email());
            final Throwable failure = catchThrowable(() -> initiating.sync().sendMail(email()));
            assertThat(failure).isInstanceOf(MailSendTimeoutException.class);
            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).getFailure()).containsSame(failure);
            assertThat(outcomes.get(0).getSubmissionReceipt()).isEmpty();
            final var measurement = outcomes.get(0).getDiagnostics().orElseThrow().getRateLimitWait();
            assertThat(measurement.getElapsed()).hasValueSatisfying(elapsed -> assertThat(elapsed).isGreaterThan(Duration.ZERO));
            assertThat(primary.messages()).hasSize(1);
            assertThat(primary.connectionCount()).isEqualTo(1);
            assertThat(secondary.connectionCount()).isZero();
        }
    }

    @Test
    void failedRechecksKeepTheExistingBatchConnection() throws Exception {
        try (RecordingSmtpServer primary = unavailableServer(); RecordingSmtpServer secondary = new RecordingSmtpServer();
             Mailer selected = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withFailoverMailer(selected,
                     FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofNanos(1)).build()).buildMailer()) {
            initiating.sync().sendMailsInSimpleBatch(List.of(email(), email(), email()));
            assertThat(primary.connectionCount()).isEqualTo(3);
            assertThat(secondary.connectionCount()).isEqualTo(1);
            assertThat(secondary.messages()).hasSize(3);
        }
    }

    @Test
    void totalDeadlineStopsSetupWithoutTryingAnotherDestination() throws Exception {
        final CountDownLatch setupStarted = new CountDownLatch(1);
        try (RecordingSmtpServer primary = new RecordingSmtpServer((peer, connection) -> {
            setupStarted.countDown();
            peer.expectClosed();
        }); RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer selected = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withMailSendTimeout(Duration.ofSeconds(2))
                     .withFailoverMailer(selected, policy()).buildMailer()) {
            final var expiring = initiating.async().sendMail(email());
            await(setupStarted);
            assertThat(catchThrowable(() -> expiring.getCompletion().get(10, SECONDS))).isNotNull();
            assertThat(secondary.connectionCount()).isZero();
            assertThat(primary.messages()).isEmpty();
        }
    }

    @Test
    void closingTheSelectedParticipantDrainsItsDelegatedSend() throws Exception {
        final CountDownLatch receivingData = new CountDownLatch(1);
        final CountDownLatch acknowledge = new CountDownLatch(1);
        try (RecordingSmtpServer primary = unavailableServer(); RecordingSmtpServer secondary = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250 localhost");
            connection.acceptMessages(peer, ignored -> {
                receivingData.countDown();
                awaitUnchecked(acknowledge);
                return "250 queued";
            });
        }); Mailer selected = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withFailoverMailer(selected, policy()).buildMailer()) {
            try {
                final var sending = initiating.async().sendMail(email());
                await(receivingData);
                final var closing = selected.closeAsync();
                assertThat(closing.isDone()).isFalse();
                acknowledge.countDown();
                sending.getCompletion().get(10, SECONDS);
                closing.get(10, SECONDS);
                assertThat(secondary.messages()).hasSize(1);
                assertThat(catchThrowable(() -> initiating.sync().sendMail(email()))).isNotNull();
                assertThat(secondary.messages()).hasSize(1);
            } finally {
                acknowledge.countDown();
            }
        }
    }

    @Test
    void cancellingTheCurrentBatchEmailDoesNotReadTheNextOne() throws Exception {
        final CountDownLatch secondMessageReceived = new CountDownLatch(1);
        final AtomicInteger iterators = new AtomicInteger();
        final AtomicInteger reached = new AtomicInteger();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer primary = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250 localhost");
            connection.acceptMessages(peer, count -> {
                if (count == 2) {
                    secondMessageReceived.countDown();
                    try {
                        peer.expectClosed();
                    } catch (java.io.IOException failure) {
                        throw new AssertionError("cancelled batch socket did not close cleanly", failure);
                    }
                    return null;
                }
                return "250 queued";
            });
        }); RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer selected = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withFailoverMailer(selected, policy()).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer()) {
            final var batch = initiating.async().sendMailsInSimpleBatch(trackedBatch(iterators, reached));
            try {
                await(secondMessageReceived);
            } finally {
                batch.requestCancellation();
            }
            assertThat(catchThrowable(() -> batch.getCompletion().get(10, SECONDS))).isNotNull();
            assertThat(iterators).hasValue(1);
            assertThat(reached).hasValue(2);
            assertThat(outcomes).hasSize(2);
            assertThat(outcomes.get(0).isSuccessful()).isTrue();
            assertThat(outcomes.get(1).isSuccessful()).isFalse();
            assertThat(primary.messages()).hasSize(2);
            assertThat(secondary.connectionCount()).isZero();
        }
    }

    private static void awaitUnchecked(final CountDownLatch latch) {
        try {
            await(latch);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test barrier interrupted", interrupted);
        }
    }

    private static Email template(final String owner) {
        return MAIL.emailBuilder().startingBlank().from(owner + "@example.test").withHeader("X-Relay", owner)
                .withRecipients(SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress(owner + "-maintainer@example.test").withType(RecipientType.BCC).build()).buildEmail();
    }

    private static Iterable<Email> trackedBatch(final AtomicInteger iterators, final AtomicInteger reached) {
        return () -> {
            iterators.incrementAndGet();
            return new Iterator<Email>() {
                @Override
                public boolean hasNext() {
                    return reached.get() < 3;
                }

                @Override
                public Email next() {
                    reached.incrementAndGet();
                    return email();
                }
            };
        };
    }

    private static final class CountingAttachment implements DataSource {
        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public InputStream getInputStream() {
            reads.incrementAndGet();
            return new ByteArrayInputStream(new byte[]{1, 2, 3});
        }

        @Override
        public OutputStream getOutputStream() {
            throw new UnsupportedOperationException("read-only test attachment");
        }

        @Override
        public String getContentType() {
            return "application/octet-stream";
        }

        @Override
        public String getName() {
            return "payload";
        }
    }
}
