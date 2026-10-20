package org.simplejavamail.mailer.failover;

import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import testutil.smtp.RecordingSmtpServer;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static testutil.MailerTestConfig.updateMailer;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.*;
import static org.simplejavamail.mailer.failover.FailoverSmtpContractTest.unavailableServer;

/** This same-Mailer setup overlap requires the direct runtime: a pool serializes allocation within each registration. */
@Timeout(45)
class FailoverDirectRecoveryContracts {
    @Test
    void cancellingARecheckMustNotDiscardAnIndependentSuccessfulConnection() throws Exception {
        assertThat(DIRECT).as("same-Mailer connection overlap needs the pool-free runtime").isTrue();
        final CountDownLatch retryStarted = new CountDownLatch(1);
        final CountDownLatch independentStarted = new CountDownLatch(1);
        final CountDownLatch finishIndependent = new CountDownLatch(1);
        final AtomicInteger connections = new AtomicInteger();
        try (RecordingSmtpServer entry = unavailableServer(); RecordingSmtpServer shared = new RecordingSmtpServer((peer, connection) -> {
            final int attempt = connections.incrementAndGet();
            if (attempt <= 3) {
                peer.reply("421 unavailable");
            } else if (attempt == 4) {
                retryStarted.countDown();
                peer.expectClosed();
            } else {
                independentStarted.countDown();
                await(finishIndependent);
                connection.greet(peer, "250 localhost");
                connection.acceptMessages(peer);
            }
        }); Mailer independent = updateMailer(builder(shared.port()), fixture -> fixture.withConnectionPoolConfig(fixture.getConnectionPoolConfig().toBuilder().withMaxSize(2).build())).buildMailer();
             Mailer initiating = builder(entry.port()).withFailoverMailer(independent,
                     FailoverPolicy.builder().withPrimaryConnectionAttempts(2).withPrimaryRecheckInterval(Duration.ofMillis(1))
                             .withPrimaryRecheckBackoff(1_000_000, Duration.ofHours(1)).build()).buildMailer()) {
            MailSend<MailSubmissionReceipt> recheck = null;
            try {
                assertThat(catchThrowable(() -> initiating.sync().sendMail(email()))).isNotNull();
                // Let the first public interval elapse. No assertion depends on how quickly this process resumes.
                new CountDownLatch(1).await(20, java.util.concurrent.TimeUnit.MILLISECONDS);
                recheck = initiating.async().sendMail(email());
                await(retryStarted);
                final var unrelated = independent.async().sendMail(email());
                await(independentStarted);
                recheck.requestCancellation();
                final var cancelled = recheck;
                assertThat(catchThrowable(() -> cancelled.getCompletion().get(10, SECONDS))).isNotNull();
                finishIndependent.countDown();
                assertThat(unrelated.getCompletion().get(10, SECONDS).getStatus()).isEqualTo(ACCEPTED);
                assertThat(shared.messages()).hasSize(1);

                // No internal state assertion: restored preference must be visible on the very next routed send.
                assertThat(initiating.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
                assertThat(shared.messages()).hasSize(2);
            } finally {
                finishIndependent.countDown();
                if (recheck != null) {
                    recheck.requestCancellation();
                }
            }
        }
    }
}
