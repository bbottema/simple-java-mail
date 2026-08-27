package demo;

import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.simplejavamail.recipient.RecipientBuilder.to;

/**
 * Demonstrates streaming terminal progress from asynchronous email sends.
 * <p>
 * Configure the SMTP credentials in {@link DemoAppBase} before running this class. For a demonstration that prints the emails without contacting an
 * SMTP server, temporarily enable the {@code LOGGING_MODE} flag in {@code DemoAppBase}.
 * </p>
 */
public class MailSendObserverDemoApp extends DemoAppBase {

	private static final int EMAIL_COUNT = 5;
	private static final Object OUTPUT_LOCK = new Object();

	public static void main(final String[] args) throws Exception {
		final AtomicInteger completedCount = new AtomicInteger();
		final AtomicInteger successfulCount = new AtomicInteger();
		final AtomicInteger failedCount = new AtomicInteger();

		final Mailer mailer = mailerTLSBuilder
				.withMailSendObserver(outcome -> reportProgress(
						outcome,
						completedCount,
						successfulCount,
						failedCount))
				.buildMailer();
		try {
			final List<CompletableFuture<Void>> completions = new ArrayList<>();
			for (int emailNumber = 1; emailNumber <= EMAIL_COUNT; emailNumber++) {
				final Email email = createEmail(emailNumber);
				completions.add(mailer.sendMailAsync(email)
						.handle((unused, failure) -> null));
			}

			CompletableFuture.allOf(completions.toArray(new CompletableFuture<?>[0])).join();
		} finally {
			mailer.close();
		}

		System.out.printf("Finished %d/%d: %d successful, %d failed%n",
				completedCount.get(), EMAIL_COUNT, successfulCount.get(), failedCount.get());
	}

	private static Email createEmail(final int emailNumber) {
		return SimpleJavaMail.fromDefaults().emailBuilder().startingBlank()
				.from("Simple Java Mail observer demo", "simplejavamail@demo.app")
				.withRecipients(to(null, YOUR_GMAIL_ADDRESS))
				.withSubject("Mail-send observer demo " + emailNumber)
				.withPlainText("This email demonstrates terminal mail-send progress.")
				.fixingMessageId("observer-demo-" + emailNumber + "@simplejavamail.org")
				.buildEmail();
	}

	private static void reportProgress(final MailSendOutcome outcome,
			final AtomicInteger completedCount,
			final AtomicInteger successfulCount,
			final AtomicInteger failedCount) {
		synchronized (OUTPUT_LOCK) {
			final int completed = completedCount.incrementAndGet();
			if (outcome.isSuccessful()) {
				successfulCount.incrementAndGet();
			} else {
				failedCount.incrementAndGet();
			}

			System.out.printf("[%d/%d] %-9s id=%s queue=%s send=%s total=%s thread=%s%n",
					completed,
					EMAIL_COUNT,
					status(outcome),
					messageId(outcome),
					duration(outcome.getReadyAt(), outcome.getStartedAt()),
					duration(outcome.getStartedAt(), Optional.of(outcome.getCompletedAt())),
					duration(Optional.of(outcome.getRequestedAt()), Optional.of(outcome.getCompletedAt())),
					Thread.currentThread().getName());
		}
	}

	private static String status(final MailSendOutcome outcome) {
		if (!outcome.isSuccessful()) {
			return "FAILED";
		}
		return outcome.getSubmissionReceipt()
				.map(MailSubmissionReceipt::getStatus)
				.map(Enum::name)
				.orElse("SUCCESS");
	}

	private static String messageId(final MailSendOutcome outcome) {
		final String messageId = outcome.getEffectiveMessageId() != null
				? outcome.getEffectiveMessageId()
				: outcome.getInitialMessageId();
		if (messageId == null) {
			return "<missing>";
		}
		return messageId.startsWith("<") ? messageId : '<' + messageId + '>';
	}

	private static String duration(final Optional<Instant> start, final Optional<Instant> end) {
		if (!start.isPresent() || !end.isPresent()) {
			return "-";
		}
		return Duration.between(start.get(), end.get()).toMillis() + "ms";
	}
}
