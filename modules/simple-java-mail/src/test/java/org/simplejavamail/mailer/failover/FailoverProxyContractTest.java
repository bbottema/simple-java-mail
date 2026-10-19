package org.simplejavamail.mailer.failover;

import java.time.Duration;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.bbottema.javasocksproxyserver.RunningSocksServer;
import org.bbottema.javasocksproxyserver.SyncSocksServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.Mailer;
import testutil.smtp.RecordingSmtpServer;

import javax.net.ServerSocketFactory;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.*;

/** Public sending plus real loopback SMTP/SOCKS; the remote proxy permits anonymous access but SJM's authenticated bridge is exercised. */
@Timeout(40)
class FailoverProxyContractTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuredCorePoolDoesNotMistakeTheUnstartedProxyBridgeForAPrimaryOutage(final boolean asynchronous) throws Exception {
        final RunningSocksServer proxy = new SyncSocksServer().startServer(0, ServerSocketFactory.getDefault());
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (IncidentCapture incidents = new IncidentCapture(); RecordingSmtpServer primary = new RecordingSmtpServer();
             RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer alternative = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withProxy("localhost", proxy.getPort(), "user", "password")
                     .withConnectionPoolCoreSize(1).withConnectionPoolMaxSize(1).withConnectionPoolExpireAfter(Duration.ofMillis(60000))
                     .withFailoverMailer(alternative, policy()).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer()) {
            // Observe a quiet background interval, not a send-performance threshold. The gate itself has a deterministic companion test.
            assertThat(incidents.observed.await(300, MILLISECONDS)).as("no SMTP outage before a foreground proxy scope exists").isFalse();
            assertThat(initiating.getSession().getProperty("mail.smtp.socks.port")).isEqualTo("0");
            for (int index = 0; index < 2; index++) {
                final var receipt = send(initiating, email(), asynchronous);
                assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
                assertThat(outcomes.get(index).getSubmissionReceipt()).containsSame(receipt);
                assertThat(outcomes.get(index).getDiagnostics().orElseThrow().getConnectionHistory()).singleElement()
                        .satisfies(step -> assertThat(step.isConnectionAcquired()).isTrue());
            }
            assertThat(primary.messages()).hasSize(2);
            assertThat(primary.connectionCount()).isEqualTo(1);
            assertThat(secondary.connectionCount()).isZero();
            assertThat(outcomes).hasSize(2);
            assertThat(incidents.observed.getCount()).isEqualTo(1);
        } finally {
            proxy.stop();
        }
    }

    @Test
    void aGenuineSetupFailureThroughTheProxyStillUsesFailover() throws Exception {
        final RunningSocksServer proxy = new SyncSocksServer().startServer(0, ServerSocketFactory.getDefault());
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer primary = new RecordingSmtpServer((peer, connection) -> {
            peer.reply("421 unavailable");
            peer.expectClosed();
        }); RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer alternative = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withProxy("localhost", proxy.getPort(), "user", "password")
                     .withConnectionPoolCoreSize(1).withConnectionPoolMaxSize(1).withFailoverMailer(alternative, policy())
                     .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer()) {
            final var receipt = initiating.sync().sendMail(email());
            assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
            assertThat(primary.connectionCount()).isEqualTo(1);
            assertThat(primary.messages()).isEmpty();
            assertThat(secondary.messages()).hasSize(1);
            assertSuccessfulOutcome(outcomes, receipt);
            final var history = outcomes.get(0).getDiagnostics().orElseThrow().getConnectionHistory();
            assertThat(history).hasSize(2);
            assertThat(history.get(0).isConnectionAttempted()).isTrue();
            assertThat(history.get(0).isConnectionAcquired()).isFalse();
            assertThat(history.get(1).isConnectionAcquired()).isTrue();
        } finally {
            proxy.stop();
        }
    }

    private static final class IncidentCapture extends AbstractAppender implements AutoCloseable {
        private final Logger logger = (Logger) LogManager.getLogger("org.simplejavamail.mailer.internal.DestinationConnectionObserver");
        private final CountDownLatch observed = new CountDownLatch(1);

        private IncidentCapture() {
            super("failover-proxy-incidents", null, null, true, Property.EMPTY_ARRAY);
            start();
            logger.addAppender(this);
        }

        @Override
        public void append(final LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.ERROR)) {
                observed.countDown();
            }
        }

        @Override
        public void close() {
            logger.removeAppender(this);
            stop();
        }
    }
}
