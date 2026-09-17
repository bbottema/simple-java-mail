package org.simplejavamail.mailer.internal;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.mailer.internal.SmtpCapabilityProbeCharacterizationTest.Conversation;
import testutil.ConfigLoaderTestHelper;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.REJECTED;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.UNKNOWN;
import static org.simplejavamail.api.mailer.MailRetryDisposition.DUPLICATE_RISK;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.acceptMessage;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.acceptMessageAfterMailFrom;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.readGeneratedEnvelopeId;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.readMessage;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.expectCommand;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.finishConnection;

/** Exercises ENVID through real loopback SMTP connections, including the pool's actual send path. */
@Timeout(40)
class SmtpEnvelopeIdTest {

    private static final String MAIL_FROM = "MAIL FROM:<sender@example.test>";
    private static final String RECIPIENT = "receiver@example.test";
    private static final String DSN = "250-localhost\r\n250 DSN";
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void localFailureBetweenSubmissionsDoesNotReportAnUnusedOrPreviousIdentifier(final boolean callerOwnedSession, final boolean fixedIdentifier)
            throws Exception {
        final String identifier = fixedIdentifier ? "fixed-attempt" : null;
        final List<String> wireIdentifiers = new CopyOnWriteArrayList<>();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(DSN);
            for (int index = 0; index < 2; index++) {
                if (fixedIdentifier) {
                    expectCommand(peer, MAIL_FROM + " ENVID=" + identifier);
                    wireIdentifiers.add(identifier);
                } else {
                    wireIdentifiers.add(readGeneratedEnvelopeId(peer, MAIL_FROM));
                }
                acceptMessageAfterMailFrom(peer, "", RECIPIENT);
            }
            finishConnection(peer);
        })) {
            final Properties properties = SmtpCapabilityProbeCharacterizationTest.session(false).getProperties();
            properties.setProperty("mail.transport.protocol", "smtp");
            properties.setProperty("mail.smtp.host", "localhost");
            properties.setProperty("mail.smtp.port", Integer.toString(server.port()));
            final MailerGenericBuilder<?> configured = callerOwnedSession ? mail.mailerBuilder(Session.getInstance(properties)) : builder(server);
            try (Mailer mailer = configured.withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1)
                    .disablingAllClientValidation(true).withMailSendObserver(outcomes::add).buildMailer()) {
                final Email withoutRecipients = mail.emailBuilder().startingBlank().from("sender@example.test")
                        .withPlainText("Synthetic local failure.").fixingEnvelopeId(identifier).buildEmail();
                mailer.withOpenConnection(sender -> {
                    final MailSubmissionReceipt first = sender.sendMailAndGetReceipt(email(identifier));
                    final Throwable failure = catchThrowable(() -> sender.sendMailAndGetReceipt(withoutRecipients));
                    assertThat(failure).isInstanceOf(MailSubmissionException.class);
                    assertThat(failure.getCause()).hasMessage("No recipient addresses");
                    final MailSubmissionReceipt notSubmitted = ((MailSubmissionException) failure).getSubmissionReceipt();
                    assertThat(notSubmitted.getEnvelopeId()).isNull();
                    assertThat(notSubmitted.getSmtpResponse()).isEmpty();
                    assertThat(notSubmitted.getRecipientResults()).isEmpty();
                    assertThat(outcomes.get(1).getSubmissionReceipt()).containsSame(notSubmitted);
                    final MailSubmissionReceipt last = sender.sendMailAndGetReceipt(email(identifier));
                    assertThat(List.of(first, last)).extracting(MailSubmissionReceipt::getEnvelopeId).containsExactlyElementsOf(wireIdentifiers);
                });
                assertThat(outcomes).hasSize(3);
                if (!fixedIdentifier) {
                    assertThat(wireIdentifiers).doesNotHaveDuplicates();
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void consecutivePooledSendsKeepTheirOwnIdentifiersAndObserverReceipts(final boolean asynchronous) throws Exception {
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final AtomicReference<String> generatedIdentifier = new AtomicReference<>();
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(DSN);
            final String content = acceptMessage(peer, MAIL_FROM + " RET=HDRS ENVID=order+20+2B42+3D", " NOTIFY=FAILURE,DELAY", RECIPIENT);
            assertThat(content).doesNotContain("ENVID=", "Original-Envelope-ID:", "NOTIFY=");
            acceptMessage(peer, MAIL_FROM + " ENVID=second", "", RECIPIENT);
            generatedIdentifier.set(readGeneratedEnvelopeId(peer, MAIL_FROM));
            acceptMessageAfterMailFrom(peer, "", RECIPIENT);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withMailSendObserver(outcomes::add).buildMailer()) {
            final Email first = mail.emailBuilder().copying(email("order +42="))
                    .withDeliveryStatusNotificationNotifyOptions(NotifyOption.FAILURE, NotifyOption.DELAY)
                    .withDeliveryStatusNotificationReturnOption(ReturnOption.HEADERS_ONLY).buildEmail();
            final List<MailSubmissionReceipt> receipts = List.of(send(mailer, first, asynchronous), send(mailer, email("second"), asynchronous),
                    send(mailer, email(null), asynchronous));
            assertThat(outcomes).hasSize(3);
            assertThat(receipts).extracting(MailSubmissionReceipt::getEnvelopeId).containsExactly("order +42=", "second", generatedIdentifier.get());
            for (int index = 0; index < receipts.size(); index++) {
                assertThat(receipts.get(index).getStatus()).isEqualTo(ACCEPTED);
                assertThat(outcomes.get(index).getSubmissionReceipt()).containsSame(receipts.get(index));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void missingOrMalformedDsnRejectsBeforeMailFrom(final boolean malformed, final boolean asynchronous) throws Exception {
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(malformed ? "250-localhost\r\n250 DSN unsupported-parameters" : "250 localhost");
            finishConnection(peer); // A pre-submission capability mismatch leaves the lease healthy.
        }); Mailer mailer = builder(server).withMailSendObserver(outcomes::add).buildMailer()) {
            final Throwable thrown = catchThrowable(() -> send(mailer, email("must-not-disappear"), asynchronous));
            if (asynchronous) {
                assertThat(thrown).isInstanceOf(ExecutionException.class);
            }
            final Throwable failure = asynchronous ? thrown.getCause() : thrown;
            assertThat(failure).isInstanceOf(MailSubmissionException.class).hasCauseInstanceOf(MessagingException.class);
            assertThat(failure.getCause()).hasMessageContaining("does not advertise usable DSN support").hasMessageContaining("No message was submitted");
            final MailSubmissionReceipt receipt = ((MailSubmissionException) failure).getSubmissionReceipt();
            assertThat(receipt.getStatus()).isEqualTo(REJECTED);
            assertThat(receipt.getEnvelopeId()).isNull();
            assertThat(receipt.getSmtpResponse()).isEmpty();
            assertThat(receipt.getAcceptedRecipients()).isEmpty();
            assertThat(receipt.getValidUnsentRecipients()).containsExactly(RECIPIENT);
            assertThat(outcomes).singleElement().satisfies(outcome -> {
                assertThat(outcome.getFailure()).containsSame(failure);
                assertThat(outcome.getSubmissionReceipt()).containsSame(receipt);
            });
        }
    }

    @Test
    void openConnectionCanContinueAfterMissingDsnWithoutLeakingThePreviousReply() throws Exception {
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet("250 localhost");
            acceptMessage(peer, MAIL_FROM, "", RECIPIENT);
            acceptMessage(peer, MAIL_FROM, "", RECIPIENT);
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            mailer.withOpenConnection(sender -> {
                sender.sendMail(email(null));
                final Throwable failure = catchThrowable(() -> sender.sendMail(email("unsupported")));
                assertThat(failure).isInstanceOf(MailSubmissionException.class);
                assertThat(((MailSubmissionException) failure).getSubmissionReceipt().getSmtpResponse()).isEmpty();
                assertThat(((MailSubmissionException) failure).getSubmissionReceipt().getRecipientResults())
                        .allSatisfy(recipient -> assertThat(recipient.getRcptResponse()).isEmpty());
                sender.sendMail(email(null));
            });
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void reusedComposedOrExactEmailGetsANewIdentifierForEverySend(final boolean exact, final boolean asynchronous) throws Exception {
        final List<String> identifiers = new CopyOnWriteArrayList<>();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final byte[] eml = ("From: sender@example.test\r\nTo: receiver@example.test\r\nMessage-ID: <same-message@example.test>\r\n"
                + "Content-Type: text/plain\r\n\r\nExact content.\r\n").getBytes(US_ASCII);
        final Email original = exact ? mail.emailBuilder().startingFromExactEml(eml).withEnvelopeRecipients(RECIPIENT).buildEmail()
                : mail.emailBuilder().copying(email(null)).fixingMessageId("<same-message@example.test>").buildEmail();
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(DSN);
            for (int index = 0; index < 2; index++) {
                identifiers.add(readGeneratedEnvelopeId(peer, MAIL_FROM));
                final String content = acceptMessageAfterMailFrom(peer, "", RECIPIENT);
                assertThat(content).doesNotContain("ENVID=", "Original-Envelope-ID:");
                if (exact) {
                    assertThat(content.getBytes(US_ASCII)).containsExactly(eml);
                }
            }
            finishConnection(peer);
        }); Mailer mailer = builder(server).withMailSendObserver(outcomes::add).buildMailer()) {
            final MailSubmissionReceipt first = send(mailer, original, asynchronous);
            final MailSubmissionReceipt second = send(mailer, original, asynchronous);
            assertThat(identifiers).hasSize(2).doesNotHaveDuplicates();
            assertThat(List.of(first, second)).extracting(MailSubmissionReceipt::getEnvelopeId).containsExactlyElementsOf(identifiers);
            assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(first);
            assertThat(outcomes.get(1).getSubmissionReceipt()).containsSame(second);
            assertThat(original.getDeliveryStatusNotification()).isNull();
            assertThat(mail.emailBuilder().copying(original).buildEmail().getDeliveryStatusNotification()).isNull();
        }
    }

    @Test
    void openConnectionReturnsEachGeneratedIdentifierWithoutChangingTheEmail() throws Exception {
        final List<String> identifiers = new CopyOnWriteArrayList<>();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final Email original = email(null);
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(DSN);
            for (int index = 0; index < 2; index++) {
                identifiers.add(readGeneratedEnvelopeId(peer, MAIL_FROM));
                acceptMessageAfterMailFrom(peer, "", RECIPIENT);
            }
            finishConnection(peer);
        }); Mailer mailer = builder(server).withMailSendObserver(outcomes::add).buildMailer()) {
            mailer.withOpenConnection(sender -> {
                final MailSubmissionReceipt first = sender.sendMailAndGetReceipt(original);
                final MailSubmissionReceipt second = sender.sendMailAndGetReceipt(original);
                assertThat(identifiers).hasSize(2).doesNotHaveDuplicates();
                assertThat(List.of(first, second)).extracting(MailSubmissionReceipt::getEnvelopeId).containsExactlyElementsOf(identifiers);
                assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(first);
                assertThat(outcomes.get(1).getSubmissionReceipt()).containsSame(second);
            });
            assertThat(original.getDeliveryStatusNotification()).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void automaticIdentifierDoesNotRequireDsn(final boolean malformed, final boolean asynchronous) throws Exception {
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(malformed ? "250-localhost\r\n250 DSN invalid" : "250 localhost");
            acceptMessage(peer, MAIL_FROM, "", RECIPIENT);
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            final MailSubmissionReceipt receipt = send(mailer, email(null), asynchronous);
            assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
            assertThat(receipt.getEnvelopeId()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void simpleBatchGeneratesDistinctIdentifiersForTheSameEmail(final boolean asynchronous) throws Exception {
        final List<String> identifiers = new CopyOnWriteArrayList<>();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final Email original = email(null);
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(DSN);
            for (int index = 0; index < 2; index++) {
                identifiers.add(readGeneratedEnvelopeId(peer, MAIL_FROM));
                acceptMessageAfterMailFrom(peer, "", RECIPIENT);
            }
            finishConnection(peer);
        }); Mailer mailer = builder(server).withMailSendObserver(outcomes::add).buildMailer()) {
            if (asynchronous) {
                mailer.async().sendMailsInSimpleBatch(List.of(original, original)).getCompletion().get(10, SECONDS);
            } else {
                mailer.sync().sendMailsInSimpleBatch(List.of(original, original));
            }
            assertThat(identifiers).hasSize(2).doesNotHaveDuplicates();
            assertThat(outcomes).extracting(outcome -> outcome.getSubmissionReceipt().orElseThrow().getEnvelopeId())
                    .containsExactlyElementsOf(identifiers);
            assertThat(original.getDeliveryStatusNotification()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedGeneratedIdentifierDoesNotLeakIntoTheReplacementConnection(final boolean replacementSupportsDsn) throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicReference<String> failedIdentifier = new AtomicReference<>();
        final AtomicReference<String> nextIdentifier = new AtomicReference<>();
        final Email original = email(null);
        try (PeerServer server = new PeerServer(2, peer -> {
            final boolean first = attempts.incrementAndGet() == 1;
            peer.greet(first || replacementSupportsDsn ? DSN : "250 localhost");
            if (first) {
                failedIdentifier.set(readGeneratedEnvelopeId(peer, MAIL_FROM));
                peer.reply("550 5.0.0 rejected by test server");
                peer.expect("RSET");
                peer.reply("250 reset");
                peer.expectClosed();
            } else {
                if (replacementSupportsDsn) {
                    nextIdentifier.set(readGeneratedEnvelopeId(peer, MAIL_FROM));
                    acceptMessageAfterMailFrom(peer, "", RECIPIENT);
                } else {
                    acceptMessage(peer, MAIL_FROM, "", RECIPIENT);
                }
                finishConnection(peer);
            }
        }); Mailer mailer = builder(server).buildMailer()) {
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(original));
            assertThat(failure).isInstanceOf(MailSubmissionException.class);
            assertThat(((MailSubmissionException) failure).getSubmissionReceipt().getEnvelopeId()).isEqualTo(failedIdentifier.get());
            final MailSubmissionReceipt receipt = mailer.async().sendMail(original).getCompletion().get(10, SECONDS);
            assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
            assertThat(receipt.getEnvelopeId()).isEqualTo(nextIdentifier.get()).isNotEqualTo(failedIdentifier.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingFinalReplyRetainsTheAttemptIdentifierWithoutClaimingAcceptance(final boolean asynchronous) throws Exception {
        final AtomicReference<String> identifier = new AtomicReference<>();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(DSN);
            identifier.set(readGeneratedEnvelopeId(peer, MAIL_FROM));
            peer.reply("250 sender accepted");
            peer.expect("RCPT TO:<" + RECIPIENT + "> ORCPT=rfc822;" + RECIPIENT);
            peer.reply("250 recipient accepted");
            peer.expect("DATA");
            peer.reply("354 send synthetic message");
            readMessage(peer);
            // Drop the connection after DATA without acknowledging acceptance.
        }); Mailer mailer = builder(server).withMailSendObserver(outcomes::add).buildMailer()) {
            final Throwable thrown = catchThrowable(() -> send(mailer, email(null), asynchronous));
            final Throwable failure = asynchronous ? thrown.getCause() : thrown;
            assertThat(failure).isInstanceOf(MailSubmissionException.class);
            final MailSubmissionReceipt receipt = ((MailSubmissionException) failure).getSubmissionReceipt();
            assertThat(receipt.getEnvelopeId()).isEqualTo(identifier.get()).isNotNull();
            assertThat(receipt.getStatus()).isEqualTo(UNKNOWN);
            assertThat(receipt.getRetryDisposition()).isEqualTo(DUPLICATE_RISK);
            assertThat(outcomes).singleElement().satisfies(outcome -> {
                assertThat(outcome.getSubmissionReceipt()).containsSame(receipt);
                assertThat(outcome.getFailure()).containsSame(failure);
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void simpleBatchUsesOneIdentifierPerEmailAndDoesNotAccumulateReceipts(final boolean asynchronous) throws Exception {
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(DSN);
            acceptMessage(peer, MAIL_FROM + " ENVID=batch-1", "", RECIPIENT);
            acceptMessage(peer, MAIL_FROM + " ENVID=batch-2", "", RECIPIENT);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withMailSendObserver(outcomes::add).buildMailer()) {
            final List<Email> emails = List.of(email("batch-1"), email("batch-2"));
            if (asynchronous) {
                assertThat(mailer.async().sendMailsInSimpleBatch(emails).getCompletion().get(10, SECONDS)).isNull();
            } else {
                mailer.sync().sendMailsInSimpleBatch(emails);
            }
            assertThat(outcomes).hasSize(2).allSatisfy(outcome -> assertThat(outcome.isSuccessful()).isTrue());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void simpleBatchStopsAtUnsupportedIdentifierWithoutReadingTheNextEmail(final boolean asynchronous) throws Exception {
        final AtomicInteger read = new AtomicInteger();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final Iterable<Email> emails = () -> new Iterator<Email>() {
            @Override
            public boolean hasNext() {
                return read.get() < 3;
            }

            @Override
            public Email next() {
                final int index = read.incrementAndGet();
                assertThat(index).isLessThan(3);
                return email(index == 1 ? null : "unsupported-batch-id");
            }
        };
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet("250 localhost");
            acceptMessage(peer, MAIL_FROM, "", RECIPIENT);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withMailSendObserver(outcomes::add).buildMailer()) {
            final Throwable failure = catchThrowable(() -> {
                if (asynchronous) {
                    mailer.async().sendMailsInSimpleBatch(emails).getCompletion().get(10, SECONDS);
                } else {
                    mailer.sync().sendMailsInSimpleBatch(emails);
                }
            });
            assertThat(asynchronous ? failure.getCause() : failure).isInstanceOf(MailSubmissionException.class);
            assertThat(read).hasValue(2);
            assertThat(outcomes).hasSize(2);
            assertThat(outcomes.get(0).isSuccessful()).isTrue();
            assertThat(outcomes.get(1).isSuccessful()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void observerCanReenterTheSizeOnePoolWithADifferentIdentifier(final boolean fixed) throws Exception {
        final AtomicReference<Mailer> owner = new AtomicReference<>();
        final AtomicReference<MailSubmissionReceipt> nestedReceipt = new AtomicReference<>();
        final AtomicInteger observed = new AtomicInteger();
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(DSN);
            for (final String identifier : List.of("outer-id", "nested-id")) {
                if (fixed) {
                    acceptMessage(peer, MAIL_FROM + " ENVID=" + identifier, "", RECIPIENT);
                } else {
                    readGeneratedEnvelopeId(peer, MAIL_FROM);
                    acceptMessageAfterMailFrom(peer, "", RECIPIENT);
                }
            }
            finishConnection(peer);
        }); Mailer mailer = builder(server).withConnectionPoolClaimTimeoutMillis(2000).withMailSendObserver(outcome -> {
            if (observed.incrementAndGet() == 1) {
                nestedReceipt.set(owner.get().sync().sendMail(email(fixed ? "nested-id" : null)));
            }
        }).buildMailer()) {
            owner.set(mailer);
            final MailSubmissionReceipt outerReceipt = mailer.async().sendMail(email(fixed ? "outer-id" : null)).getCompletion().get(10, SECONDS);
            assertThat(outerReceipt.getStatus()).isEqualTo(ACCEPTED);
            assertThat(observed).hasValue(2);
            assertThat(nestedReceipt.get()).isNotNull();
            assertThat(nestedReceipt.get().getStatus()).isEqualTo(ACCEPTED);
            assertThat(outerReceipt.getEnvelopeId()).isNotNull().isNotEqualTo(nestedReceipt.get().getEnvelopeId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void simultaneousPooledSendsKeepIdentifiersOnTheirOwnConnections(final boolean fixed) throws Exception {
        final Set<String> observedCommands = ConcurrentHashMap.newKeySet();
        final CountDownLatch connected = new CountDownLatch(2);
        try (PeerServer server = new PeerServer(2, peer -> {
            peer.greet(DSN);
            connected.countDown();
            assertThat(connected.await(10, SECONDS)).isTrue();
            final String command = peer.readLine();
            assertThat(observedCommands.add(command)).isTrue();
            if (fixed) {
                assertThat(command).isIn(MAIL_FROM + " ENVID=parallel-1", MAIL_FROM + " ENVID=parallel-2");
            } else {
                assertThat(command).startsWith(MAIL_FROM + " ENVID=");
            }
            peer.reply("250 sender accepted");
            peer.expect("RCPT TO:<" + RECIPIENT + "> ORCPT=rfc822;" + RECIPIENT);
            peer.reply("250 recipient accepted");
            peer.expect("DATA");
            peer.reply("354 send message");
            String line;
            int lines = 0;
            do {
                line = peer.readLine();
                assertThat(++lines).isLessThan(100);
            } while (line != null && !".".equals(line));
            assertThat(line).isEqualTo(".");
            peer.reply("250 accepted");
            finishConnection(peer);
        }); Mailer mailer = builder(server).withConnectionPoolMaxSize(2).withThreadPoolSize(2).buildMailer()) {
            final Email shared = email(null);
            final CompletableFuture<MailSubmissionReceipt> first = mailer.async().sendMail(fixed ? email("parallel-1") : shared).getCompletion();
            final CompletableFuture<MailSubmissionReceipt> second = mailer.async().sendMail(fixed ? email("parallel-2") : shared).getCompletion();
            final MailSubmissionReceipt firstReceipt = first.get(15, SECONDS);
            final MailSubmissionReceipt secondReceipt = second.get(15, SECONDS);
            assertThat(firstReceipt.getStatus()).isEqualTo(ACCEPTED);
            assertThat(secondReceipt.getStatus()).isEqualTo(ACCEPTED);
            assertThat(firstReceipt.getEnvelopeId()).isNotNull().isNotEqualTo(secondReceipt.getEnvelopeId());
            assertThat(observedCommands).containsExactlyInAnyOrder(MAIL_FROM + " ENVID=" + firstReceipt.getEnvelopeId(),
                    MAIL_FROM + " ENVID=" + secondReceipt.getEnvelopeId());
            assertThat(shared.getDeliveryStatusNotification()).isNull();
        }
        assertThat(observedCommands).hasSize(2);
    }

    @Test
    void failedLeaseIsInvalidatedAndTheNextSendHasANewIdentifier() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        try (PeerServer server = new PeerServer(2, peer -> {
            peer.greet(DSN);
            if (attempts.incrementAndGet() == 1) {
                expectCommand(peer, MAIL_FROM + " ENVID=rejected");
                peer.reply("550 5.0.0 rejected by test server");
                peer.expect("RSET");
                peer.reply("250 reset");
                peer.expectClosed();
            } else {
                acceptMessage(peer, MAIL_FROM + " ENVID=next-attempt", "", RECIPIENT);
                finishConnection(peer);
            }
        }); Mailer mailer = builder(server).buildMailer()) {
            assertThat(catchThrowable(() -> mailer.sync().sendMail(email("rejected")))).isInstanceOf(MailSubmissionException.class);
            final MailSubmissionReceipt receipt = mailer.async().sendMail(email("next-attempt")).getCompletion().get(10, SECONDS);
            assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
            assertThat(receipt.getSmtpResponse().orElseThrow().getResponse()).contains("accepted synthetic message");
            assertThat(receipt.getInvalidRecipients()).isEmpty();
        }
        assertThat(attempts).hasValue(2);
    }

    @Test
    void exactEmailKeepsAllBytesWithAnIdentifierAndDuplicateEnvelopeRecipients() throws Exception {
        final byte[] eml = ("From: author@example.test\r\nTo: visible@example.test\r\nBcc: retain@example.test\r\n"
                + "Message-ID: <unchanged@example.test>\r\nDate: Tue, 15 Sep 2026 12:00:00 +0000\r\n"
                + "Content-Type: text/plain; charset=us-ascii\r\n\r\nKeep these exact bytes.\r\n").getBytes(US_ASCII);
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(DSN);
            assertThat(acceptMessage(peer, MAIL_FROM + " ENVID=exact+2B42", "", RECIPIENT, RECIPIENT).getBytes(US_ASCII))
                    .containsExactly(eml);
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            final Email exact = mail.emailBuilder().startingFromExactEml(eml).withEnvelopeSender("sender@example.test")
                    .withEnvelopeRecipients(RECIPIENT, RECIPIENT).fixingEnvelopeId("exact+42").buildEmail();
            assertThat(mailer.sync().sendMail(exact).getAcceptedRecipients()).containsExactly(RECIPIENT, RECIPIENT);
        }
    }

    @Test
    void unrelatedSessionMailExtensionsArePreservedAndNoIdentifierIsWrittenBack() throws Exception {
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet("250-localhost\r\n250-XTEST\r\n250 DSN");
            acceptMessage(peer, MAIL_FROM + " XTEST=keep ENVID=one", "", RECIPIENT);
            readGeneratedEnvelopeId(peer, MAIL_FROM + " XTEST=keep");
            acceptMessageAfterMailFrom(peer, "", RECIPIENT);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperty("mail.smtp.mailextension", "XTEST=keep").buildMailer()) {
            mailer.sync().sendMail(email("one"));
            mailer.sync().sendMail(email(null));
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void capabilityCheckUsesTheAuthenticatedPostTlsConnection(final boolean supportedAfterTls, final boolean fixed) throws Exception {
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(supportedAfterTls ? "250-localhost\r\n250 STARTTLS" : "250-localhost\r\n250-DSN\r\n250 STARTTLS");
            peer.expect("STARTTLS");
            peer.reply("220 start TLS");
            peer.upgradeToTls();
            peer.expect("EHLO probe.example.test");
            peer.reply(supportedAfterTls ? "250-localhost\r\n250-AUTH LOGIN\r\n250 DSN" : "250-localhost\r\n250 AUTH LOGIN");
            peer.authenticate();
            if (supportedAfterTls) {
                if (fixed) {
                    acceptMessage(peer, MAIL_FROM + " ENVID=after-tls", "", RECIPIENT);
                } else {
                    readGeneratedEnvelopeId(peer, MAIL_FROM);
                    acceptMessageAfterMailFrom(peer, "", RECIPIENT);
                }
                finishConnection(peer);
            } else if (!fixed) {
                acceptMessage(peer, MAIL_FROM, "", RECIPIENT);
                finishConnection(peer);
            } else {
                // TLS shutdown can close directly or send QUIT, but must never start a transaction.
                final String shutdownCommand = peer.readLine();
                if (shutdownCommand != null) {
                    assertThat(shutdownCommand).isEqualTo("QUIT");
                    peer.reply("221 bye");
                    peer.expectClosed();
                }
            }
        }); Mailer mailer = builder(server).withSMTPServer("localhost", server.port(), "test-user", "test-password")
                .withTransportStrategy(TransportStrategy.SMTP_TLS)
                .withProperties(SmtpCapabilityProbeCharacterizationTest.session(true).getProperties()).buildMailer()) {
            if (supportedAfterTls || !fixed) {
                final MailSubmissionReceipt receipt = mailer.sync().sendMail(email(fixed ? "after-tls" : null));
                assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
                assertThat(receipt.getEnvelopeId() != null).isEqualTo(supportedAfterTls);
            } else {
                assertThat(catchThrowable(() -> mailer.sync().sendMail(email("after-tls"))))
                        .isInstanceOf(MailSubmissionException.class).hasCauseInstanceOf(MessagingException.class);
            }
        }
    }

    @Test
    void callerOwnedSessionKeepsItsRawExtensionAndRemainsUnchanged() throws Exception {
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(DSN);
            acceptMessage(peer, MAIL_FROM + " XTEST=caller ENVID=caller-id", "", RECIPIENT);
            finishConnection(peer);
        })) {
            final Properties properties = SmtpCapabilityProbeCharacterizationTest.session(false).getProperties();
            properties.setProperty("mail.transport.protocol", "smtp");
            properties.setProperty("mail.smtp.host", "localhost");
            properties.setProperty("mail.smtp.port", Integer.toString(server.port()));
            properties.setProperty("mail.smtp.mailextension", "XTEST=caller");
            final Session session = Session.getInstance(properties);
            try (Mailer mailer = mail.mailerBuilder(session).withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).buildMailer()) {
                assertThat(mailer.sync().sendMail(email("caller-id")).getStatus()).isEqualTo(ACCEPTED);
                assertThat(session.getProperty("mail.smtp.mailextension")).isEqualTo("XTEST=caller");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENVID=old", "XTEST=keep envid=old", "ENVID"})
    void conflictingRawIdentifierIsRejectedBeforeSubmission(final String rawExtension) throws Exception {
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(DSN);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperty("mail.smtp.mailextension", rawExtension).buildMailer()) {
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email("new")));
            assertThat(failure).isInstanceOf(MailSubmissionException.class);
            assertThat(failure.getCause()).hasMessageContaining("on this email and in the Mailer property 'mail.smtp.mailextension'")
                    .hasMessageNotContaining("mail.smtps.mailextension")
                    .hasMessageNotContaining("SMTPMessage")
                    .hasMessageContaining("Leave any other entries unchanged")
                    .hasMessageContaining("keep fixingEnvelopeId");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rawIdentifierRemainsCallerOwnedAndDoesNotAcquireAnAutomaticDuplicate(final boolean supportsDsn) throws Exception {
        final String rawExtension = "XTEST=keep envid=caller+2B42";
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(supportsDsn ? DSN : "250 probe.example.test");
            acceptMessage(peer, MAIL_FROM + " " + rawExtension, "", RECIPIENT);
            acceptMessage(peer, MAIL_FROM + " " + rawExtension, "", RECIPIENT);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperty("mail.smtp.mailextension", rawExtension).buildMailer()) {
            final Email email = email(null);
            for (final boolean asynchronous : new boolean[] {false, true}) {
                final MailSubmissionReceipt receipt = send(mailer, email, asynchronous);
                assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
                assertThat(receipt.getEnvelopeId()).isNull();
            }
            assertThat(email.getDeliveryStatusNotification()).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void automaticIdentifierPreservesTheExistingAngusEightBitConversion(final boolean supportsDsn, final boolean multipart) throws Exception {
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(supportsDsn ? "250-localhost\r\n250-DSN\r\n250 8BITMIME" : "250-localhost\r\n250 8BITMIME");
            if (supportsDsn) {
                readGeneratedEnvelopeId(peer, MAIL_FROM);
            } else {
                expectCommand(peer, MAIL_FROM);
            }
            final String content = acceptMessageAfterMailFrom(peer, "", RECIPIENT);
            assertThat(content).contains("Content-Transfer-Encoding: 8bit").doesNotContain("Content-Transfer-Encoding: quoted-printable");
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperty("mail.smtp.allow8bitmime", "true").buildMailer()) {
            final EmailPopulatingBuilder emailBuilder = mail.emailBuilder().copying(email(null)).withPlainText("Synthetic caf\u00e9 content.");
            if (multipart) {
                emailBuilder.withHTMLText("<p>Synthetic caf\u00e9 content.</p>");
            }
            assertThat(mailer.sync().sendMail(emailBuilder.buildEmail()).getStatus()).isEqualTo(ACCEPTED);
        }
    }

    private MailerRegularBuilder<?> builder(final PeerServer server) {
        return mail.mailerBuilder().withSMTPServer("localhost", server.port()).withSmtpClientHostname("probe.example.test")
                .withSessionTimeout(5000).withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).withConnectionPoolClaimTimeoutMillis(10000);
    }

    private Email email(final String identifier) {
        return mail.emailBuilder().startingBlank().from("sender@example.test")
                .withRecipients(new Recipient(null, RECIPIENT, Message.RecipientType.TO, null))
                .withSubject("Synthetic ENVID test").withPlainText("Synthetic loopback content.")
                .fixingEnvelopeId(identifier).buildEmail();
    }

    private static MailSubmissionReceipt send(final Mailer mailer, final Email email, final boolean asynchronous) throws Exception {
        return asynchronous ? mailer.async().sendMail(email).getCompletion().get(10, SECONDS) : mailer.sync().sendMail(email);
    }

    @FunctionalInterface
    interface PeerScript {
        void run(Conversation peer) throws Exception;
    }

    static final class PeerServer implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService workers;
        private final CompletableFuture<Void> serving;

        PeerServer(final int connections, final PeerScript script) throws Exception {
            this(connections, US_ASCII, script);
        }

        PeerServer(final int connections, final Charset charset, final PeerScript script) throws Exception {
            server = new ServerSocket(0, connections, InetAddress.getByName("localhost"));
            server.setSoTimeout(15000);
            workers = Executors.newFixedThreadPool(connections + 1);
            serving = CompletableFuture.runAsync(() -> {
                final List<CompletableFuture<Void>> peers = new ArrayList<>();
                try {
                    for (int index = 0; index < connections; index++) {
                        final Socket socket = server.accept();
                        peers.add(CompletableFuture.runAsync(() -> {
                            try (Conversation peer = new Conversation(socket, charset)) {
                                script.run(peer);
                            } catch (Exception failure) {
                                throw new AssertionError("ENVID peer failed", failure);
                            }
                        }, workers));
                    }
                    CompletableFuture.allOf(peers.toArray(new CompletableFuture<?>[0])).get(20, SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError("ENVID server failed", failure);
                }
            }, workers);
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            try {
                serving.get(25, SECONDS);
            } finally {
                server.close();
                workers.shutdownNow();
                assertThat(workers.awaitTermination(5, SECONDS)).isTrue();
            }
        }
    }
}
