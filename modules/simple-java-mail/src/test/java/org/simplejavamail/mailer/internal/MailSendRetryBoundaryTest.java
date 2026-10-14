package org.simplejavamail.mailer.internal;

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.MailRecipientDisposition;
import org.simplejavamail.api.mailer.MailRecipientResult;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.OperationalConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MailSendRetryBoundaryTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("wholeMessageRetryEvidence")
    void wholeMessageRetryRequiresConsistentTemporaryRefusalEvidence(final String scenario, final MailSubmissionStatus status,
            final MailRetryDisposition retryDisposition, final Integer replyCode, final List<MailRecipientResult> recipients,
            final boolean expectedRetry) {
        final MailSubmissionReceipt receipt = MailSubmissionReceiptImpl.builder()
            .emailId(null)
            .smtpResponse(response(replyCode))
            .submittedAt(Instant.EPOCH)
            .status(status)
            .recipientResults(recipients)
            .retryDisposition(retryDisposition)
            .build();
        final MailSubmissionException refusal = new MailSubmissionException(scenario, new MessagingException("provider"), receipt);
        assertThat(MailSendRetryEligibility.isTemporaryWholeMessageRefusal(refusal)).as(scenario).isEqualTo(expectedRetry);
    }

    private static Stream<Arguments> wholeMessageRetryEvidence() {
        final MailRecipientResult temporary = recipient(MailRecipientDisposition.VALID_UNSENT, 450);
        final MailRecipientResult permanent = recipient(MailRecipientDisposition.VALID_UNSENT, 550);
        return Stream.of(
                Arguments.of("temporary transaction refusal", MailSubmissionStatus.REJECTED, MailRetryDisposition.SAFE_TO_RETRY_ALL,
                        451, List.of(), true),
                Arguments.of("temporary recipient is enough without a transaction reply", MailSubmissionStatus.REJECTED,
                        MailRetryDisposition.SAFE_TO_RETRY_ALL, null, List.of(temporary), true),
                Arguments.of("positive RCPT followed by temporary DATA refusal", MailSubmissionStatus.REJECTED,
                        MailRetryDisposition.SAFE_TO_RETRY_ALL, 451, List.of(recipient(MailRecipientDisposition.VALID_UNSENT, 250)), true),
                Arguments.of("no temporary evidence", MailSubmissionStatus.REJECTED, MailRetryDisposition.SAFE_TO_RETRY_ALL,
                        null, List.of(), false),
                Arguments.of("unsent recipient without a reply is not temporary evidence", MailSubmissionStatus.REJECTED,
                        MailRetryDisposition.SAFE_TO_RETRY_ALL, null, List.of(recipient(MailRecipientDisposition.VALID_UNSENT, null)), false),
                Arguments.of("partial acceptance", MailSubmissionStatus.PARTIALLY_ACCEPTED, MailRetryDisposition.SAFE_TO_RETRY_ALL,
                        451, List.of(), false),
                Arguments.of("unknown acceptance", MailSubmissionStatus.UNKNOWN, MailRetryDisposition.SAFE_TO_RETRY_ALL,
                        451, List.of(), false),
                Arguments.of("confirmed acceptance", MailSubmissionStatus.ACCEPTED, MailRetryDisposition.SAFE_TO_RETRY_ALL,
                        451, List.of(), false),
                Arguments.of("only unaccepted recipients may be retried", MailSubmissionStatus.REJECTED,
                        MailRetryDisposition.SAFE_TO_RETRY_UNACCEPTED, 451, List.of(), false),
                Arguments.of("caller policy is required", MailSubmissionStatus.REJECTED, MailRetryDisposition.CALLER_POLICY_REQUIRED,
                        451, List.of(), false),
                Arguments.of("replay is forbidden", MailSubmissionStatus.REJECTED, MailRetryDisposition.DO_NOT_RETRY, 451, List.of(), false),
                Arguments.of("accepted recipient contradicts whole-message refusal", MailSubmissionStatus.REJECTED,
                        MailRetryDisposition.SAFE_TO_RETRY_ALL, 451, List.of(recipient(MailRecipientDisposition.ACCEPTED, 250)), false),
                Arguments.of("invalid recipient prevents whole-envelope replay", MailSubmissionStatus.REJECTED,
                        MailRetryDisposition.SAFE_TO_RETRY_ALL, 451, List.of(recipient(MailRecipientDisposition.INVALID, 550)), false),
                Arguments.of("invalid recipient remains invalid even with a temporary reply", MailSubmissionStatus.REJECTED,
                        MailRetryDisposition.SAFE_TO_RETRY_ALL, 451, List.of(recipient(MailRecipientDisposition.INVALID, 450)), false),
                Arguments.of("unknown recipient acceptance", MailSubmissionStatus.REJECTED, MailRetryDisposition.SAFE_TO_RETRY_ALL,
                        451, List.of(recipient(MailRecipientDisposition.UNKNOWN, null)), false),
                Arguments.of("one permanent RCPT refusal vetoes temporary refusal", MailSubmissionStatus.REJECTED,
                        MailRetryDisposition.SAFE_TO_RETRY_ALL, 450, List.of(temporary, permanent), false),
                Arguments.of("permanent transaction reply vetoes a temporary recipient", MailSubmissionStatus.REJECTED,
                        MailRetryDisposition.SAFE_TO_RETRY_ALL, 550, List.of(temporary), false),
                Arguments.of("positive transaction reply is not temporary", MailSubmissionStatus.REJECTED,
                        MailRetryDisposition.SAFE_TO_RETRY_ALL, 250, List.of(temporary), false),
                Arguments.of("intermediate transaction reply is not temporary", MailSubmissionStatus.REJECTED,
                        MailRetryDisposition.SAFE_TO_RETRY_ALL, 354, List.of(temporary), false),
                Arguments.of("provider EOF sentinel is not a temporary reply", MailSubmissionStatus.REJECTED,
                        MailRetryDisposition.SAFE_TO_RETRY_ALL, -1, List.of(temporary), false));
    }

    private static MailRecipientResult recipient(final MailRecipientDisposition disposition, final Integer replyCode) {
        return new MailRecipientResult("receiver@example.test", "receiver@example.test", disposition, replyCode == null ? null : true, response(replyCode));
    }

    private static SmtpServerResponse response(final Integer replyCode) {
        return replyCode == null ? null : new SmtpServerResponse(replyCode, "fixture reply");
    }

    @Test
    void retryGuidanceAloneNeverAuthorizesReplay() {
        assertThat(MailSendRetryEligibility.isTemporaryWholeMessageRefusal(failure(MailSubmissionStatus.REJECTED, null, List.of()))).isFalse();
        assertThat(MailSendRetryEligibility.isTemporaryWholeMessageRefusal(failure(MailSubmissionStatus.REJECTED, 451, List.of()))).isTrue();
        assertThat(MailSendRetryEligibility.isTemporaryWholeMessageRefusal(failure(MailSubmissionStatus.REJECTED, 550, List.of()))).isFalse();
        assertThat(MailSendRetryEligibility.isTemporaryWholeMessageRefusal(failure(MailSubmissionStatus.UNKNOWN, 451, List.of()))).isFalse();
        assertThat(MailSendRetryEligibility.isTemporaryWholeMessageRefusal(failure(MailSubmissionStatus.PARTIALLY_ACCEPTED, 451, List.of()))).isFalse();
        final MailRecipientResult temporary = new MailRecipientResult("a@example.test", "a@example.test", MailRecipientDisposition.VALID_UNSENT,
                true, new SmtpServerResponse(450, "temporary"));
        final MailRecipientResult permanent = new MailRecipientResult("b@example.test", "b@example.test", MailRecipientDisposition.VALID_UNSENT,
                true, new SmtpServerResponse(550, "permanent"));
        assertThat(MailSendRetryEligibility.isTemporaryWholeMessageRefusal(failure(MailSubmissionStatus.REJECTED, null, List.of(temporary)))).isTrue();
        assertThat(MailSendRetryEligibility.isTemporaryWholeMessageRefusal(
                failure(MailSubmissionStatus.REJECTED, 450, List.of(temporary, permanent)))).isFalse();
    }

    @Test
    void backoffAndJitterAreBoundedWithoutIntegerOverflow() {
        final MailSendRetryPolicy policy = MailSendRetryPolicy.builder().withMaximumAttempts(Integer.MAX_VALUE).withRetryDelay(Duration.ofSeconds(10))
                .withRetryBackoff(3, Duration.ofSeconds(50)).withRetryJitter(0.2).build();
        assertThat(MailSendRetryDelay.afterAttempt(policy, 1, () -> 0)).isEqualTo(Duration.ofSeconds(8));
        assertThat(MailSendRetryDelay.afterAttempt(policy, 1, () -> 1)).isEqualTo(Duration.ofSeconds(12));
        assertThat(MailSendRetryDelay.afterAttempt(policy, Integer.MAX_VALUE, () -> 1)).isEqualTo(Duration.ofSeconds(50));
        assertThat(MailSendRetryDelay.afterAttempt(policy, Integer.MAX_VALUE, () -> 0)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void replayByteReservationsAreAtomicBoundedAndReleasedExactlyOnce() {
        final OperationalConfig config = mock(OperationalConfig.class);
        when(config.getMailSendRetryPolicy()).thenReturn(MailSendRetryPolicy.builder().withMaximumMessageSize(10).withMemoryLimit(15).build());
        final MailSendReplayBudget budget = new MailSendReplayBudget(config);
        final MailSendReplayBudget.Reservation first = budget.reserve();
        final MailSendReplayBudget.Reservation second = budget.reserve();
        first.retain(10);
        second.retain(5);
        assertThatThrownBy(() -> second.retain(1)).hasMessageContaining("memory budget");
        assertThatThrownBy(() -> first.retain(1)).hasMessageContaining("replay limit");
        first.close();
        first.close();
        second.retain(5);
        second.close();
        try (MailSendReplayBudget.Reservation next = budget.reserve()) { next.retain(10); }
    }

    @Test
    void concurrentReplayReservationsCannotOvercommitTheAggregateBudget() throws Exception {
        final OperationalConfig config = mock(OperationalConfig.class);
        when(config.getMailSendRetryPolicy()).thenReturn(MailSendRetryPolicy.builder().withMaximumMessageSize(1).withMemoryLimit(4).build());
        final MailSendReplayBudget budget = new MailSendReplayBudget(config);
        final ExecutorService workers = Executors.newFixedThreadPool(8);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch tried = new CountDownLatch(8);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final List<Future<?>> results = new ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                results.add(workers.submit(() -> {
                    try (MailSendReplayBudget.Reservation reservation = budget.reserve()) {
                        start.await();
                        try {
                            reservation.retain(1);
                            accepted.incrementAndGet();
                        } catch (MailerException full) {
                            rejected.incrementAndGet();
                        } finally {
                            tried.countDown();
                        }
                        // Keep all successful reservations alive until every contender has tried: no release may hide overcommit.
                        assertThat(release.await(5, SECONDS)).isTrue();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }));
            }
            start.countDown();
            assertThat(tried.await(5, SECONDS)).isTrue();
            assertThat(accepted).hasValue(4);
            assertThat(rejected).hasValue(4);
            release.countDown();
            for (Future<?> result : results) { result.get(5, SECONDS); }
            try (MailSendReplayBudget.Reservation next = budget.reserve()) { next.retain(1); }
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    private static MailSubmissionException failure(final MailSubmissionStatus status, final Integer code, final List<MailRecipientResult> recipients) {
        return new MailSubmissionException("refused", new MessagingException("provider"), MailSubmissionReceiptImpl.builder()
            .emailId(null)
            .smtpResponse(code == null ? null : new SmtpServerResponse(code, "reply"))
            .submittedAt(Instant.EPOCH)
            .status(status)
            .recipientResults(recipients)
            .retryDisposition(MailRetryDisposition.SAFE_TO_RETRY_ALL)
            .build());
    }
}
