package org.simplejavamail.api.mailer;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MailSendTest {
    @Test
    void completionViewsCannotCompleteOrCancelTheOperation() {
        final CompletableFuture<Object> actual = new CompletableFuture<>();
        final AtomicInteger requests = new AtomicInteger();
        final MailSend<Object> send = new MailSend<>(actual, requests::incrementAndGet);
        final CompletableFuture<Object> cancelled = send.getCompletion();
        final CompletableFuture<Object> completed = send.getCompletion();
        cancelled.cancel(true);
        completed.complete("invented");
        assertThat(actual).isNotDone();
        assertThat(requests).hasValue(0);
        final Object receipt = new Object();
        actual.complete(receipt);
        assertThat(send.getCompletion().join()).isSameAs(receipt);
        assertThat(cancelled).isCancelled();
        assertThat(completed.join()).isEqualTo("invented");
    }

    @Test
    void everyViewKeepsTheExactFailureWithoutAnExtraCompletionException() {
        final CompletableFuture<Void> actual = new CompletableFuture<>();
        final MailSend<Void> send = new MailSend<>(actual, () -> { });
        final RuntimeException original = new RuntimeException("original");
        final AtomicReference<Throwable> observed = new AtomicReference<>();
        send.getCompletion().whenComplete((value, failure) -> observed.set(failure));
        actual.completeExceptionally(original);
        assertThat(observed).hasValue(original);
        send.getCompletion().whenComplete((value, failure) -> assertThat(failure).isSameAs(original));
    }

    @Test
    void cancellationIsAOneShotRequestAndDoesNotResolveTheResult() {
        final CompletableFuture<Void> actual = new CompletableFuture<>();
        final AtomicInteger requests = new AtomicInteger();
        final MailSend<Void> send = new MailSend<>(actual, requests::incrementAndGet);
        send.requestCancellation();
        send.requestCancellation();
        assertThat(requests).hasValue(1);
        assertThat(send.getCompletion()).isNotDone();
        actual.complete(null);
        send.requestCancellation();
        assertThat(requests).hasValue(1);
        final AtomicInteger lateRequests = new AtomicInteger();
        new MailSend<>(CompletableFuture.completedFuture(null), lateRequests::incrementAndGet).requestCancellation();
        assertThat(lateRequests).hasValue(0);
    }
}
