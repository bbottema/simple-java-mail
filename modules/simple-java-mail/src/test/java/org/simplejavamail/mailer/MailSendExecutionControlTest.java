package org.simplejavamail.mailer;

import jakarta.activation.DataSource;
import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendTimeoutException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.ConfigLoaderTestHelper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.simplejavamail.recipient.RecipientBuilder.to;

@Timeout(20)
class MailSendExecutionControlTest {
    @ParameterizedTest
    @ValueSource(strings = {"BDAT_NON_LAST", "BDAT_LAST"})
    void bdatCancellationDistinguishesIntermediateChunksFromPossibleCommit(final String phase) throws Exception {
        try (BlockedSmtpServer server = new BlockedSmtpServer(phase);
             Mailer mailer = builder(server).withProperty("mail.smtp.chunksize", 64).buildMailer()) {
            final MailSend<MailSubmissionReceipt> send = mailer.sendMailAndGetReceiptAsync(email("chunked"));
            assertThat(server.blocked.await(5, SECONDS)).isTrue();
            send.requestCancellation();
            final MailSendCancelledException cancelled = (MailSendCancelledException) failure(send);
            final MailSubmissionReceipt receipt = cancelled.getSubmissionReceipt().orElseThrow(AssertionError::new);
            assertThat(receipt.getStatus()).isEqualTo(phase.equals("BDAT_LAST") ? MailSubmissionStatus.UNKNOWN : MailSubmissionStatus.REJECTED);
            if (phase.equals("BDAT_LAST")) {
                assertThat(receipt.getRetryDisposition()).isEqualTo(MailRetryDisposition.DUPLICATE_RISK);
            }
            assertThat(mailer.sendMailAndGetReceiptSync(email("next-chunked")).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
        }
    }

    @Test
    void acceptedUnpooledSendSurvivesDeadlineDuringQuitCleanup() throws Exception {
        final ExecutorService caller = Executors.newSingleThreadExecutor();
        try (BlockedSmtpServer server = new BlockedSmtpServer("QUIT");
             Mailer mailer = builder(server).withMailSendTimeout(Duration.ofSeconds(5)).buildMailer()) {
            mailer.rehearse(email("warm-up"));
            final CompletableFuture<MailSubmissionReceipt> send = CompletableFuture.supplyAsync(() -> {
                try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
                    modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
                    return mailer.sendMailAndGetReceiptSync(email("accepted-before-quit"));
                }
            }, caller);
            assertThat(server.blocked.await(8, SECONDS)).isTrue();
            assertThat(send.get(8, SECONDS).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void cancellationDuringBodyWriteIsKnownUnsentBeforeTheTerminator() throws Exception {
        try (BlockedSmtpServer server = new BlockedSmtpServer("BODY");
             Mailer mailer = builder(server).buildMailer()) {
            final Email large = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                    .from("sender@example.org").withRecipients(to(null, "large@example.org"))
                    .withPlainText("x".repeat(8_000_000)).buildEmail();
            final MailSend<MailSubmissionReceipt> send = mailer.sendMailAndGetReceiptAsync(large);
            assertThat(server.blocked.await(5, SECONDS)).isTrue();
            send.requestCancellation();
            final MailSendCancelledException cancellation = (MailSendCancelledException) failure(send);
            assertThat(cancellation.getSubmissionReceipt().orElseThrow(AssertionError::new).getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
        }
    }

    @Test
    void nonCooperativeDataSourceDelaysCompletionButCannotSendAfterCancellation() throws Exception {
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try (BlockedSmtpServer server = new BlockedSmtpServer("NONE");
             Mailer mailer = builder(server).buildMailer()) {
            final DataSource source = new DataSource() {
                @Override public InputStream getInputStream() { reading.countDown(); await(release); return new ByteArrayInputStream(new byte[]{1}); }
                @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
                @Override public String getContentType() { return "application/octet-stream"; }
                @Override public String getName() { return "blocked-data-source"; }
            };
            final Email attachment = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                    .from("sender@example.org").withRecipients(to(null, "attachment@example.org"))
                    .withAttachment("blocked", source).buildEmail();
            final MailSend<Void> send = mailer.sendMailAsync(attachment);
            try {
                assertThat(reading.await(5, SECONDS)).isTrue();
                send.requestCancellation();
                assertThat(send.getCompletion()).isNotDone();
            } finally {
                release.countDown();
            }
            assertThat(failure(send)).isInstanceOf(MailSendCancelledException.class);
            assertThat(server.messages).hasValue(0);
        } finally {
            release.countDown();
        }
    }

    @Test
    void simultaneousHealthyLeasesAreUnaffectedByOneAbortedLease() throws Exception {
        try (BlockedSmtpServer server = new BlockedSmtpServer(".");
             Mailer mailer = builder(server).withThreadPoolSize(4).withConnectionPoolMaxSize(4).buildMailer()) {
            final MailSend<Void> blocked = mailer.sendMailAsync(email("cancelled"));
            assertThat(server.blocked.await(5, SECONDS)).isTrue();
            final List<MailSend<MailSubmissionReceipt>> healthy = new CopyOnWriteArrayList<>();
            for (int index = 0; index < 12; index++) {
                healthy.add(mailer.sendMailAndGetReceiptAsync(email("healthy-" + index)));
            }
            blocked.requestCancellation();
            assertThat(failure(blocked)).isInstanceOf(MailSendCancelledException.class);
            for (int index = 0; index < healthy.size(); index++) {
                final MailSubmissionReceipt receipt = healthy.get(index).getCompletion().get(5, SECONDS);
                assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
                assertThat(receipt.getRecipientResults().get(0).getEnvelopeAddress()).contains("healthy-" + index + "@example.org");
            }
        }
    }

    @Test
    void openConnectionGetsFreshPerEmailBudgetsAndDoesNotTimeApplicationGaps() throws Exception {
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (BlockedSmtpServer server = new BlockedSmtpServer("NONE");
             Mailer mailer = builder(server).withMailSendTimeout(Duration.ofSeconds(2)).withMailSendObserver(outcomes::add).buildMailer()) {
            mailer.withOpenConnection(sender -> {
                sender.sendMail(email("first"));
                // Outlast an entire send budget without requiring sub-second SMTP round trips under parallel test load.
                new CountDownLatch(1).await(2500, MILLISECONDS);
                sender.sendMail(email("second"));
            });
            assertThat(outcomes).hasSize(2).allSatisfy(outcome -> assertThat(outcome.isSuccessful()).isTrue());
            assertThat(server.connections).hasValue(1);
        }
    }

    @Test
    void simpleBatchSharesOneBudgetAndDoesNotReachUntouchedEmails() throws Exception {
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (BlockedSmtpServer server = new BlockedSmtpServer("SECOND_REPLY");
             Mailer mailer = builder(server).withMailSendTimeout(Duration.ofSeconds(5)).withMailSendObserver(outcomes::add).buildMailer()) {
            final MailSend<Void> batch = mailer.sendMailsInSimpleBatch(List.of(email("first"), email("second"), email("untouched")), true);
            assertThat(failure(batch)).isInstanceOf(MailSendTimeoutException.class);
            assertThat(outcomes).hasSize(2);
            assertThat(outcomes.get(0).isSuccessful()).isTrue();
            assertThat(outcomes.get(1).isSuccessful()).isFalse();
        }
    }

    @Test
    void inlineObserversBetweenBatchEmailsDoNotConsumeTheBatchBudget() throws Exception {
        final AtomicInteger callbacks = new AtomicInteger();
        try (BlockedSmtpServer server = new BlockedSmtpServer("DELAY");
             Mailer mailer = builder(server).withMailSendTimeout(Duration.ofMillis(1500)).withMailSendObserver(outcome -> {
                 if (callbacks.incrementAndGet() == 1) {
                     try {
                         new CountDownLatch(1).await(1800, MILLISECONDS);
                     } catch (InterruptedException interrupted) {
                         Thread.currentThread().interrupt();
                     }
                 }
             }).buildMailer()) {
            mailer.sendMailsInSimpleBatch(List.of(email("first"), email("second")), true).getCompletion().get(5, SECONDS);
            assertThat(callbacks).hasValue(2);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GREETING", "EHLO", "AUTH", "MAIL FROM:", "RCPT TO:", "DATA", "."})
    void cancellationAbortsThePooledAttemptAndTheNextLeaseHasFreshFacts(final String phase) throws Exception {
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (BlockedSmtpServer server = new BlockedSmtpServer(phase);
             Mailer mailer = builder(server).withMailSendObserver(outcomes::add).buildMailer()) {
            final MailSend<MailSubmissionReceipt> send = mailer.sendMailAndGetReceiptAsync(email("cancelled"));
            assertThat(server.blocked.await(5, SECONDS)).as(phase).isTrue();
            send.requestCancellation();
            send.requestCancellation();
            final Throwable failure = failure(send);
            assertThat(failure).isInstanceOf(MailSendCancelledException.class);
            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).getFailure()).containsSame(failure);
            final MailSendCancelledException cancellation = (MailSendCancelledException) failure;
            if (phase.equals(".")) {
                final MailSubmissionReceipt receipt = cancellation.getSubmissionReceipt().orElseThrow(AssertionError::new);
                assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
                assertThat(receipt.getRetryDisposition()).isEqualTo(MailRetryDisposition.DUPLICATE_RISK);
                assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(receipt);
            } else if (cancellation.getSubmissionReceipt().isPresent()) {
                assertThat(cancellation.getSubmissionReceipt().get().getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
            }
            final MailSubmissionReceipt next = mailer.sendMailAndGetReceiptAsync(email("fresh")).getCompletion().get(5, SECONDS);
            assertThat(next.getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            assertThat(next.getRecipientResults()).allSatisfy(recipient ->
                    assertThat(recipient.getEnvelopeAddress()).contains("fresh@example.org"));
            assertThat(outcomes.get(1).getSubmissionReceipt()).containsSame(next);
            send.requestCancellation();
            assertThat(mailer.sendMailAndGetReceiptSync(email("after-late-request")).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GREETING", "EHLO", "AUTH", "MAIL FROM:", "RCPT TO:", "DATA", "."})
    void deadlineAbortsBlockedIoLongBeforeTheSocketTimeout(final String phase) throws Exception {
        try (BlockedSmtpServer server = new BlockedSmtpServer(phase);
             Mailer mailer = builder(server).withMailSendTimeout(Duration.ofMillis(500)).buildMailer()) {
            final MailSend<MailSubmissionReceipt> send = mailer.sendMailAndGetReceiptAsync(email("deadline"));
            assertThat(server.blocked.await(5, SECONDS)).isTrue();
            assertThat(failure(send)).isInstanceOf(MailSendTimeoutException.class);
        }
    }

    @Test
    void aPoolSizeOneObserverCanSendAgainAfterTheCancelledLeaseIsDisposed() throws Exception {
        final AtomicReference<Mailer> currentMailer = new AtomicReference<>();
        final AtomicReference<MailSubmissionReceipt> nested = new AtomicReference<>();
        final AtomicReference<Throwable> nestedFailure = new AtomicReference<>();
        try (BlockedSmtpServer server = new BlockedSmtpServer(".");
             Mailer mailer = builder(server).withMailSendObserver(outcome -> {
                 if (!outcome.isSuccessful()) {
                     try {
                         nested.set(currentMailer.get().sendMailAndGetReceiptSync(email("observer-reentrant")));
                     } catch (Throwable failure) {
                         nestedFailure.set(failure);
                     }
                 }
             }).buildMailer()) {
            currentMailer.set(mailer);
            final MailSend<Void> send = mailer.sendMailAsync(email("cancelled"));
            assertThat(server.blocked.await(5, SECONDS)).isTrue();
            send.requestCancellation();
            assertThat(failure(send)).isInstanceOf(MailSendCancelledException.class);
            assertThat(nestedFailure).hasValue(null);
            assertThat(nested.get().getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
        }
    }

    @Test
    void poolAcquisitionCanBeCancelledWithoutAbortingItsCurrentBorrower() throws Exception {
        try (BlockedSmtpServer server = new BlockedSmtpServer("HELD_REPLY");
             Mailer mailer = builder(server).withThreadPoolSize(2).buildMailer()) {
            final List<Thread> workers = recordSendWorkers(mailer);
            final MailSend<MailSubmissionReceipt> owner = mailer.sendMailAndGetReceiptAsync(email("owner"));
            try {
                assertThat(server.blocked.await(5, SECONDS)).isTrue();
                final MailSend<Void> waiter = mailer.sendMailAsync(email("waiter"));
                awaitBlockedPoolClaim(workers);
                waiter.requestCancellation();
                assertThat(failure(waiter)).isInstanceOf(MailSendCancelledException.class)
                        .hasRootCauseInstanceOf(CancellationException.class);
                assertThat(owner.getCompletion()).isNotDone();
                assertThat(server.connections).hasValue(1);
            } finally {
                server.releaseReply.countDown();
            }
            assertThat(owner.getCompletion().get(5, SECONDS).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            assertThat(mailer.sendMailAndGetReceiptSync(email("after-cancelled-claim")).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            assertThat(server.connections).as("the current borrower's connection remains reusable").hasValue(1);
            assertThat(server.messages).as("the cancelled claimant never submits an email").hasValue(2);
        }
    }

    @Test
    void cancelledQueuedSimpleBatchNeverOpensTheIterable() throws Exception {
        final ExecutorService callerExecutor = Executors.newSingleThreadExecutor();
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger iterations = new AtomicInteger();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
                .withTransportModeLoggingOnly(true).withExecutorService(callerExecutor).withMailSendObserver(outcomes::add).buildMailer()) {
            callerExecutor.execute(() -> await(release));
            final Iterable<Email> emails = () -> { iterations.incrementAndGet(); return List.of(email("untouched")).iterator(); };
            final MailSend<Void> send = mailer.sendMailsInSimpleBatch(emails, true);
            send.requestCancellation();
            assertThat(failure(send)).isInstanceOf(MailSendCancelledException.class);
            assertThat(iterations).hasValue(0);
            assertThat(outcomes).isEmpty();
        } finally {
            release.countDown();
            callerExecutor.shutdownNow();
        }
    }

    @Test
    void unknownCallerSessionRejectsATotalDeadlineWithoutOpeningASocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final Properties properties = new Properties();
            properties.setProperty("mail.smtp.host", server.getInetAddress().getHostAddress());
            properties.setProperty("mail.smtp.port", Integer.toString(server.getLocalPort()));
            final Session session = Session.getInstance(properties);
            try (Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder(session)
                    .withMailSendTimeout(Duration.ofSeconds(1)).buildMailer()) {
                assertThat(failure(mailer.sendMailAsync(email("unsupported")))).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Simple Java Mail can't stop its network calls")
                        .hasMessageContaining("Remove the total timeout from your builder or configuration")
                        .hasMessageContaining("If you can't change the configuration that sets it");
                assertThat(session.getProperty("mail.smtp.socketFactory.fallback")).isNull();
                server.setSoTimeout(100);
                assertThatThrownBy(server::accept).isInstanceOf(java.net.SocketTimeoutException.class);
            }
        }
    }

    private static MailerRegularBuilder<?> builder(final BlockedSmtpServer server) {
        final MailerRegularBuilder<?> builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
                .withTransportStrategy(TransportStrategy.SMTP).withOpportunisticTLS(false)
                .withSMTPServer(InetAddress.getLoopbackAddress().getHostAddress(), server.listener.getLocalPort())
                .withSessionTimeout(30000).withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1)
                .withConnectionPoolClaimTimeoutMillis(5000);
        if (server.phase.equals("AUTH")) {
            builder.withSMTPServerUsername("user").withSMTPServerPassword("password").withProperty("mail.smtp.auth.mechanisms", "PLAIN");
        }
        return builder;
    }

    private static Email email(final String recipient) {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("sender@example.org").withRecipients(to(null, recipient + "@example.org"))
                .withSubject("Execution control test").withPlainText("body").buildEmail();
    }

    private static List<Thread> recordSendWorkers(final Mailer mailer) {
        final List<Thread> workers = new CopyOnWriteArrayList<>();
        final ThreadPoolExecutor executor = (ThreadPoolExecutor) mailer.getOperationalConfig().getExecutorService();
        final ThreadFactory originalFactory = executor.getThreadFactory();
        executor.setThreadFactory(action -> {
            final Thread worker = originalFactory.newThread(action);
            workers.add(worker);
            return worker;
        });
        return workers;
    }

    private static void awaitBlockedPoolClaim(final List<Thread> workers) throws InterruptedException {
        // A running send may still be preparing; cancel only after it reaches the pool's actual availability wait.
        final long deadline = System.nanoTime() + SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            for (Thread worker : workers) {
                if (worker.getState() == Thread.State.TIMED_WAITING) {
                    for (StackTraceElement frame : worker.getStackTrace()) {
                        if (frame.getClassName().equals("org.bbottema.genericobjectpool.ClaimAttempt")
                                && frame.getMethodName().equals("awaitAvailability")) {
                            return;
                        }
                    }
                }
            }
            Thread.sleep(1);
        }
        fail("The send did not reach the underlying pool's availability wait");
    }

    private static Throwable failure(final MailSend<?> send) throws Exception {
        return send.getCompletion().handle((value, failure) -> failure).get(10, SECONDS);
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertThat(latch.await(10, SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Withholds exactly one connection's selected reply; later connections behave normally so reuse can be checked. */
    private static final class BlockedSmtpServer implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        private final ExecutorService peers = Executors.newCachedThreadPool();
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final List<Throwable> errors = new CopyOnWriteArrayList<>();
        private final AtomicInteger connections = new AtomicInteger();
        private final AtomicInteger messages = new AtomicInteger();
        private final CountDownLatch releaseBody = new CountDownLatch(1);
        private final CountDownLatch releaseReply = new CountDownLatch(1);
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final String phase;
        private volatile boolean closed;

        private BlockedSmtpServer(final String phase) throws IOException {
            this.phase = phase;
            peers.submit(() -> {
                while (!closed) {
                    try {
                        final Socket socket = listener.accept();
                        sockets.add(socket);
                        final boolean first = connections.incrementAndGet() == 1;
                        peers.submit(() -> serve(socket, first));
                    } catch (IOException failure) {
                        if (!closed) {
                            errors.add(failure);
                        }
                    }
                }
            });
        }

        private void serve(final Socket socket, final boolean first) {
            try (Socket connection = socket;
                 BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                 PrintWriter output = new PrintWriter(connection.getOutputStream(), true, StandardCharsets.US_ASCII)) {
                connection.setSoTimeout(10000);
                if (first && phase.equals("GREETING")) {
                    waitForAbort(input);
                    return;
                }
                reply(output, "220 control.example ESMTP");
                boolean body = false;
                String line;
                while ((line = input.readLine()) != null) {
                    if (first && (!body || line.equals(".")) && line.startsWith(phase)) {
                        waitForAbort(input);
                        return;
                    }
                    if (body && !line.equals(".")) {
                        continue;
                    }
                    if (line.startsWith("EHLO")) {
                        reply(output, "250-control.example\r\n250-CHUNKING\r\n250 AUTH PLAIN");
                    } else if (line.startsWith("BDAT ")) {
                        final int size = Integer.parseInt(line.split(" ")[1]);
                        final boolean last = line.endsWith(" LAST");
                        for (int index = 0; index < size; index++) {
                            if (input.read() < 0) {
                                return;
                            }
                        }
                        if (first && (last && phase.equals("BDAT_LAST") || !last && phase.equals("BDAT_NON_LAST"))) {
                            waitForAbort(input);
                            return;
                        }
                        reply(output, "250 2.0.0 chunk received");
                    } else if (line.startsWith("AUTH")) {
                        reply(output, "235 2.7.0 authenticated");
                    } else if (line.equals("DATA")) {
                        body = true;
                        reply(output, "354 continue");
                        if (first && phase.equals("BODY")) {
                            blocked.countDown();
                            await(releaseBody);
                            return;
                        }
                    } else {
                        if (line.equals(".")) {
                            messages.incrementAndGet();
                            if (first && phase.equals("HELD_REPLY") && messages.get() == 1) {
                                blocked.countDown();
                                await(releaseReply);
                            }
                            if (phase.equals("SECOND_REPLY") && messages.get() == 2) {
                                waitForAbort(input);
                                return;
                            }
                            if (phase.equals("DELAY")) {
                                new CountDownLatch(1).await(300, MILLISECONDS);
                            }
                        }
                        body = false;
                        reply(output, line.equals("QUIT") ? "221 bye" : "250 2.0.0 accepted");
                        if (line.equals("QUIT")) {
                            return;
                        }
                    }
                }
            } catch (SocketException expectedAbort) {
                // An aborted SMTP client may reset rather than gracefully close the connection.
            } catch (Exception failure) {
                if (!closed) {
                    errors.add(failure);
                }
            } finally {
                sockets.remove(socket);
            }
        }

        private void waitForAbort(final BufferedReader input) throws IOException {
            blocked.countDown();
            while (input.readLine() != null) {
                // Ignore buffered bytes; the client must close without a reply from us.
            }
        }

        private static void reply(final PrintWriter output, final String line) {
            output.print(line + "\r\n");
            output.flush();
        }

        @Override
        public void close() throws Exception {
            closed = true;
            releaseBody.countDown();
            releaseReply.countDown();
            listener.close();
            for (Socket socket : sockets) {
                socket.close();
            }
            peers.shutdownNow();
            assertThat(peers.awaitTermination(5, SECONDS)).isTrue();
            assertThat(errors).isEmpty();
        }
    }
}
