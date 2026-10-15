package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.*;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import testutil.smtp.ScriptedSmtpServer;
import testutil.smtp.SmtpConversation;
import testutil.smtp.RecordingSmtpServer;
import org.simplejavamail.api.internal.batchsupport.PoolConnectionObserver;
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.mailer.internal.util.AcquiredMailTransport;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.UUID;
import java.util.Arrays;
import java.util.Properties;
import javax.net.SocketFactory;
import jakarta.mail.Session;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.*;
import static org.simplejavamail.api.mailer.MailSendProgressStage.*;
import static org.mockito.Mockito.mock;

/** The peer waits for observation before replying, so coalescing cannot make these assertions depend on thread scheduling. */
@Timeout(45)
class MailSendDetailedProgressTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directSetupWorksIndependentlyOfAbortSupport(final boolean customFactory) throws Exception {
        final Stages stages = new Stages();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> conversation(peer, stages, false, "LOGIN", null))) {
            final MailerRegularBuilder<?> settings = builder(server.port(), false, "LOGIN");
            if (customFactory) { settings.withProperty("mail.smtp.socketFactory", SocketFactory.getDefault()); }
            try (Mailer mailer = settings.buildMailer(); MailSendProgressDispatcher dispatcher = new MailSendProgressDispatcher(stages::record, null);
                 MailSendControl control = new MailSendControl(null, mock(ScheduledExecutorService.class))) {
                final MailSendProgressRecorder progress = new MailSendProgressRecorder(dispatcher, UUID.randomUUID(), null, System::nanoTime);
                final MailSendDiagnosticsRecorder diagnostics = new MailSendDiagnosticsRecorder(null, progress);
                diagnostics.startConnectionAcquisition();
                try (AcquiredMailTransport transport = AcquiredMailTransport.acquire(mailer.getSession(), null, control,
                        PoolConnectionObserver.NONE, diagnostics)) {
                    if (customFactory) { assertThat(MailTransportLifecycleResolver.findAbortAction(transport.getTransport())).isEmpty(); }
                    final MailerImpl owner = (MailerImpl) mailer;
                    final Email prepared = owner.prepareSelectedEmail(email(), owner, java.util.Collections.singletonList(owner));
                    assertThat(transport.send(prepared, diagnostics, mock(SendingAllowance.Reservation.class)).getStatus())
                            .isEqualTo(MailSubmissionStatus.ACCEPTED);
                }
                progress.finish(null);
                stages.await(FINISHED);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void heloIsObservableWithDisabledOrRejectedEhlo(final boolean ehloEnabled) throws Exception {
        final Stages stages = new Stages();
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            stages.await(WAITING_FOR_GREETING);
            peer.reply(SmtpConversation.GREETING);
            if (ehloEnabled) {
                peer.expect("EHLO probe.example.test");
                peer.reply("500 EHLO unsupported");
            }
            peer.expect("HELO probe.example.test");
            stages.await(NEGOTIATING_CAPABILITIES);
            peer.reply("250 localhost");
            connection.acceptMessages(peer);
        }); Mailer mailer = builder(server.port(), false, "LOGIN").withSMTPServerUsername(null).withSMTPServerPassword(null)
                .withProperty("mail.smtp.ehlo", ehloEnabled).withMailSendProgressObserver(stages::record).buildMailer()) {
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            stages.await(FINISHED);
            assertThat(server.messages()).hasSize(1);
        }
    }

    @Test
    void cancellationDuringGreetingRetiresSetupBeforeAReplacementConnection() throws Exception {
        final Stages stages = new Stages();
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            if (connection.number() == 1) {
                stages.await(WAITING_FOR_GREETING);
                assertThat(peer.readLine()).isNull();
            } else {
                connection.greet(peer, "250 localhost");
                connection.acceptMessages(peer);
            }
        }); Mailer mailer = builder(server.port(), false, "LOGIN").withSMTPServerUsername(null).withSMTPServerPassword(null)
                .withMailSendProgressObserver(stages::record).buildMailer()) {
            final MailSend<MailSubmissionReceipt> cancelled = mailer.async().sendMail(email());
            stages.await(WAITING_FOR_GREETING);
            cancelled.requestCancellation();
            assertThat(catchThrowable(() -> cancelled.getCompletion().get(15, SECONDS)))
                    .hasCauseInstanceOf(MailSendCancelledException.class);
            final UUID first = stages.nextFinished().getObservationId();
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            assertThat(stages.nextFinished().getObservationId()).isNotEqualTo(first);
            assertThat(server.messages()).hasSize(1);
            assertThat(stages.observed.stream().filter(e -> e.getObservationId().equals(first)))
                    .extracting(MailSendProgress::getStage).doesNotContain(NEGOTIATING_CAPABILITIES, SUBMITTING_SENDER, UPLOADING);
        }
    }

    @Test
    void implicitTlsKeepsHandshakeInAcquisitionInsteadOfInventingAnUpgrade() throws Exception {
        final Stages stages = new Stages();
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            stages.await(ACQUIRING_CONNECTION);
            peer.upgradeToTls();
            stages.await(WAITING_FOR_GREETING);
            connection.greet(peer, "250 localhost");
            connection.acceptMessages(peer);
        }); Mailer mailer = builder(server.port(), false, "LOGIN").withTransportStrategy(TransportStrategy.SMTPS)
                .withSMTPServerUsername(null).withSMTPServerPassword(null).withMailSendProgressObserver(stages::record).buildMailer()) {
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            stages.await(FINISHED);
            assertThat(stages.observed).extracting(MailSendProgress::getStage).doesNotContain(NEGOTIATING_TLS);
        }
    }

    @Test
    void callerOwnedOrdinaryAngusSessionKeepsCoarseProgress() throws Exception {
        final Stages stages = new Stages();
        try (RecordingSmtpServer server = new RecordingSmtpServer()) {
            final Properties properties = new Properties();
            properties.setProperty("mail.transport.protocol", "smtp");
            properties.setProperty("mail.smtp.host", "localhost");
            properties.setProperty("mail.smtp.port", Integer.toString(server.port()));
            properties.setProperty("mail.smtp.localhost", "probe.example.test");
            properties.setProperty("mail.smtp.timeout", "20000");
            final Session session = Session.getInstance(properties);
            try (Mailer mailer = mail.mailerBuilder(session).withConnectionPoolCoreSize(0).withMailSendProgressObserver(stages::record).buildMailer()) {
                assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
                stages.await(FINISHED);
                assertThat(stages.observed).extracting(MailSendProgress::getStage).doesNotContain(WAITING_FOR_GREETING,
                        NEGOTIATING_CAPABILITIES, NEGOTIATING_TLS, AUTHENTICATING, SUBMITTING_SENDER, SUBMITTING_RECIPIENTS,
                        WAITING_FOR_DATA_PERMISSION, UPLOADING, WAITING_FOR_RESPONSE);
                assertThat(stages.observed).allSatisfy(event -> assertThat(event.getBytesWritten()).isEmpty());
                assertThat(session.getProvider("smtp").getClassName()).isEqualTo("org.eclipse.angus.mail.smtp.SMTPTransport");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reusedAndBackgroundConnectionsDoNotReplaySetup(final boolean background) throws Exception {
        final Stages stages = new Stages();
        final CountDownLatch ready = new CountDownLatch(1);
        try (RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            if (!background) { stages.await(WAITING_FOR_GREETING); }
            connection.greet(peer, "250 localhost");
            ready.countDown();
            connection.acceptMessages(peer);
        }); Mailer mailer = builder(server.port(), false, "LOGIN").withSMTPServerUsername(null).withSMTPServerPassword(null)
                .withConnectionPoolCoreSize(background ? 1 : 0).withMailSendProgressObserver(stages::record).buildMailer()) {
            if (background) {
                assertThat(ready.await(15, SECONDS)).isTrue();
                assertThat(stages.observed).isEmpty();
            }
            mailer.sync().sendMail(email());
            final UUID first = stages.nextFinished().getObservationId();
            mailer.sync().sendMail(email());
            final UUID second = stages.nextFinished().getObservationId();
            assertThat(second).isNotEqualTo(first);
            assertThat(server.connectionCount()).isEqualTo(1);
            assertThat(stages.observed.stream().filter(e -> background || e.getObservationId().equals(second)))
                    .extracting(MailSendProgress::getStage).doesNotContain(WAITING_FOR_GREETING, NEGOTIATING_CAPABILITIES, NEGOTIATING_TLS, AUTHENTICATING);
        }
    }

    @Test
    void retainedScopesDoNotAttributeSharedSetupToIndividualEmails() throws Exception {
        final Stages stages = new Stages();
        try (RecordingSmtpServer server = new RecordingSmtpServer(); Mailer mailer = builder(server.port(), false, "LOGIN")
                .withSMTPServerUsername(null).withSMTPServerPassword(null).withMailSendProgressObserver(stages::record).buildMailer()) {
            mailer.sync().sendMailsInSimpleBatch(Arrays.asList(email(), email()));
            stages.nextFinished();
            stages.nextFinished();
            mailer.withOpenConnection(sender -> sender.sendMail(email()));
            stages.nextFinished();
            assertThat(server.messages()).hasSize(3);
            assertThat(stages.observed).extracting(MailSendProgress::getStage)
                    .doesNotContain(WAITING_FOR_GREETING, NEGOTIATING_CAPABILITIES, NEGOTIATING_TLS, AUTHENTICATING);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,LOGIN", "true,LOGIN", "false,PLAIN", "true,PLAIN"})
    void observesActualSetupAndEnvelopeExchanges(final boolean tls, final String mechanism) throws Exception {
        final Stages stages = new Stages();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> conversation(peer, stages, tls, mechanism, null));
             Mailer mailer = builder(server.port(), tls, mechanism).withMailSendProgressObserver(stages::record).buildMailer()) {
            final MailSubmissionReceipt receipt = mailer.async().sendMail(email()).getCompletion().get(30, SECONDS);
            assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            stages.await(FINISHED);
            assertThat(stages.observed).extracting(MailSendProgress::getStage).contains(WAITING_FOR_GREETING, NEGOTIATING_CAPABILITIES,
                    AUTHENTICATING, SUBMITTING_SENDER, SUBMITTING_RECIPIENTS, WAITING_FOR_DATA_PERMISSION);
            if (tls) { assertThat(stages.observed).extracting(MailSendProgress::getStage).contains(NEGOTIATING_TLS); }
            else { assertThat(stages.observed).extracting(MailSendProgress::getStage).doesNotContain(NEGOTIATING_TLS); }
            assertThat(stages.observed.stream().filter(e -> e.getStage() == AUTHENTICATING)).allSatisfy(e ->
                    assertThat(e.getSubmissionAttempt()).isZero());
            assertThat(stages.observed.stream().filter(e -> e.getStage() == SUBMITTING_RECIPIENTS)).allSatisfy(e ->
                    assertThat(e.getSubmissionAttempt()).isEqualTo(1));
            assertThat(stages.observed.toString()).doesNotContain("private-user", "private-password", "recipient@example.test", "AUTH LOGIN");
        }
    }

    @ParameterizedTest
    @EnumSource(value = MailSendProgressStage.class, names = {"WAITING_FOR_GREETING", "NEGOTIATING_CAPABILITIES", "NEGOTIATING_TLS",
            "AUTHENTICATING", "SUBMITTING_SENDER", "SUBMITTING_RECIPIENTS", "WAITING_FOR_DATA_PERMISSION"})
    void phaseFailureKeepsTheOriginalResultAndDoesNotInventSuccess(final MailSendProgressStage failureStage) throws Exception {
        final Stages stages = new Stages();
        final List<MailSendOutcome> results = new CopyOnWriteArrayList<>();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> conversation(peer, stages, true, "LOGIN", failureStage));
             Mailer mailer = builder(server.port(), true, "LOGIN").withMailSendProgressObserver(stages::record)
                     .withMailSendResultObserver(results::add).buildMailer()) {
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email()));
            assertThat(failure).isNotNull();
            stages.await(FINISHED);
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getFailure()).containsSame(failure);
            assertThat(results.get(0).isSuccessful()).isFalse();
            assertThat(stages.observed).extracting(MailSendProgress::getStage).contains(failureStage).doesNotContain(UPLOADING);
        }
    }

    private void conversation(final SmtpConversation peer, final Stages stages, final boolean tls, final String mechanism,
            final MailSendProgressStage failureStage) throws Exception {
        if (!reply(peer, stages, WAITING_FOR_GREETING, SmtpConversation.GREETING, failureStage)) { return; }
        peer.expect("EHLO probe.example.test");
        if (!reply(peer, stages, NEGOTIATING_CAPABILITIES, capabilities(tls, mechanism), failureStage)) { return; }
        if (tls) {
            peer.expect("STARTTLS");
            if (!reply(peer, stages, NEGOTIATING_TLS, "220 begin TLS", failureStage)) { return; }
            peer.upgradeToTls();
            peer.expect("EHLO probe.example.test");
            assertThat(stages.secondCapabilities.await(15, SECONDS)).isTrue();
            peer.reply(capabilities(false, mechanism));
        }
        final String auth = peer.readLine();
        assertThat(auth).startsWith("AUTH " + mechanism);
        stages.await(AUTHENTICATING);
        if (failureStage == AUTHENTICATING) { peer.reply("535 rejected"); drainCleanup(peer); return; }
        if (mechanism.equals("LOGIN")) {
            peer.reply("334 VXNlcm5hbWU6");
            assertThat(peer.readLine()).isNotBlank();
            peer.reply("334 UGFzc3dvcmQ6");
            assertThat(peer.readLine()).isNotBlank();
        }
        peer.reply("235 authenticated");
        assertThat(peer.readLine()).startsWith("MAIL FROM:");
        if (!reply(peer, stages, SUBMITTING_SENDER, "250 sender accepted", failureStage)) { return; }
        assertThat(peer.readLine()).startsWith("RCPT TO:");
        if (!reply(peer, stages, SUBMITTING_RECIPIENTS, "250 recipient accepted", failureStage)) { return; }
        peer.expect("DATA");
        if (!reply(peer, stages, WAITING_FOR_DATA_PERMISSION, "354 upload", failureStage)) { return; }
        String line;
        while ((line = peer.readLine()) != null && !line.equals(".")) { }
        assertThat(line).isEqualTo(".");
        stages.await(WAITING_FOR_RESPONSE);
        peer.reply("250 queued");
        drainCleanup(peer);
    }

    private static boolean reply(final SmtpConversation peer, final Stages stages, final MailSendProgressStage stage,
            final String success, final MailSendProgressStage failureStage) throws Exception {
        stages.await(stage);
        if (stage == failureStage) {
            peer.reply(stage == NEGOTIATING_TLS ? "454 TLS unavailable" : "554 rejected");
            drainCleanup(peer);
            return false;
        }
        peer.reply(success);
        return true;
    }

    private static void drainCleanup(final SmtpConversation peer) throws Exception {
        String command;
        while ((command = peer.readLine()) != null) {
            if (command.equals("QUIT")) { peer.reply("221 bye"); return; }
            assertThat(command).matches("RSET|NOOP|HELO probe.example.test");
            peer.reply(command.startsWith("HELO") ? "554 HELO rejected" : "250 reset");
        }
    }

    private static String capabilities(final boolean tls, final String mechanism) {
        return "250-localhost\r\n" + (tls ? "250-STARTTLS\r\n" : "") + "250 AUTH " + mechanism;
    }

    private MailerRegularBuilder<?> builder(final int port, final boolean tls, final String mechanism) {
        return mail.mailerBuilder().withSMTPServer("localhost", port, "private-user", "private-password")
                .withTransportStrategy(tls ? TransportStrategy.SMTP_TLS : TransportStrategy.SMTP).withOpportunisticTLS(false)
                .trustingAllHosts(true).verifyingServerIdentity(false).withSmtpClientHostname("probe.example.test")
                .withProperty("mail.smtp.auth.mechanisms", mechanism).withProperty("mail.host", "localhost")
                .withSessionTimeout(20000).withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).withThreadPoolSize(1);
    }

    private Email email() {
        return mail.emailBuilder().startingBlank().from("sender@example.test")
                .withRecipients(mail.recipientBuilder().withAddress("recipient@example.test").withType(jakarta.mail.Message.RecipientType.TO).build())
                .withSubject("detail")
                .withPlainText("unchanged content").buildEmail();
    }

    private static final class Stages {
        final List<MailSendProgress> observed = new CopyOnWriteArrayList<>();
        final Map<MailSendProgressStage, CountDownLatch> reached = new EnumMap<>(MailSendProgressStage.class);
        final CountDownLatch secondCapabilities = new CountDownLatch(2);
        final BlockingQueue<MailSendProgress> finished = new LinkedBlockingQueue<>();
        Stages() { for (MailSendProgressStage stage : MailSendProgressStage.values()) { reached.put(stage, new CountDownLatch(1)); } }
        void record(final MailSendProgress event) {
            observed.add(event);
            reached.get(event.getStage()).countDown();
            if (event.getStage() == NEGOTIATING_CAPABILITIES) { secondCapabilities.countDown(); }
            if (event.getStage() == FINISHED) { finished.add(event); }
        }
        MailSendProgress nextFinished() throws InterruptedException {
            final MailSendProgress event = finished.poll(15, SECONDS);
            assertThat(event).isNotNull();
            return event;
        }
        void await(final MailSendProgressStage stage) throws InterruptedException {
            assertThat(reached.get(stage).await(15, SECONDS)).as("live stage %s", stage).isTrue();
        }
    }
}
