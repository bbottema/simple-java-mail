package demo;

import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.Mailer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.simplejavamail.recipient.RecipientBuilder.to;

/**
 * Shows why send completion and executor-backed observer aggregation are separate waits.
 * Configure DemoAppBase before running; enable its LOGGING_MODE for a no-SMTP demonstration.
 * The cancellation request races with sending deliberately: a completed or already accepted email cannot be recalled.
 */
public final class MailSendExecutionDemoApp extends DemoAppBase {
    private static final int EMAIL_COUNT = 5;

    public static void main(final String[] args) throws Exception {
        final ExecutorService observations = Executors.newFixedThreadPool(2);
        final CountDownLatch observed = new CountDownLatch(EMAIL_COUNT);
        final AtomicInteger successes = new AtomicInteger();
        try (Mailer mailer = mailerTLSBuilder.withMailSendObserver(outcome -> {
            try {
                report(outcome, successes);
            } finally {
                observed.countDown();
            }
        }, observations).buildMailer()) {
            final List<CompletableFuture<Void>> completions = new ArrayList<>();
            for (int number = 1; number <= EMAIL_COUNT; number++) {
                final MailSend<Void> send = mailer.sendMailAsync(email(number));
                if (number == EMAIL_COUNT) {
                    send.requestCancellation();
                }
                completions.add(send.getCompletion().handle((unused, failure) -> null));
            }
            CompletableFuture.allOf(completions.toArray(new CompletableFuture<?>[0])).join();
            System.out.println("All sends finished; observer callbacks may still be running.");
            if (!observed.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Not all observer callbacks finished");
            }
            System.out.printf("Observed %d sends: %d successful, %d failed%n", EMAIL_COUNT, successes.get(), EMAIL_COUNT - successes.get());
        } finally {
            observations.shutdown();
            if (!observations.awaitTermination(30, TimeUnit.SECONDS)) {
                observations.shutdownNow();
            }
        }
    }

    private static Email email(final int number) {
        return SimpleJavaMail.fromDefaults().emailBuilder().startingBlank()
                .from("simplejavamail@demo.app").withRecipients(to(null, YOUR_GMAIL_ADDRESS))
                .fixingMessageId("execution-demo-" + number + "@simplejavamail.org")
                .withSubject("Send execution demo " + number).withPlainText("A notification that may become obsolete.").buildEmail();
    }

    private static void report(final MailSendOutcome outcome, final AtomicInteger successes) {
        if (outcome.isSuccessful()) {
            successes.incrementAndGet();
        }
        System.out.printf("id=%s successful=%s submission=%s failure=%s thread=%s%n", outcome.getEffectiveMessageId(), outcome.isSuccessful(),
                outcome.getSubmissionReceipt().map(receipt -> receipt.getStatus().name()).orElse("none"),
                outcome.getFailure().map(failure -> failure.getClass().getSimpleName()).orElse("none"), Thread.currentThread().getName());
    }
}
