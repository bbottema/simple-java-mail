package org.simplejavamail.mailer.failover;

import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.MailSendHandlers;
import jakarta.mail.Message.RecipientType;

import org.simplejavamail.api.SimpleJavaMail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import testutil.smtp.RecordingSmtpServer;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static testutil.MailerTestConfig.updateMailer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.*;



/** Pool-specific contracts run only with the real optional batch module and its upstream dependencies. */
@Timeout(45)
class FailoverPoolContractTest {

    @Test
    void ordinaryClusterUsesSelectedTemplatesForConcurrentOriginalReuse() throws Exception {
        final UUID cluster = UUID.randomUUID();
        final Email original = MAIL.emailBuilder().startingBlank().withRecipients(SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress("receiver@example.test").withType(RecipientType.TO).build())
                .withPlainText("unchanged original").buildEmail();
        try (RecordingSmtpServer first = new RecordingSmtpServer(); RecordingSmtpServer second = new RecordingSmtpServer();
             Mailer primary = updateMailer(builder(first.port()), fixture -> fixture.withConnectionPoolConfig(fixture.getConnectionPoolConfig().toBuilder().withClusterKey(cluster).withLoadBalancingStrategy(LoadBalancingStrategy.ROUND_ROBIN).build()))
                     .withEmailOverrides(template("first")).buildMailer();
             Mailer member = updateMailer(builder(second.port()), fixture -> fixture.withConnectionPoolConfig(fixture.getConnectionPoolConfig().toBuilder().withClusterKey(cluster).build())).withEmailOverrides(template("second")).buildMailer()) {
            final var one = primary.async().sendMail(original);
            final var two = primary.async().sendMail(original);
            one.getCompletion().get(10, SECONDS);
            two.getCompletion().get(10, SECONDS);
            assertSelectedTemplate(first, "first");
            assertSelectedTemplate(second, "second");
            assertThat(original.getFromRecipient()).isNull();
            assertThat(original.getHeaders()).isEmpty();
            assertThat(original.getRecipients()).hasSize(1);
        }
    }

    @Test
    void exhaustedPoolCapacityIsNotAnOutageAndRecoversAfterRelease() throws Exception {
        final CountDownLatch borrowed = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final ExecutorService callback = Executors.newSingleThreadExecutor();
        // Keep the released connection eligible for reuse even when the first send is slow on the test host.
        try (RecordingSmtpServer primary = new RecordingSmtpServer(); RecordingSmtpServer secondary = new RecordingSmtpServer();
             Mailer selected = builder(secondary.port()).buildMailer();
             Mailer initiating = updateMailer(builder(primary.port()), fixture -> fixture.withConnectionPoolConfig(fixture.getConnectionPoolConfig().toBuilder().withMaxSize(1).withClaimTimeout(Duration.ofMillis(150)).withExpireAfter(Duration.ofMillis(60000)).build()))
                     .withFailoverMailer(selected, policy()).buildMailer()) {
            try {
                final var occupied = callback.submit(() -> {
                    initiating.withOpenConnection(sender -> {
                        borrowed.countDown();
                        await(release);
                        sender.sendMail(email());
                    });
                    return null;
                });
                await(borrowed);
                assertThat(catchThrowable(() -> initiating.sync().sendMail(email()))).isInstanceOf(MailException.class);
                assertThat(secondary.connectionCount()).isZero();
                release.countDown();
                occupied.get(10, SECONDS);
                initiating.sync().sendMail(email());
                assertThat(primary.messages()).hasSize(2);
                assertThat(primary.connectionCount()).isEqualTo(1);
                assertThat(secondary.connectionCount()).isZero();
            } finally {
                release.countDown();
            }
        } finally {
            callback.shutdownNow();
            assertThat(callback.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    @Test
    void ordinaryObserverCanReenterAPoolOfOneAfterLeaseRelease() throws Exception {
        final AtomicReference<Mailer> active = new AtomicReference<>();
        final AtomicReference<Throwable> observerFailure = new AtomicReference<>();
        final AtomicInteger completions = new AtomicInteger();
        // Test lease release and reentry, not expiration while a slow host completes the first send.
        try (RecordingSmtpServer primary = new RecordingSmtpServer(); RecordingSmtpServer secondary = new RecordingSmtpServer();
             Mailer selected = builder(secondary.port()).buildMailer();
             Mailer initiating = updateMailer(builder(primary.port()), fixture -> fixture.withConnectionPoolConfig(fixture.getConnectionPoolConfig().toBuilder().withMaxSize(1).withExpireAfter(Duration.ofMillis(60000)).build()))
                     .withFailoverMailer(selected, policy())
                     .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcome -> {
                         if (completions.incrementAndGet() == 1) {
                             observerFailure.set(catchThrowable(() -> active.get().sync().sendMail(email())));
                         }
                     }).build()).buildMailer()) {
            active.set(initiating);
            initiating.sync().sendMail(email());
            assertThat(observerFailure.get()).isNull();
            assertThat(completions).hasValue(2);
            assertThat(primary.messages()).hasSize(2);
            assertThat(primary.connectionCount()).isEqualTo(1);
            assertThat(secondary.connectionCount()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentOrClearedFailoverDoesNotDisableBackgroundReplenishment(final boolean cleared) throws Exception {
        final CountDownLatch initialStarted = new CountDownLatch(1);
        final CountDownLatch failInitial = new CountDownLatch(1);
        final CountDownLatch replenished = new CountDownLatch(1);
        try (RecordingSmtpServer primary = new RecordingSmtpServer((peer, connection) -> {
            if (connection.number() == 1) {
                initialStarted.countDown();
                await(failInitial);
                peer.reply("421 unavailable");
            } else {
                replenished.countDown();
                connection.greet(peer, "250 localhost");
                connection.acceptMessages(peer);
            }
        }); RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer selected = builder(secondary.port()).buildMailer()) {
            final var configured = updateMailer(builder(primary.port()), fixture -> fixture.withConnectionPoolConfig(fixture.getConnectionPoolConfig().toBuilder().withCoreSize(1).withMaxSize(1).build()));
            if (cleared) {
                configured.withFailoverMailer(selected, policy()).clearFailoverMailer();
            }
            try (Mailer ordinary = configured.buildMailer()) {
                try {
                    await(initialStarted);
                    failInitial.countDown();
                    await(replenished);
                    assertThat(primary.messages()).isEmpty();
                    ordinary.sync().sendMail(email());
                    assertThat(primary.messages()).hasSize(1);
                    assertThat(secondary.connectionCount()).isZero();
                } finally {
                    failInitial.countDown();
                }
            }
        }
    }

    @Test
    void backgroundOutageIsRespectedByForegroundSendsDuringBackoff() throws Exception {
        final CountDownLatch initialStarted = new CountDownLatch(1);
        final CountDownLatch releaseInitial = new CountDownLatch(1);
        final CountDownLatch failedConnectionClosed = new CountDownLatch(1);
        try (RecordingSmtpServer primary = new RecordingSmtpServer((peer, connection) -> {
            initialStarted.countDown();
            await(releaseInitial);
            peer.reply("421 unavailable");
            peer.expectClosed();
            failedConnectionClosed.countDown();
        }); RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer selected = builder(secondary.port()).buildMailer();
             Mailer initiating = updateMailer(builder(primary.port()), fixture -> fixture.withConnectionPoolConfig(fixture.getConnectionPoolConfig().toBuilder().withCoreSize(1).withMaxSize(1).build()))
                     .withFailoverMailer(selected, FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofHours(1)).build()).buildMailer()) {
            try {
                await(initialStarted);
                releaseInitial.countDown();
                await(failedConnectionClosed);
                for (int attempt = 0; attempt < 3; attempt++) {
                    initiating.sync().sendMail(email());
                }
                assertThat(primary.connectionCount()).isEqualTo(1);
                assertThat(secondary.messages()).hasSize(3);
            } finally {
                releaseInitial.countDown();
            }
        }
    }

    private static Email template(final String name) {
        return MAIL.emailBuilder().startingBlank().from(name + "@example.test").withHeader("X-Relay", name)
                .withRecipients(SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress(name + "-maintainer@example.test").withType(RecipientType.BCC).build()).buildEmail();
    }

    private static void assertSelectedTemplate(final RecordingSmtpServer server, final String owner) {
        assertThat(server.messages()).hasSize(1);
        assertThat(new String(server.messages().get(0), UTF_8)).contains("X-Relay: " + owner).contains("From: " + owner + "@example.test");
        assertThat(server.commands()).contains("RCPT TO:<" + owner + "-maintainer@example.test>", "RCPT TO:<receiver@example.test>");
    }
}
