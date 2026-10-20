package org.simplejavamail.mailer.failover;

import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.config.ConfigLoader;
import testutil.smtp.RecordingSmtpServer;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static testutil.MailerTestConfig.updateMailer;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.*;
import static org.simplejavamail.mailer.failover.FailoverSmtpContractTest.unavailableServer;

@Timeout(45)
class FailoverRecoveryContractTest {
    private ExecutorService concurrentSends;

    @BeforeEach
    void startWorkers() {
        // The pool-free default has one worker. Explicit application workers make overlapping direct connections possible too.
        concurrentSends = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void stopWorkers() throws InterruptedException {
        concurrentSends.shutdownNow();
        assertThat(concurrentSends.awaitTermination(10, SECONDS)).isTrue();
    }

    @Test
    void oneDueRecheckDoesNotHoldOtherSendsOnTheWorkingDestination() throws Exception {
        final CountDownLatch recheckStarted = new CountDownLatch(1);
        final CountDownLatch finishRecheck = new CountDownLatch(1);
        try (RecordingSmtpServer primary = new RecordingSmtpServer((peer, connection) -> {
            if (connection.number() == 1) {
                peer.reply("421 unavailable");
            } else {
                recheckStarted.countDown();
                await(finishRecheck);
                connection.greet(peer, "250 localhost");
                connection.acceptMessages(peer);
            }
        }); RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer destination = builder(secondary.port()).buildMailer();
             Mailer initiating = updateMailer(builder(primary.port()), fixture -> fixture.withAsyncExecutionConfig(fixture.getAsyncExecutionConfig().toBuilder().withExecutorService(concurrentSends).build()))
                     .withFailoverMailer(destination, immediatelyDuePolicy()).buildMailer()) {
            try {
                initiating.sync().sendMail(email());
                final var recheck = initiating.async().sendMail(email());
                await(recheckStarted);
                initiating.async().sendMail(email()).getCompletion().get(10, SECONDS);
                assertThat(primary.connectionCount()).isEqualTo(2);
                assertThat(secondary.messages()).hasSize(2);
                finishRecheck.countDown();
                recheck.getCompletion().get(10, SECONDS);
                initiating.sync().sendMail(email());
                assertThat(primary.messages()).hasSize(2);
                assertThat(secondary.messages()).hasSize(2);
            } finally {
                finishRecheck.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void overlappingSuccessOutweighsFailureInEitherCompletionOrder(final boolean successFirst) throws Exception {
        final CountDownLatch failedStarted = new CountDownLatch(1);
        final CountDownLatch successfulStarted = new CountDownLatch(1);
        final CountDownLatch releaseFailure = new CountDownLatch(1);
        final CountDownLatch releaseSuccess = new CountDownLatch(1);
        try (RecordingSmtpServer primary = new RecordingSmtpServer((peer, connection) -> {
            if (connection.number() == 1) {
                failedStarted.countDown();
                await(releaseFailure);
                peer.reply("421 unavailable");
            } else {
                successfulStarted.countDown();
                await(releaseSuccess);
                connection.greet(peer, "250 localhost");
                connection.acceptMessages(peer);
            }
        }); RecordingSmtpServer secondary = new RecordingSmtpServer(); RecordingSmtpServer entry = unavailableServer()) {
            // Separate instances of one named definition share observations but have separate pools. One pool serializes its allocator.
            final SimpleJavaMail factory = namedRouteFactory(primary.port(), secondary.port());
            try (Mailer initiating = updateMailer(builder(factory, entry.port()), fixture -> fixture.withAsyncExecutionConfig(fixture.getAsyncExecutionConfig().toBuilder().withExecutorService(concurrentSends).build())).buildMailer();
                 Mailer independent = updateMailer(builder(factory, entry.port()), fixture -> fixture.withAsyncExecutionConfig(fixture.getAsyncExecutionConfig().toBuilder().withExecutorService(concurrentSends).build())).buildMailer()) {
                try {
                    final var failing = initiating.async().sendMail(email());
                    await(failedStarted);
                    final var succeeding = independent.async().sendMail(email());
                    await(successfulStarted);
                    if (successFirst) {
                        releaseSuccess.countDown();
                        succeeding.getCompletion().get(10, SECONDS);
                    }
                    releaseFailure.countDown();
                    failing.getCompletion().get(10, SECONDS);
                    releaseSuccess.countDown();
                    succeeding.getCompletion().get(10, SECONDS);
                    initiating.sync().sendMail(email());
                    assertThat(primary.messages()).hasSize(2);
                    assertThat(secondary.messages()).hasSize(1);
                } finally {
                    releaseFailure.countDown();
                    releaseSuccess.countDown();
                }
            }
        }
    }

    @Test
    void cancellingAcquisitionReleasesCapacityWithoutUsingFailover() throws Exception {
        final CountDownLatch acceptedSocket = new CountDownLatch(1);
        try (RecordingSmtpServer primary = new RecordingSmtpServer((peer, connection) -> {
            if (connection.number() == 1) {
                acceptedSocket.countDown();
                peer.expectClosed();
            } else {
                connection.greet(peer, "250 localhost");
                connection.acceptMessages(peer);
            }
        }); RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer destination = builder(secondary.port()).buildMailer();
             Mailer initiating = updateMailer(builder(primary.port()), fixture -> fixture.withConnectionPoolConfig(fixture.getConnectionPoolConfig().toBuilder().withMaxSize(1).build())).withFailoverMailer(destination, policy()).buildMailer()) {
            final var cancelled = initiating.async().sendMail(email());
            await(acceptedSocket);
            cancelled.requestCancellation();
            assertThat(catchThrowable(() -> cancelled.getCompletion().get(10, SECONDS))).isNotNull();
            assertThat(secondary.connectionCount()).isZero();
            assertThat(initiating.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(primary.messages()).hasSize(1);
            assertThat(secondary.connectionCount()).isZero();
        }
    }

    private static FailoverPolicy immediatelyDuePolicy() {
        return FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofNanos(1)).build();
    }

    private static SimpleJavaMail namedRouteFactory(final int sharedPort, final int lastPort) {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("simplejavamail.failover.mailer", "shared");
        properties.put("simplejavamail.mailers.shared.failover.mailer", "last");
        addNamedDestination(properties, "shared", sharedPort);
        addNamedDestination(properties, "last", lastPort);
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(properties).load());
    }

    private static void addNamedDestination(final Map<String, Object> properties, final String name, final int port) {
        final String prefix = "simplejavamail.mailers." + name + ".";
        properties.put(prefix + "smtp.host", "localhost");
        properties.put(prefix + "smtp.port", port);
        properties.put(prefix + "smtp.clienthostname", "probe.example.test");
        properties.put(prefix + "transportstrategy", "SMTP");
        properties.put(prefix + "defaults.connectionpool.coresize", 0);
        properties.put(prefix + "defaults.sessiontimeoutmillis", 30000);
    }
}
