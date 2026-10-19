package org.simplejavamail.mailer.retry;

import org.simplejavamail.api.mailer.MailSendHandlers;
import jakarta.mail.Message.RecipientType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import jakarta.activation.DataSource;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendTimeoutException;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.recipient.internal.RecipientBuilderImpl;
import testutil.smtp.RecordingSmtpServer;
import testutil.smtp.SmtpConversation;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static java.util.stream.Collectors.toList;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

/** Uses only the public API and SMTP wire ledger: the retry engine remains a black box. */
@Timeout(25)
class MailSendRetrySmtpContractTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    private MailerRegularBuilder<?> builder(final int port) {
        // Retry barriers measure sending, not a workstation DNS lookup while generating the MIME Message-ID.
        return mail.mailerBuilder().withSMTPServer("localhost", port).withTransportStrategy(TransportStrategy.SMTP)
                .withOpportunisticTLS(false).withSmtpClientHostname("probe.example.test").withProperty("mail.host", "localhost").withSessionTimeout(Duration.ofMillis(5000))
                .withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).withConnectionPoolExpireAfter(Duration.ofMillis(60000)).withThreadPoolSize(1);
    }

    private MailSendRetryPolicy policy(final Duration delay) {
        return MailSendRetryPolicy.builder().withMaximumAttempts(3).withRetryDelay(delay).build();
    }

    private Email email() {
        return mail.emailBuilder().startingBlank().from("sender@example.test")
                .withRecipients(SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress("receiver@example.test").withType(RecipientType.TO).build())
                .withSubject("retry contract").withPlainText("frozen content").buildEmail();
    }

    @Test
    void temporaryDataRefusalReplaysIdenticalBytesAndPublishesOneOutcome() throws Exception {
        final AtomicInteger submissions = new AtomicInteger();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250 localhost");
            connection.acceptMessages(peer, ignored -> submissions.incrementAndGet() < 3 ? "451 retry later" : "250 queued");
        }); Mailer mailer = builder(server.port()).withMailSendRetryPolicy(policy(Duration.ofMillis(5)))
                .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer()) {
            final MailSubmissionReceipt receipt = mailer.sync().sendMail(email());
            assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            assertThat(server.messages()).hasSize(3);
            assertThat(server.messages().get(1)).isEqualTo(server.messages().get(0));
            assertThat(server.messages().get(2)).isEqualTo(server.messages().get(0));
            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(receipt);
            assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getSubmissionHistory()).hasSize(3);
        }
    }

    @Test
    void permanentOrUncertainSubmissionIsNeverReplayed() throws Exception {
        for (String reply : new String[] { "550 rejected", null }) {
            try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
                connection.greet(peer, "250 localhost");
                connection.acceptMessages(peer, reply);
            }); Mailer mailer = builder(server.port()).withMailSendRetryPolicy(policy(Duration.ofMillis(5))).buildMailer()) {
                assertThatThrownBy(() -> mailer.sync().sendMail(email())).isInstanceOf(MailSubmissionException.class);
                assertThat(server.messages()).hasSize(1);
            }
        }
    }

    @Test
    void waitingAsyncRetryReleasesItsWorkerAndLeaseAndDrainsDuringShutdown() throws Exception {
        final AtomicInteger submissions = new AtomicInteger();
        final CountDownLatch firstRefused = new CountDownLatch(1);
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250 localhost");
            connection.acceptMessages(peer, ignored -> {
                if (submissions.incrementAndGet() == 1) {
                    firstRefused.countDown();
                    return "451 retry later";
                }
                return "250 queued";
            });
        }); Mailer mailer = builder(server.port()).withMailSendRetryPolicy(policy(Duration.ofSeconds(6))).buildMailer()) {
            final MailSend<MailSubmissionReceipt> delayed = mailer.async().sendMail(email());
            assertThat(firstRefused.await(5, SECONDS)).isTrue();
            final MailSubmissionReceipt unrelated = mailer.async().sendMail(email()).getCompletion().get(5, SECONDS);
            assertThat(unrelated.getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            assertThat(delayed.getCompletion()).isNotDone();
            final Future<Void> closing = mailer.closeAsync();
            assertThat(closing.isDone()).isFalse();
            assertThat(delayed.getCompletion().get(10, SECONDS).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            closing.get(5, SECONDS);
            assertThat(server.messages()).hasSize(3);
        }
    }

    @Test
    void retainedBatchRetriesTheCurrentEmailBeforeAdvancingTheIterator() throws Exception {
        final AtomicInteger submissions = new AtomicInteger();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250 localhost");
            connection.acceptMessages(peer, ignored -> submissions.incrementAndGet() == 1 ? "451 retry later" : "250 queued");
        }); Mailer mailer = builder(server.port()).withMailSendRetryPolicy(policy(Duration.ofMillis(5)))
                .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer()) {
            mailer.sync().sendMailsInSimpleBatch(List.of(email(), email()));
            assertThat(server.connectionCount()).isEqualTo(1);
            assertThat(server.messages()).hasSize(3);
            assertThat(server.messages().get(0)).isEqualTo(server.messages().get(1));
            assertThat(outcomes).hasSize(2);
            assertThat(server.commands()).doesNotContain("NOOP");
        }
    }

    @Test
    void cancellationAndDeadlineDuringBackoffRetainTheRefusedReceipt() throws Exception {
        for (boolean cancel : new boolean[] { true, false }) {
            final CountDownLatch refused = new CountDownLatch(1);
            final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
            try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
                connection.greet(peer, "250 localhost");
                connection.acceptMessages(peer, ignored -> {
                    refused.countDown();
                    return "451 not now";
                });
            }); Mailer mailer = builder(server.port()).withMailSendRetryPolicy(policy(Duration.ofHours(1)))
                    .withMailSendTimeout(Duration.ofSeconds(8)).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer()) {
                final MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(email());
                assertThat(refused.await(12, SECONDS)).as("commands=%s; outcomes=%s", server.commands(),
                        outcomes.stream().map(outcome -> outcome.getFailure().map(Throwable::toString).orElse("success")).toArray()).isTrue();
                mailer.getOperationalConfig().getExecutorService().submit(() -> { }).get(5, SECONDS);
                if (cancel) { send.requestCancellation(); }
                final Throwable failure = send.getCompletion().handle((value, cause) -> cause).get(12, SECONDS);
                assertThat(failure).isInstanceOf(cancel ? MailSendCancelledException.class : MailSendTimeoutException.class);
                assertThat(outcomes).hasSize(1);
                final MailSubmissionReceipt captured = outcomes.get(0).getSubmissionReceipt().orElseThrow();
                assertThat(captured.getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
                assertThat(outcomes.get(0).getFailure()).containsSame(failure);
                assertThat(cancel ? ((MailSendCancelledException) failure).getSubmissionReceipt()
                        : ((MailSendTimeoutException) failure).getSubmissionReceipt()).containsSame(captured);
                assertThat(server.messages()).hasSize(1);
            }
        }
    }

    @Test
    void replaySizeFailureOccursBeforeMailFromAndDoesNotLeakMemory() throws Exception {
        try (RecordingSmtpServer server = new RecordingSmtpServer();
             Mailer mailer = builder(server.port()).withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumAttempts(3)
                     .withRetryDelay(Duration.ofMillis(5)).withMaximumMessageSize(100).withMemoryLimit(100).build()).buildMailer()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(() -> mailer.sync().sendMail(email())).hasMessageContaining("replay limit");
                assertThat(server.commands()).noneMatch(command -> command.startsWith("MAIL FROM:"));
            }
        }
    }

    @Test
    void selectedFailoverMailerOwnsPolicyAndDoesNotInheritInitiatorsPolicy() throws Exception {
        for (boolean selectedHasPolicy : new boolean[] { true, false }) {
            final AtomicInteger submissions = new AtomicInteger();
            try (RecordingSmtpServer unavailable = new RecordingSmtpServer((peer, connection) -> { peer.close(); });
                 RecordingSmtpServer destination = new RecordingSmtpServer((peer, connection) -> {
                     connection.greet(peer, "250 localhost");
                     connection.acceptMessages(peer, ignored -> submissions.incrementAndGet() == 1 ? "451 temporary" : "250 queued");
                 })) {
                final MailerRegularBuilder<?> selectedBuilder = builder(destination.port());
                if (selectedHasPolicy) { selectedBuilder.withMailSendRetryPolicy(policy(Duration.ofMillis(5))); }
                try (Mailer selected = selectedBuilder.buildMailer()) {
                    final MailerRegularBuilder<?> sourceBuilder = builder(unavailable.port()).withFailoverMailer(selected, FailoverPolicy.builder().build());
                    if (!selectedHasPolicy) { sourceBuilder.withMailSendRetryPolicy(policy(Duration.ofMillis(5))); }
                    try (Mailer source = sourceBuilder.buildMailer()) {
                        if (selectedHasPolicy) {
                            assertThat(source.sync().sendMail(email()).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
                        } else {
                            assertThatThrownBy(() -> source.sync().sendMail(email())).isInstanceOf(MailSubmissionException.class);
                        }
                        assertThat(destination.messages()).hasSize(selectedHasPolicy ? 2 : 1);
                    }
                }
            }
        }
    }

    @Test
    void initiatingResourceOnlyPolicyBoundsReplayWhileSelectedPolicySuppliesTheSchedule() throws Exception {
        for (boolean sourceCanRetainMessage : new boolean[] {false, true}) {
            final AtomicInteger submissions = new AtomicInteger();
            try (RecordingSmtpServer unavailable = new RecordingSmtpServer((peer, connection) -> peer.close());
                 RecordingSmtpServer destination = new RecordingSmtpServer((peer, connection) -> {
                     connection.greet(peer, "250 localhost");
                     connection.acceptMessages(peer, ignored -> submissions.incrementAndGet() == 1 ? "451 temporary" : "250 queued");
                 });
                 Mailer selected = builder(destination.port()).withMailSendRetryPolicy(MailSendRetryPolicy.builder()
                         .withMaximumAttempts(3).withRetryDelay(Duration.ofMillis(5)).withMaximumMessageSize(100).withMemoryLimit(100).build())
                         .buildMailer();
                 Mailer source = builder(unavailable.port()).withFailoverMailer(selected, FailoverPolicy.builder().build())
                         .withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumMessageSize(sourceCanRetainMessage ? 8192 : 100)
                                 .withMemoryLimit(sourceCanRetainMessage ? 8192 : 100).build()).buildMailer()) {
                assertThat(source.getOperationalConfig().getMailSendRetryPolicy().isRetryEnabled()).isFalse();
                if (sourceCanRetainMessage) {
                    assertThat(source.async().sendMail(email()).getCompletion().get(10, SECONDS).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
                    assertThat(destination.messages()).hasSize(2);
                } else {
                    assertThatThrownBy(() -> source.sync().sendMail(email())).hasMessageContaining("replay limit");
                    assertThat(destination.commands()).noneMatch(command -> command.startsWith("MAIL FROM:"));
                }
            }
        }
    }

    @Test
    void openConnectionCallbackIsInvokedOnceAndRetriesStayInsideIt() throws Exception {
        final AtomicInteger submissions = new AtomicInteger();
        final AtomicInteger callbacks = new AtomicInteger();
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250 localhost");
            connection.acceptMessages(peer, ignored -> submissions.incrementAndGet() == 1 ? "451 temporary" : "250 queued");
        }); Mailer mailer = builder(server.port()).withMailSendRetryPolicy(policy(Duration.ofMillis(5))).buildMailer()) {
            mailer.withOpenConnection(sender -> {
                callbacks.incrementAndGet();
                assertThat(sender.sendMail(email()).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
                sender.sendMail(email());
            });
            assertThat(callbacks).hasValue(1);
            assertThat(server.connectionCount()).isEqualTo(1);
            assertThat(server.messages()).hasSize(3);
            assertThat(server.messages().get(0)).isEqualTo(server.messages().get(1));
        }
    }

    @Test
    void exactReplayKeepsEveryByteAndComposedReplayDoesNotReReadAttachments() throws Exception {
        final byte[] eml = ("From: sender@example.test\r\nTo: receiver@example.test\r\nBcc: retained@example.test\r\n"
                + "Message-ID: <exact-retry@example.test>\r\nSubject: exact retry\r\nContent-Type: text/plain\r\n\r\nexact body\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        final AtomicInteger attempts = new AtomicInteger();
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250 localhost");
            connection.acceptMessages(peer, ignored -> attempts.incrementAndGet() < 3 ? "451 temporary" : "250 queued");
        }); Mailer mailer = builder(server.port()).withMailSendRetryPolicy(policy(Duration.ofMillis(5))).buildMailer()) {
            final Email exact = mail.emailBuilder().startingFromExactEml(eml).withEnvelopeSender("sender@example.test")
                    .withEnvelopeRecipients("receiver@example.test").buildEmail();
            assertThat(mailer.sync().sendMail(exact).getEmailId()).isEqualTo("<exact-retry@example.test>");
            assertThat(server.messages()).hasSize(3).allSatisfy(bytes -> assertThat(bytes).isEqualTo(eml));
        }
        final int readsWithoutRefusal = attachmentReadsAfterTemporaryRefusals(0);
        final int readsWithRetries = attachmentReadsAfterTemporaryRefusals(2);
        assertThat(readsWithRetries).isEqualTo(readsWithoutRefusal);
    }

    @Test
    void automaticEnvelopeIdsChangePerTransactionButFixedIdsRemainFixed() throws Exception {
        for (boolean fixed : new boolean[] { false, true }) {
            final AtomicInteger attempts = new AtomicInteger();
            try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
                connection.greet(peer, "250-localhost\r\n250 DSN");
                connection.acceptMessages(peer, ignored -> attempts.incrementAndGet() < 3 ? "451 temporary" : "250 queued");
            }); Mailer mailer = builder(server.port()).withMailSendRetryPolicy(policy(Duration.ofMillis(5))).buildMailer()) {
                final var source = mail.emailBuilder().startingBlank().from("sender@example.test")
                        .withRecipients(SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress("receiver@example.test").withType(RecipientType.TO).build())
                        .withPlainText("body").withDeliveryStatusNotificationNotifyOptions(
                                DeliveryStatusNotification.NotifyOption.FAILURE);
                if (fixed) { source.fixingEnvelopeId("fixed-envelope"); }
                final var receipt = mailer.sync().sendMail(source.buildEmail());
                final List<String> identifiers = server.commands().stream().filter(command -> command.startsWith("MAIL FROM:"))
                        .map(command -> command.replaceAll(".* ENVID=([^ ]+).*", "$1")).collect(toList());
                assertThat(identifiers).hasSize(3);
                if (fixed) { assertThat(identifiers).containsOnly("fixed-envelope"); }
                else { assertThat(new HashSet<>(identifiers)).hasSize(3); }
                assertThat(receipt.getEnvelopeId()).isEqualTo(identifiers.get(2));
                assertThat(server.messages()).hasSize(3).allSatisfy(bytes -> assertThat(bytes).isEqualTo(server.messages().get(0)));
            }
        }
    }

    @Test
    void temporaryRecipientRefusalCanRetryWithoutTransmittingFirstAttemptContent() throws Exception {
        final AtomicInteger recipientCommands = new AtomicInteger();
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250 localhost");
            serveRecipientReplies(peer, connection, (recipientPeer, command) ->
                    recipientPeer.reply(recipientCommands.incrementAndGet() == 1 ? "450 mailbox busy" : "250 ok"));
        }); Mailer mailer = builder(server.port()).withMailSendRetryPolicy(policy(Duration.ofMillis(5))).buildMailer()) {
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            assertThat(recipientCommands).hasValue(2);
            assertThat(server.messages()).hasSize(1);
            assertThat(server.commands()).filteredOn(command -> command.equals("DATA")).hasSize(1);
        }
    }

    @Test
    void enablingRetryDoesNotMakeDedicatedScopesSelectAnotherClusterMember() throws Exception {
        final UUID cluster = UUID.randomUUID();
        try (RecordingSmtpServer first = new RecordingSmtpServer(); RecordingSmtpServer second = new RecordingSmtpServer();
             Mailer initiating = builder(first.port()).withClusterKey(cluster)
                     .withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy.ROUND_ROBIN)
                     .withMailSendRetryPolicy(policy(Duration.ofMillis(5))).buildMailer();
             Mailer member = builder(second.port()).withClusterKey(cluster).buildMailer()) {
            initiating.sync().sendMailsInSimpleBatch(List.of(email(), email()));
            initiating.withOpenConnection(sender -> {
                sender.sendMail(email());
                sender.sendMail(email());
            });
            assertThat(first.messages()).hasSize(4);
            assertThat(second.messages()).isEmpty();
            assertThat(first.connectionCount()).isEqualTo(2);
        }
    }

    @Test
    void retained421RefusalDoesNotWaitOrReplaceItsConnection() throws Exception {
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250 localhost");
            connection.acceptMessages(peer, "421 service closing");
        }); Mailer mailer = builder(server.port()).withMailSendRetryPolicy(policy(Duration.ofHours(1))).buildMailer()) {
            assertThatThrownBy(() -> mailer.sync().sendMailsInSimpleBatch(List.of(email(), email())))
                    .isInstanceOf(MailSubmissionException.class);
            assertThat(server.messages()).hasSize(1);
            assertThat(server.connectionCount()).isEqualTo(1);
            assertThat(server.commands()).doesNotContain("NOOP");
        }
    }

    @Test
    void concurrentPooledSendsKeepTheirRefusalsAndHistoriesSeparate() throws Exception {
        final AtomicInteger submissions = new AtomicInteger();
        final CountDownLatch firstRefusals = new CountDownLatch(2);
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final Email original = mail.emailBuilder().startingBlank().from("sender@example.test")
                .withRecipients(SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress("receiver@example.test").withType(RecipientType.TO).build())
                .fixingMessageId("<concurrent-retry@example.test>").fixingSentDate(Instant.EPOCH)
                .withPlainText("same immutable email").buildEmail();
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250 localhost");
            connection.acceptMessages(peer, ignored -> {
                if (submissions.incrementAndGet() > 2) { return "250 queued"; }
                firstRefusals.countDown();
                // Both connections must reach the refusal before either send can recover and reuse state.
                try {
                    assertThat(firstRefusals.await(10, SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                return "451 temporarily refused";
            });
        }); Mailer mailer = builder(server.port()).withThreadPoolSize(4).withConnectionPoolMaxSize(2)
                .withMailSendRetryPolicy(policy(Duration.ofMillis(5))).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer()) {
            final List<MailSend<MailSubmissionReceipt>> sends = new ArrayList<>();
            for (int index = 0; index < 8; index++) { sends.add(mailer.async().sendMail(original)); }
            for (MailSend<MailSubmissionReceipt> send : sends) {
                assertThat(send.getCompletion().get(10, SECONDS).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            }
            assertThat(outcomes).hasSize(8).allSatisfy(outcome -> {
                assertThat(outcome.isSuccessful()).isTrue();
                assertThat(outcome.getFailure()).isEmpty();
                assertThat(outcome.getDiagnostics().orElseThrow().getSubmissionHistory().get(0).getAttemptNumber()).isEqualTo(1);
            });
            assertThat(outcomes.stream().filter(outcome -> outcome.getDiagnostics().orElseThrow().getSubmissionHistory().size() == 2)).hasSize(2);
            assertThat(server.messages()).hasSize(10).allSatisfy(bytes -> assertThat(bytes).isEqualTo(server.messages().get(0)));
            assertThat(server.connectionCount()).isEqualTo(4); // Existing pool health rules invalidate both refused attempts.
        }
    }

    @Test
    @Timeout(35)
    void recoveredSendReleasesItsLeaseBeforeAReentrantObserver() throws Exception {
        final AtomicInteger submissions = new AtomicInteger();
        final AtomicInteger observed = new AtomicInteger();
        final AtomicReference<Mailer> owner = new AtomicReference<>();
        final AtomicReference<MailSubmissionReceipt> nested = new AtomicReference<>();
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250 localhost");
            connection.acceptMessages(peer, ignored -> submissions.incrementAndGet() == 1 ? "451 refused" : "250 queued");
        }); Mailer mailer = builder(server.port()).withMailSendRetryPolicy(policy(Duration.ofMillis(5))).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcome -> {
            assertThat(outcome.isSuccessful()).isTrue();
            if (observed.incrementAndGet() == 1) { nested.set(owner.get().sync().sendMail(email())); }
        }).build()).buildMailer()) {
            owner.set(mailer);
            final MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(email());
            try {
                // Three local submissions each retain the configured five-second socket bound; this checks ownership, not throughput.
                assertThat(send.getCompletion().get(20, SECONDS).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            } catch (TimeoutException timeout) {
                throw new AssertionError(describeBlockedSend(server), timeout);
            }
            assertThat(nested.get().getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            assertThat(observed).hasValue(2);
            assertThat(server.messages()).hasSize(3);
            assertThat(server.connectionCount()).isEqualTo(2); // Recovery replaces the refused lease; the observer reuses the healthy one.
        }
    }

    @Test
    void composedSignedContentIsProtectedOnceAndReplayedByteForByte() throws Exception {
        final KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(1024);
        // Reuse the established DNS-free signing fixture identity; this is a loopback content-preservation test.
        final Email protectedEmail = mail.emailBuilder().startingBlank().from("sender@supersecret-testing-domain.com")
                .withRecipients(SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress("receiver@example.test").withType(RecipientType.TO).build())
                .withPlainText("signed retry body").signWithSmime(SmimeSigningConfig.builder().pkcs12Config(loadPkcs12KeyStore()).build())
                .signWithDomainKey(DkimConfig.builder().dkimPrivateKeyData(keys.generateKeyPair().getPrivate().getEncoded())
                        .dkimSigningDomain("supersecret-testing-domain.com").dkimSelector("retry").build()).buildEmail();
        final AtomicInteger submissions = new AtomicInteger();
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250 localhost");
            connection.acceptMessages(peer, ignored -> submissions.incrementAndGet() == 1 ? "451 refused" : "250 queued");
        }); Mailer mailer = builder(server.port()).withMailSendRetryPolicy(policy(Duration.ofMillis(5))).buildMailer()) {
            assertThat(mailer.sync().sendMail(protectedEmail).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            assertThat(server.messages()).hasSize(2);
            assertThat(server.messages().get(1)).isEqualTo(server.messages().get(0));
            assertThat(new String(server.messages().get(0), StandardCharsets.ISO_8859_1))
                    .contains("DKIM-Signature:", "application/pkcs7-signature");
        }
    }

    @Test
    void exhaustingTheScheduleRetainsTheFinalRealFailureInBothModes() throws Exception {
        for (boolean asynchronous : new boolean[] { false, true }) {
            final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
            try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
                connection.greet(peer, "250 localhost");
                connection.acceptMessages(peer, "451 still refused");
            }); Mailer mailer = builder(server.port()).withMailSendRetryPolicy(policy(Duration.ofMillis(1)))
                    .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer()) {
                final Throwable failure = asynchronous
                        ? mailer.async().sendMail(email()).getCompletion().handle((value, cause) -> cause).get(10, SECONDS)
                        : catchThrowable(() -> mailer.sync().sendMail(email()));
                assertThat(failure).isInstanceOf(MailSubmissionException.class);
                assertThat(server.messages()).hasSize(3);
                assertThat(outcomes).hasSize(1);
                assertThat(outcomes.get(0).getFailure()).containsSame(failure);
                assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(((MailSubmissionException) failure).getSubmissionReceipt().orElseThrow());
                final var history = outcomes.get(0).getDiagnostics().orElseThrow().getSubmissionHistory();
                assertThat(history).hasSize(3);
                assertThat(history.get(2).getAttemptNumber()).isEqualTo(3);
                assertThat(history.get(2).getRetryDelay()).isEmpty();
            }
        }
    }

    @Test
    void oneRefusedMailboxDoesNotDelayAnotherMailboxOnTheSameMailer() throws Exception {
        final AtomicInteger aliceRecipients = new AtomicInteger();
        final CountDownLatch aliceRefused = new CountDownLatch(1);
        final Email alice = mail.emailBuilder().startingBlank().from("sender@example.test")
                .withRecipients(SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress("alice@example.test").withType(RecipientType.TO).build()).withPlainText("Alice's email").buildEmail();
        final Email bob = mail.emailBuilder().startingBlank().from("sender@example.test")
                .withRecipients(SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress("bob@example.test").withType(RecipientType.TO).build()).withPlainText("Bob's email").buildEmail();
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250 localhost");
            serveRecipientReplies(peer, connection, (recipientPeer, command) -> {
                if (command.contains("alice@example.test") && aliceRecipients.incrementAndGet() == 1) {
                    recipientPeer.reply("450 Alice is busy");
                    aliceRefused.countDown();
                } else {
                    recipientPeer.reply("250 ok");
                }
            });
        }); Mailer mailer = builder(server.port()).withMailSendRetryPolicy(policy(Duration.ofSeconds(10))).buildMailer()) {
            final MailSend<MailSubmissionReceipt> delayedAlice = mailer.async().sendMail(alice);
            assertThat(aliceRefused.await(5, SECONDS)).isTrue();
            final MailSubmissionReceipt bobReceipt = mailer.async().sendMail(bob).getCompletion().get(5, SECONDS);
            assertThat(bobReceipt.getAcceptedRecipients()).containsExactly("bob@example.test");
            assertThat(delayedAlice.getCompletion()).isNotDone();
            assertThat(delayedAlice.getCompletion().get(15, SECONDS).getAcceptedRecipients()).containsExactly("alice@example.test");
            assertThat(server.messages()).hasSize(2);
        }
    }

    private int attachmentReadsAfterTemporaryRefusals(final int refusedAttempts) throws Exception {
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger submissions = new AtomicInteger();
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250 localhost");
            connection.acceptMessages(peer, ignored -> submissions.incrementAndGet() <= refusedAttempts ? "451 temporary" : "250 queued");
        }); Mailer mailer = builder(server.port()).withMailSendRetryPolicy(policy(Duration.ofMillis(5))).buildMailer()) {
            final Email composed = mail.emailBuilder().startingBlank().from("sender@example.test")
                    .withRecipients(SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress("receiver@example.test").withType(RecipientType.TO).build(), SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress("archive@example.test").withType(RecipientType.BCC).build())
                    .withAttachment("report.bin", countingAttachment(reads)).withPlainText("body").buildEmail();
            mailer.sync().sendMail(composed);
            assertThat(server.messages()).hasSize(refusedAttempts + 1).allSatisfy(bytes ->
                    assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).doesNotContain("Bcc:"));
            assertThat(server.commands()).anyMatch(command -> command.contains("archive@example.test"));
            return reads.get();
        }
    }

    private static DataSource countingAttachment(final AtomicInteger reads) {
        return new DataSource() {
            @Override public InputStream getInputStream() {
                reads.incrementAndGet();
                return new ByteArrayInputStream(new byte[] { 1, 2, 3, 4 });
            }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "application/octet-stream"; }
            @Override public String getName() { return "report"; }
        };
    }

    /** Keep protocol plumbing out of the mailbox-specific behavior being asserted by each test. */
    private static void serveRecipientReplies(final SmtpConversation peer, final RecordingSmtpServer.Connection connection,
            final BiConsumer<SmtpConversation, String> recipientReply) throws Exception {
        String command;
        while ((command = connection.readCommand(peer)) != null) {
            if (command.startsWith("MAIL FROM:") || command.equals("RSET") || command.equals("NOOP")) {
                peer.reply("250 ok");
            } else if (command.startsWith("RCPT TO:")) {
                recipientReply.accept(peer, command);
            } else if (command.equals("DATA")) {
                peer.reply("354 content");
                connection.receiveMessage(peer);
                peer.reply("250 queued");
            } else if (command.equals("QUIT")) {
                peer.reply("221 bye");
                return;
            } else {
                throw new AssertionError(command);
            }
        }
    }

    private static String describeBlockedSend(final RecordingSmtpServer server) {
        final StringBuilder blockedThreads = new StringBuilder("SMTP commands: ").append(server.commands());
        Thread.getAllStackTraces().forEach((thread, frames) -> {
            if (thread.getName().contains("Simple Java Mail") || thread.getName().contains("sjm-retry")) {
                blockedThreads.append('\n').append(thread.getName()).append(": ").append(Arrays.toString(frames));
            }
        });
        return blockedThreads.toString();
    }
}
