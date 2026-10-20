package testutil.memory;

import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.MailSendHandlers;
import jakarta.activation.FileDataSource;
import jakarta.mail.Message.RecipientType;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendProgress;
import org.simplejavamail.api.mailer.MailSendProgressStage;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Keeps only live work and aggregate counters, except for the deliberately leaking positive-control profile. */
final class MemoryPressureRun implements AutoCloseable {
    private static final int MAXIMUM_CONCURRENT_CANCELLATIONS = 64;
    private final MemoryPressureOptions options;
    private final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    private final List<MemorySmtpProcess> servers = new ArrayList<>();
    private final List<Email> positiveControl = new ArrayList<>();
    private final Map<String, CountDownLatch> retryWaiters = new ConcurrentHashMap<>();
    private final AtomicReference<Throwable> unexpectedOutcome = new AtomicReference<>();
    private Mailer mailer;
    private Mailer failover;
    private Path attachment;
    private long emailSequence;
    private int measuredBatches;
    private int scenarioTransitions;
    private int recoveries;
    private long primaryAccepted;
    private long secondaryAccepted;
    private long primaryBeforeBatch;
    private long secondaryBeforeBatch;
    final Counters counters = new Counters();

    MemoryPressureRun(final MemoryPressureOptions options) throws Exception {
        this.options = options;
        try {
            startSmtpDestinations();
            prepareAttachmentFile();
            createMailers();
        } catch (Exception failure) {
            try {
                close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    List<MemorySmtpProcess> servers() {
        return Collections.unmodifiableList(servers);
    }

    private void startSmtpDestinations() throws Exception {
        final String mode = options.profile.equals("retry") ? "retry" : options.profile.equals("cancellation") ? "refuse" : "accept";
        servers.add(new MemorySmtpProcess(options, "primary", mode));
        if (options.profile.equals("failover")) {
            servers.add(new MemorySmtpProcess(options, "secondary", "accept"));
        }
    }

    private void prepareAttachmentFile() throws IOException {
        if (options.profile.equals("attachment-file")) {
            attachment = options.output.resolve("synthetic-attachment.bin");
            final byte[] block = new byte[32768];
            try (OutputStream output = Files.newOutputStream(attachment)) {
                for (int remaining = options.attachmentBytes; remaining > 0; remaining -= Math.min(remaining, block.length)) {
                    output.write(block, 0, Math.min(remaining, block.length));
                }
            }
        }
    }

    private void createMailers() {
        final MailerRegularBuilder<?> builder = builder(servers.get(0).port, true);
        if (options.profile.equals("retry") || options.profile.equals("cancellation")) {
            builder.withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumAttempts(3)
                    .withRetryDelay(options.profile.equals("cancellation") ? Duration.ofHours(1) : Duration.ofMillis(2))
                    .withWaitingCapacity(options.inflight).build());
        }
        if (options.profile.equals("failover")) {
            failover = builder(servers.get(1).port, false).buildMailer();
            builder.withFailoverMailer(failover, FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofMillis(10)).build());
        }
        mailer = builder.buildMailer();
    }

    private MailerRegularBuilder<?> builder(final int port, final boolean observers) {
        final MailerRegularBuilder<?> builder = factory.mailerBuilder().withSMTPServer("127.0.0.1", port)
                .withTransportStrategy(TransportStrategy.SMTP).withTlsConfig(TlsConfig.builder().withOpportunisticTLS(false).build())
                .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("memory.example.test").withSessionTimeout(Duration.ofMillis(15000)).build()).withProperty("mail.host", "memory.example.test")
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withClaimTimeout(Duration.ofMillis(15000)).withCoreSize(0).withMaxSize(options.concurrency).withExpireAfter(Duration.ofMillis(3600000)).build())
                .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withThreadPoolSize(options.concurrency).withKeepAliveTime(Duration.ofMillis(3600000)).withQueueCapacity(options.inflight).build());
        if (observers) {
            final MailSendHandlers.Builder handlers = MailSendHandlers.builder();
            if (options.hasResultObserver()) {
                handlers.withResultObserver(this::recordResult);
            }
            if (options.hasProgressObserver()) {
                handlers.withProgressObserver(this::recordProgress);
            }
            builder.withSendHandlers(handlers.build());
        }
        return builder;
    }

    void sendWarmup() throws Exception {
        if (options.profile.equals("churn") && options.warmup > 0) {
            requireAccepted(mailer.sync().sendMail(nextEmail()));
            counters.completed++;
            sendBatch(options.warmup - 1, () -> { });
        } else {
            sendBatch(options.warmup, () -> { });
        }
        verifyDrainedBatch();
    }

    void beginMeasuredBatch() throws Exception {
        measuredBatches++;
        if (options.profile.equals("failover")) {
            primaryBeforeBatch = servers.get(0).statistics().accepted;
            secondaryBeforeBatch = servers.get(1).statistics().accepted;
            final boolean available = measuredBatches % 2 == 0;
            servers.get(0).setAvailable(available);
            scenarioTransitions++;
            if (available) {
                // An explicit timer respects the configured recovery interval; no send, pool or SMTP timing is inferred from an arbitrary sleep.
                CompletableFuture.runAsync(() -> { }, CompletableFuture.delayedExecutor(15, TimeUnit.MILLISECONDS)).get(5, TimeUnit.SECONDS);
            }
        }
    }

    void sendBatch(final int count, final Checkpoint loadedCheckpoint) throws Exception {
        if (options.profile.equals("cancellation")) {
            cancelWaitingRetries(count, loadedCheckpoint);
            return;
        }
        final Queue<CompletableFuture<MailSubmissionReceipt>> pending = new ArrayDeque<>();
        for (int index = 0; index < count; index++) {
            final Email email = nextEmail();
            if (options.profile.equals("churn")) {
                sendThroughNewMailer(email);
            } else if (options.sendsSynchronously()) {
                requireAccepted(mailer.sync().sendMail(email));
                counters.completed++;
            } else {
                pending.add(mailer.async().sendMail(email).getCompletion());
                if (pending.size() >= options.inflight) {
                    awaitAccepted(pending.remove());
                }
            }
            if (options.profile.equals("positive-control") && measuredBatches > 0) {
                positiveControl.add(email);
            }
        }
        loadedCheckpoint.sample();
        while (!pending.isEmpty()) {
            awaitAccepted(pending.remove());
        }
    }

    private Email nextEmail() {
        final long sequence = ++emailSequence;
        final EmailPopulatingBuilder builder = factory.emailBuilder().startingBlank().from("sender@example.test")
                .withRecipients(factory.recipientBuilder().withAddress("recipient-" + sequence + "@example.test").withType(RecipientType.TO).build())
                .fixingMessageId("<memory-" + sequence + "@example.test>").withSubject("Synthetic memory profile " + sequence)
                .withPlainText("Synthetic memory content " + sequence + ' ' + "x".repeat(options.textBytes));
        if (options.profile.equals("attachment-memory")) {
            builder.withAttachment("synthetic.bin", new byte[options.attachmentBytes], "application/octet-stream");
        } else if (attachment != null) {
            builder.withAttachment("synthetic.bin", new FileDataSource(attachment.toFile()));
        }
        return builder.buildEmail();
    }

    private void sendThroughNewMailer(final Email email) throws Exception {
        // Keep the ordinary Mailer alive so cluster churn cannot be hidden by replacing an entirely idle global pool engine.
        try (Mailer temporary = builder(servers.get(0).port, true).buildMailer()) {
            requireAccepted(temporary.sync().sendMail(email));
            counters.completed++;
        }
    }

    private void cancelWaitingRetries(final int count, final Checkpoint loadedCheckpoint) throws Exception {
        final int maximumConcurrent = Math.min(options.inflight, MAXIMUM_CONCURRENT_CANCELLATIONS);
        for (int reached = 0; reached < count; reached += Math.min(maximumConcurrent, count - reached)) {
            final int chunkSize = Math.min(maximumConcurrent, count - reached);
            cancelWaitingChunk(chunkSize, reached + chunkSize == count ? loadedCheckpoint : () -> { });
        }
        if (count == 0) {
            loadedCheckpoint.sample();
        }
    }

    private void cancelWaitingChunk(final int count, final Checkpoint loadedCheckpoint) throws Exception {
        final List<PendingCancellation> pending = new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) {
                submitCancellationCandidate(pending);
            }
            awaitAllRetryWaiters(pending);
            counters.maximumCancellationConcurrency = Math.max(counters.maximumCancellationConcurrency, pending.size());
            loadedCheckpoint.sample();
            pending.forEach(candidate -> candidate.send.requestCancellation());
            for (final PendingCancellation candidate : pending) {
                awaitCancelled(candidate.send);
            }
        } finally {
            // Failed scheduling, observation or sampling must not leave hour-long retries alive during Mailer shutdown.
            pending.forEach(candidate -> candidate.send.requestCancellation());
            pending.forEach(candidate -> retryWaiters.remove(candidate.messageId));
        }
    }

    private void submitCancellationCandidate(final List<PendingCancellation> pending) {
        final Email email = nextEmail();
        final CountDownLatch waiting = new CountDownLatch(1);
        retryWaiters.put(email.getId(), waiting);
        try {
            pending.add(new PendingCancellation(email.getId(), waiting, mailer.async().sendMail(email)));
        } catch (RuntimeException | Error failure) {
            retryWaiters.remove(email.getId());
            throw failure;
        }
    }

    private static void awaitAllRetryWaiters(final List<PendingCancellation> pending) throws Exception {
        // Each ID stays at WAITING_TO_RETRY until cancelled. At most 64 IDs wait together, below the dispatcher's retained-ID bound.
        final long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        for (final PendingCancellation candidate : pending) {
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !candidate.waiting.await(remaining, TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("Cancellation profile did not observe genuine retry waiting for " + candidate.messageId);
            }
        }
    }

    private void awaitCancelled(final MailSend<MailSubmissionReceipt> send) throws Exception {
        try {
            send.getCompletion().get(20, TimeUnit.SECONDS);
            throw new AssertionError("A cancelled retry completed successfully");
        } catch (ExecutionException failure) {
            if (!(failure.getCause() instanceof MailSendCancelledException)) {
                throw failure;
            }
            final MailSendCancelledException cancelled = (MailSendCancelledException) failure.getCause();
            if (cancelled.getSubmissionReceipt().isEmpty()
                    || cancelled.getSubmissionReceipt().get().getStatus() != MailSubmissionStatus.REJECTED) {
                throw new AssertionError("Retry cancellation lost its refused submission receipt", cancelled);
            }
            counters.expectedFailures++;
            counters.completed++;
        }
    }

    private void awaitAccepted(final CompletableFuture<MailSubmissionReceipt> completion) throws Exception {
        requireAccepted(completion.get(60, TimeUnit.SECONDS));
        counters.completed++;
    }

    private static void requireAccepted(final MailSubmissionReceipt receipt) {
        if (receipt.getStatus() != MailSubmissionStatus.ACCEPTED) {
            throw new AssertionError("The discard-only server did not accept the complete synthetic submission: " + receipt.getStatus());
        }
    }

    private void recordResult(final MailSendOutcome outcome) {
        counters.results.incrementAndGet();
        if (!outcome.isSuccessful() && (!options.profile.equals("cancellation")
                || !(outcome.getFailure().orElse(null) instanceof MailSendCancelledException))) {
            unexpectedOutcome.compareAndSet(null, outcome.getFailure().orElseGet(() -> new AssertionError("Unsuccessful outcome without a failure")));
        }
    }

    private void recordProgress(final MailSendProgress progress) {
        counters.progress.incrementAndGet();
        if (progress.getStage() == MailSendProgressStage.WAITING_TO_RETRY && progress.getInitialMessageId() != null) {
            final CountDownLatch waiter = retryWaiters.get(progress.getInitialMessageId());
            if (waiter != null) {
                waiter.countDown();
            }
        }
        if (options.profile.equals("slow-progress")) {
            try {
                Thread.sleep(options.observerDelayMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void verifyDrainedBatch() throws Exception {
        if (unexpectedOutcome.get() != null) {
            throw new AssertionError("The memory workload produced an unexpected failed outcome", unexpectedOutcome.get());
        }
        for (final MemorySmtpProcess server : servers) {
            if (server.statistics().pendingRetryKeys != 0) {
                throw new AssertionError("A drained retry fixture retained recipient bookkeeping");
            }
        }
        if (options.hasResultObserver() && counters.results.get() != counters.completed) {
            throw new AssertionError("Result callbacks and completed logical sends differ: " + counters.results + " versus " + counters.completed);
        }
        verifyFailoverDestination();
    }

    private void verifyFailoverDestination() throws Exception {
        if (!options.profile.equals("failover") || measuredBatches == 0) {
            return;
        }
        final long primaryDelta = servers.get(0).statistics().accepted - primaryBeforeBatch;
        final long secondaryDelta = servers.get(1).statistics().accepted - secondaryBeforeBatch;
        if (measuredBatches % 2 != 0) {
            if (primaryDelta != 0 || secondaryDelta <= 0) {
                throw new AssertionError("The unavailable primary must contribute no accepted submissions; the failover must handle this batch");
            }
        } else {
            // Concurrent sends may still use B while one operation owns A's recheck. Require actual recovery without inventing strict handoff ordering.
            if (primaryDelta <= 0) {
                throw new AssertionError("The recovered primary accepted no new work after its recheck interval");
            }
            recoveries++;
        }
    }

    void closeMailers() throws Exception {
        Exception failure = null;
        if (mailer != null) {
            final Mailer closing = mailer;
            mailer = null;
            try {
                closing.close();
            } catch (Exception cleanup) {
                failure = cleanup;
            }
        }
        if (failover != null) {
            final Mailer closing = failover;
            failover = null;
            try {
                closing.close();
            } catch (Exception cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    void writeVerifiedSummary() throws Exception {
        final long expected = options.warmup + (long) options.messages;
        final MemorySmtpProcess.Statistics primary = servers.get(0).statistics();
        primaryAccepted = primary.accepted;
        secondaryAccepted = servers.size() > 1 ? servers.get(1).statistics().accepted : 0;
        if (counters.completed != expected || primaryAccepted + secondaryAccepted != expected - counters.expectedFailures) {
            throw new AssertionError("Client and server logical-message counts disagree");
        }
        if (options.profile.equals("retry") && primary.refused != expected) {
            throw new AssertionError("The retry workload must refuse exactly one invocation for every logical send");
        }
        if (options.profile.equals("cancellation") && (primary.refused != expected || counters.expectedFailures != expected)) {
            throw new AssertionError("Every cancellation must follow one actual refused submission");
        }
        if (options.profile.equals("failover") && secondaryAccepted == 0) {
            throw new AssertionError("The failover workload never used its alternate destination");
        }
        final Map<String, Object> summary = new LinkedHashMap<>(MemoryPressureMeasurements.profileMetadata(options));
        summary.put("completed", counters.completed);
        summary.put("expectedFailures", counters.expectedFailures);
        summary.put("resultCallbacks", counters.results.get());
        summary.put("progressCallbacks", counters.progress.get());
        summary.put("serverAccepted", primaryAccepted + secondaryAccepted);
        summary.put("serverRefused", primary.refused);
        summary.put("primaryAccepted", primaryAccepted);
        summary.put("secondaryAccepted", secondaryAccepted);
        summary.put("scenarioTransitions", scenarioTransitions);
        summary.put("recoveries", recoveries);
        summary.put("cancellationConcurrency", counters.maximumCancellationConcurrency);
        summary.put("positiveControlRetainedEmails", positiveControl.size());
        summary.put("connections", connectionCount());
        final List<Long> serverPids = new ArrayList<>();
        servers.forEach(server -> serverPids.add(server.pid()));
        summary.put("serverPids", serverPids);
        summary.put("scenarioNote", scenarioNote());
        MemoryPressureMeasurements.writeJson(options.output.resolve("summary.json"), summary);
    }

    private long connectionCount() throws Exception {
        long connections = 0;
        for (final MemorySmtpProcess server : servers) {
            connections += server.statistics().connections;
        }
        return connections;
    }

    private String scenarioNote() {
        if (options.profile.equals("cancellation")) {
            return "Bounded concurrent cancellation after actual temporary DATA refusals and observed retry waiting; no submission is replayed. "
                    + "At most min(inflight, 64) observations wait together; cancellationConcurrency records the actual maximum.";
        }
        if (options.profile.equals("failover")) {
            return "Primary availability alternates at drained batch boundaries; only idle server connections are retired. Recovery is operation-led.";
        }
        if (options.profile.equals("churn")) {
            return "One Mailer remains live while each reached email creates, uses and closes another Mailer.";
        }
        return "Fresh synthetic Emails, bounded in-flight completions, separate discard-only SMTP heap; no delivery claim.";
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        try {
            closeMailers();
        } catch (Exception cleanup) {
            failure = cleanup;
        }
        for (int index = servers.size() - 1; index >= 0; index--) {
            try {
                servers.get(index).close();
            } catch (Exception cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @FunctionalInterface
    interface Checkpoint {
        void sample() throws Exception;
    }

    static final class Counters {
        final AtomicLong results = new AtomicLong();
        final AtomicLong progress = new AtomicLong();
        long completed;
        long expectedFailures;
        int maximumCancellationConcurrency;
    }

    private static final class PendingCancellation {
        private final String messageId;
        private final CountDownLatch waiting;
        private final MailSend<MailSubmissionReceipt> send;

        private PendingCancellation(final String messageId, final CountDownLatch waiting, final MailSend<MailSubmissionReceipt> send) {
            this.messageId = messageId;
            this.waiting = waiting;
            this.send = send;
        }
    }
}
