package org.simplejavamail.mailer;

import org.bbottema.javasocksproxyserver.RunningSocksServer;
import org.bbottema.javasocksproxyserver.SyncSocksServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import testutil.ConfigLoaderTestHelper;
import testutil.smtp.RecordingSmtpServer;

import javax.net.ServerSocketFactory;
import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;

/** Actual Angus client timeout behavior through the library-owned authenticated-proxy bridge. */
@Timeout(45)
class AuthenticatedSocksTimeoutTest {

    @Test
    void aSlowSmtpReplySurvivesTenSecondsAndThePooledConnectionRemainsReusable() throws Exception {
        final RunningSocksServer proxy = new SyncSocksServer().startServer(0, ServerSocketFactory.getDefault());
        try (RecordingSmtpServer smtp = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250 localhost");
            connection.acceptMessages(peer, number -> {
                if (number == 1) {
                    // Only this scenario crosses the former ten-second bridge timeout using real sockets.
                    try {
                        Thread.sleep(11000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Interrupted while delaying the SMTP reply", interrupted);
                    }
                }
                return "250 queued";
            });
        }); Mailer mailer = mailer(smtp.port(), proxy.getPort(), Duration.ofSeconds(30))) {
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);

            assertThat(smtp.messages()).hasSize(2);
            assertThat(smtp.connectionCount()).as("same pooled connection after the delayed reply").isEqualTo(1);
        } finally {
            proxy.stop();
        }
    }

    @Test
    void theConfiguredShortClientReadTimeoutStillClosesTheTunnel() throws Exception {
        final RunningSocksServer proxy = new SyncSocksServer().startServer(0, ServerSocketFactory.getDefault());
        // Withhold the SMTP greeting. The client's read timeout must close the otherwise transparent tunnel.
        try (RecordingSmtpServer smtp = new RecordingSmtpServer((peer, connection) -> peer.expectClosed());
             Mailer mailer = mailer(smtp.port(), proxy.getPort(), Duration.ofMillis(300))) {
            assertThatThrownBy(() -> mailer.sync().sendMail(email()))
                    .isInstanceOf(MailException.class)
                    .hasRootCauseInstanceOf(SocketTimeoutException.class);
            assertThat(smtp.messages()).isEmpty();
            assertThat(smtp.connectionCount()).isEqualTo(1);
        } finally {
            proxy.stop();
        }
    }

    private static Mailer mailer(final int smtpPort, final int proxyPort, final Duration readTimeout) {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
                .withSMTPServer("localhost", smtpPort)
                .withConnectionConfig(ConnectionConfig.builder()
                        .withSmtpClientHostname("probe.example.test")
                        .withConnectTimeout(Duration.ofSeconds(5))
                        .withReadTimeout(readTimeout).build())
                .withConnectionPoolConfig(ConnectionPoolConfig.builder()
                        .withCoreSize(0).withMaxSize(1).withExpireAfter(Duration.ofMinutes(1)).build())
                .withProxyConfig(SocksProxyConfig.builder()
                        .withHost("localhost").withPort(proxyPort)
                        .withUsername("proxy-user").withPassword("proxy-secret").build())
                .buildMailer();
    }

    private static Email email() {
        return SimpleJavaMail.fromDefaults().emailBuilder().startingBlank()
                .from("sender@example.test").to("recipient@example.test")
                .withPlainText("Transparent SOCKS forwarding").buildEmail();
    }
}
