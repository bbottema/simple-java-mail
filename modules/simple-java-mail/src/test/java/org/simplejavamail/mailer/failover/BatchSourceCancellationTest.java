package org.simplejavamail.mailer.failover;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendTimeoutException;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import testutil.smtp.RecordingSmtpServer;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.builder;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.email;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.policy;

/** Stops at lazy application boundaries without undoing an already completed individual send. */
@Timeout(40)
class BatchSourceCancellationTest {
    enum Route { LOGGING, CUSTOM, SMTP, SMTP_RETRY, FAILOVER }

    @ParameterizedTest
    @CsvSource({"LOGGING,false", "LOGGING,true", "CUSTOM,false", "CUSTOM,true", "SMTP,false", "SMTP,true",
            "SMTP_RETRY,false", "SMTP_RETRY,true", "FAILOVER,false", "FAILOVER,true"})
    void observerCancellationNeverAsksTheSourceAgain(final Route route, final boolean moreEmails) throws Exception {
        final CompletableFuture<MailSend<Void>> ready = new CompletableFuture<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Iterable<Email> source = () -> {
            calls.add("iterator");
            return new Iterator<>() {
                private boolean returned;
                @Override public boolean hasNext() { calls.add("hasNext"); return !returned || moreEmails; }
                @Override public Email next() { calls.add("next"); returned = true; return email(); }
            };
        };
        try (Fixture fixture = new Fixture(route, outcome -> ready.join().requestCancellation())) {
            final MailSend<Void> batch = fixture.mailer.async().sendMailsInSimpleBatch(source);
            ready.complete(batch);
            assertThat(failure(batch)).isInstanceOf(MailSendCancelledException.class);
            assertThat(calls).containsExactlyElementsOf(route == Route.FAILOVER
                    ? List.of("iterator", "hasNext", "next") : List.of("iterator", "hasNext", "hasNext", "next"));
            assertThat(fixture.outcomes).hasSize(1).allSatisfy(outcome -> assertThat(outcome.isSuccessful()).isTrue());
            fixture.assertSubmissions(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void cancellationInsideIteratorStopsBeforeInitialHasNext(final Route route) throws Exception {
        final CompletableFuture<MailSend<Void>> ready = new CompletableFuture<>();
        final AtomicInteger hasNextCalls = new AtomicInteger();
        final Iterable<Email> source = () -> {
            ready.join().requestCancellation();
            return new Iterator<>() {
                @Override public boolean hasNext() { hasNextCalls.incrementAndGet(); return false; }
                @Override public Email next() { throw new AssertionError("cancelled source must not be advanced"); }
            };
        };
        try (Fixture fixture = new Fixture(route, outcome -> { })) {
            final MailSend<Void> batch = fixture.mailer.async().sendMailsInSimpleBatch(source);
            ready.complete(batch);
            assertThat(failure(batch)).isInstanceOf(MailSendCancelledException.class);
            assertThat(hasNextCalls).hasValue(0);
            assertThat(fixture.outcomes).isEmpty();
            fixture.assertNoConnections();
        }
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void cancellationInsideHasNextPreventsAnotherSourceCallback(final Route route) throws Exception {
        final CompletableFuture<MailSend<Void>> ready = new CompletableFuture<>();
        final AtomicInteger hasNextCalls = new AtomicInteger();
        final Iterable<Email> source = () -> new Iterator<>() {
            @Override public boolean hasNext() {
                hasNextCalls.incrementAndGet();
                ready.join().requestCancellation();
                return true;
            }
            @Override public Email next() { throw new AssertionError("cancelled source must not be advanced"); }
        };
        try (Fixture fixture = new Fixture(route, outcome -> { })) {
            final MailSend<Void> batch = fixture.mailer.async().sendMailsInSimpleBatch(source);
            ready.complete(batch);
            assertThat(failure(batch)).isInstanceOf(MailSendCancelledException.class);
            assertThat(hasNextCalls).hasValue(1);
            assertThat(fixture.outcomes).isEmpty();
            fixture.assertNoConnections();
        }
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void normalLazyCallbackOrderAndOutcomesRemainUnchanged(final Route route) throws Exception {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Iterable<Email> source = () -> {
            calls.add("iterator");
            return new Iterator<>() {
                private int returned;
                @Override public boolean hasNext() { calls.add("hasNext"); return returned < 2; }
                @Override public Email next() { calls.add("next"); returned++; return email(); }
            };
        };
        try (Fixture fixture = new Fixture(route, outcome -> { })) {
            fixture.mailer.async().sendMailsInSimpleBatch(source).getCompletion().get(15, SECONDS);
            assertThat(calls).containsExactlyElementsOf(route == Route.FAILOVER
                    ? List.of("iterator", "hasNext", "next", "hasNext", "next", "hasNext")
                    : List.of("iterator", "hasNext", "hasNext", "next", "hasNext", "next", "hasNext"));
            assertThat(fixture.outcomes).hasSize(2).allSatisfy(outcome -> assertThat(outcome.isSuccessful()).isTrue());
            fixture.assertSubmissions(2);
        }
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void emptySourceNeverOpensAConnection(final Route route) throws Exception {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Iterable<Email> source = () -> {
            calls.add("iterator");
            return new Iterator<>() {
                @Override public boolean hasNext() { calls.add("hasNext"); return false; }
                @Override public Email next() { throw new AssertionError("empty source must not be advanced"); }
            };
        };
        try (Fixture fixture = new Fixture(route, outcome -> { })) {
            fixture.mailer.async().sendMailsInSimpleBatch(source).getCompletion().get(15, SECONDS);
            assertThat(calls).containsExactly("iterator", "hasNext");
            assertThat(fixture.outcomes).isEmpty();
            fixture.assertNoConnections();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOGGING", "FAILOVER"})
    void sourceFailureKeepsItsIdentityWhenTheCallbackAlsoCancels(final Route route) throws Exception {
        final CompletableFuture<MailSend<Void>> ready = new CompletableFuture<>();
        final RuntimeException original = new IllegalStateException("source failed after requesting cancellation");
        final Iterable<Email> source = () -> { ready.join().requestCancellation(); throw original; };
        try (Fixture fixture = new Fixture(route, outcome -> { })) {
            final MailSend<Void> batch = fixture.mailer.async().sendMailsInSimpleBatch(source);
            ready.complete(batch);
            assertThat(failure(batch)).isSameAs(original);
            assertThat(fixture.outcomes).isEmpty();
            fixture.assertNoConnections();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOGGING", "FAILOVER"})
    void anExpiredDeadlineWaitsForTheActiveCallbackThenStopsBeforeHasNext(final Route route) throws Exception {
        final CountDownLatch iteratorEntered = new CountDownLatch(1);
        final CountDownLatch returnIterator = new CountDownLatch(1);
        final AtomicInteger hasNextCalls = new AtomicInteger();
        final Iterable<Email> source = () -> {
            iteratorEntered.countDown();
            await(returnIterator);
            return new Iterator<>() {
                @Override public boolean hasNext() { hasNextCalls.incrementAndGet(); return false; }
                @Override public Email next() { throw new AssertionError("expired source must not be advanced"); }
            };
        };
        try (Fixture fixture = new Fixture(route, outcome -> { }, Duration.ofSeconds(2))) {
            final MailSend<Void> batch = fixture.mailer.async().sendMailsInSimpleBatch(source);
            try {
                await(iteratorEntered);
                // Outlast the whole budget without asking SJM to interrupt an application callback already running.
                assertThat(new CountDownLatch(1).await(2250, MILLISECONDS)).isFalse();
                assertThat(batch.getCompletion().isDone()).isFalse();
            } finally {
                returnIterator.countDown();
            }
            assertThat(failure(batch)).isInstanceOf(MailSendTimeoutException.class);
            assertThat(hasNextCalls).hasValue(0);
            assertThat(fixture.outcomes).isEmpty();
            fixture.assertNoConnections();
        }
    }

    @Test
    void cancellationDuringRouteSelectionDoesNotCallNextOrVisitFailover() throws Exception {
        final CountDownLatch selecting = new CountDownLatch(1);
        final AtomicInteger nextCalls = new AtomicInteger();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final Iterable<Email> source = () -> new Iterator<>() {
            @Override public boolean hasNext() { return true; }
            @Override public Email next() { nextCalls.incrementAndGet(); return email(); }
        };
        try (RecordingSmtpServer primary = new RecordingSmtpServer((peer, connection) -> {
            selecting.countDown();
            peer.expectClosed();
        }); RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer alternative = builder(secondary.port()).buildMailer();
             Mailer mailer = builder(primary.port()).withFailoverMailer(alternative, policy())
                     .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer()) {
            final MailSend<Void> batch = mailer.async().sendMailsInSimpleBatch(source);
            try { await(selecting); }
            finally { batch.requestCancellation(); }
            assertThat(failure(batch)).isInstanceOf(MailSendCancelledException.class);
            assertThat(nextCalls).hasValue(0);
            assertThat(outcomes).isEmpty();
            assertThat(primary.messages()).isEmpty();
            assertThat(secondary.connectionCount()).isZero();
        }
    }

    private static void await(final CountDownLatch latch) {
        try { assertThat(latch.await(10, SECONDS)).isTrue(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static Throwable failure(final MailSend<Void> batch) throws Exception {
        return batch.getCompletion().handle((value, failure) -> failure).get(15, SECONDS);
    }

    private static final class Fixture implements AutoCloseable {
        private final Route route;
        private final RecordingSmtpServer primary;
        private final RecordingSmtpServer secondary;
        private final Mailer alternative;
        private final Mailer mailer;
        private final AtomicInteger customSubmissions = new AtomicInteger();
        private final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();

        private Fixture(final Route route, final java.util.function.Consumer<MailSendOutcome> observer) throws Exception {
            this(route, observer, null);
        }

        private Fixture(final Route route, final java.util.function.Consumer<MailSendOutcome> observer, final Duration timeout) throws Exception {
            this.route = route;
            primary = route == Route.FAILOVER ? FailoverSmtpContractTest.unavailableServer() : new RecordingSmtpServer();
            secondary = new RecordingSmtpServer();
            alternative = builder(secondary.port()).buildMailer();
            final var configured = builder(primary.port()).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcome -> {
                outcomes.add(outcome);
                observer.accept(outcome);
            }).build());
            if (timeout != null) {
                configured.withMailSendTimeout(timeout);
            }
            if (route == Route.LOGGING) {
                configured.withTransportModeLoggingOnly(true);
            } else if (route == Route.CUSTOM) {
                configured.withCustomMailer(new CustomMailer() {
                    @Override public void testConnection(final OperationalConfig configuration, final Session session) { }
                    @Override public void sendMessage(final OperationalConfig configuration, final Session session,
                            final Email email, final MimeMessage message) { customSubmissions.incrementAndGet(); }
                });
            } else if (route == Route.FAILOVER) {
                configured.withFailoverMailer(alternative, policy());
            } else if (route == Route.SMTP_RETRY) {
                configured.withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumAttempts(2).withRetryDelay(Duration.ofMillis(1)).build());
            }
            mailer = configured.buildMailer();
        }

        private void assertSubmissions(final int count) {
            assertThat(customSubmissions).hasValue(route == Route.CUSTOM ? count : 0);
            assertThat(primary.messages()).hasSize(route == Route.SMTP || route == Route.SMTP_RETRY ? count : 0);
            assertThat(secondary.messages()).hasSize(route == Route.FAILOVER ? count : 0);
        }

        private void assertNoConnections() {
            assertThat(primary.connectionCount()).isZero();
            assertThat(secondary.connectionCount()).isZero();
            assertThat(customSubmissions).hasValue(0);
        }

        @Override public void close() throws Exception {
            try (RecordingSmtpServer ignoredPrimary = primary; RecordingSmtpServer ignoredSecondary = secondary;
                 Mailer ignoredAlternative = alternative; Mailer ignoredMailer = mailer) { }
        }
    }
}
