package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import testutil.ConfigLoaderTestHelper;
import testutil.smtp.ScriptedSmtpServer;
import testutil.smtp.SmtpConversation;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.acceptMessage;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.finishConnection;
import static org.simplejavamail.recipient.RecipientBuilder.to;

/** Real loopback sockets exercise Angus setup evidence, not just synthetic exception classification. */
@Timeout(30)
class SmtpConnectionFailoverWireTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closedGreetingAndTemporaryServiceRejectionCanFailOver(final boolean temporaryReply) throws Exception {
        try (ScriptedSmtpServer primary = new ScriptedSmtpServer(1, peer -> {
            if (temporaryReply) {
                peer.reply("421 service temporarily unavailable");
            }
            peer.close();
        }); ScriptedSmtpServer secondary = acceptingPeer();
             Mailer failoverMailer = builder(secondary.port()).buildMailer();
             Mailer primaryMailer = builder(primary.port()).withFailoverMailer(failoverMailer, FailoverPolicy.builder().build()).buildMailer()) {
            assertThat(primaryMailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
        }
    }

    @Test
    void temporaryEhloRejectionFailsOverWithoutMailCommandsOnTheFirstConnection() throws Exception {
        try (ScriptedSmtpServer primary = new ScriptedSmtpServer(1, peer -> {
            peer.greet("421 service restarting");
            peer.expectClosed();
        }); ScriptedSmtpServer secondary = acceptingPeer();
              Mailer failoverMailer = builder(secondary.port()).buildMailer();
              Mailer primaryMailer = builder(primary.port()).withFailoverMailer(failoverMailer, FailoverPolicy.builder().build()).buildMailer()) {
            assertThat(primaryMailer.async().sendMail(email()).getCompletion().join().getStatus()).isEqualTo(ACCEPTED);
        }
    }

    @Test
    void missingRequiredStartTlsStopsInsteadOfUsingAnotherServer() throws Exception {
        final CustomMailer failoverCustomMailer = mock(CustomMailer.class);
        try (ScriptedSmtpServer primary = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250 localhost");
            peer.expectClosed();
        }); Mailer failoverMailer = mail.mailerBuilder().withCustomMailer(failoverCustomMailer).buildMailer();
             Mailer primaryMailer = builder(primary.port()).withTransportStrategy(org.simplejavamail.api.mailer.config.TransportStrategy.SMTP_TLS)
                     .withFailoverMailer(failoverMailer, FailoverPolicy.builder().build()).buildMailer()) {
            assertThatThrownBy(() -> primaryMailer.sync().sendMail(email())).isInstanceOf(ConnectionSetupFailure.class)
                    .hasStackTraceContaining("STARTTLS is required");
            verifyNoInteractions(failoverCustomMailer);
        }
    }

    private ScriptedSmtpServer acceptingPeer() throws Exception {
        return new ScriptedSmtpServer(1, peer -> {
            peer.greet("250 localhost");
            acceptMessage(peer, "MAIL FROM:<sender@example.test>", "", "receiver@example.test");
            finishConnection(peer);
        });
    }

    private MailerRegularBuilder<?> builder(final int port) {
        return mail.mailerBuilder().withSMTPServer("localhost", port).withTransportStrategy(SMTP)
                .withSmtpClientHostname("probe.example.test").withSessionTimeout(1000)
                .withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1);
    }

    private Email email() {
        return mail.emailBuilder().startingBlank().from("sender@example.test").withRecipients(to(null, "receiver@example.test"))
                .withPlainText("loopback test").buildEmail();
    }
}
