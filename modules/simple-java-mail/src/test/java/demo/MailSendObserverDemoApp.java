package demo;

import org.simplejavamail.api.mailer.MailSendHandlers;
import jakarta.mail.Message.RecipientType;

import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendDiagnostics;
import org.simplejavamail.api.mailer.MailSendMeasurement;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendProgress;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates live progress and final results from asynchronous email sends.
 * <p>
 * Configure the SMTP credentials in {@link DemoAppBase} before running this class. For a demonstration that prints the emails without contacting an
 * SMTP server, temporarily enable the {@code LOGGING_MODE} flag in {@code DemoAppBase}.
 * <p>
 * Watch the progress output to see what each email is doing, then inspect its final result for acceptance facts and timings. Those timings describe
 * the send itself, not the time spent printing this output. Scheduling includes waiting for execution, and submission includes provider work rather
 * than just network traffic. Where a measurement does not apply, such as SMTP submission in logging-only mode, the demo prints n/a.
 * <p>
 * With asynchronous sends, preparation starts on the executor and uses the selected Mailer's templates. If connection failover tries several
 * destinations, preparation and acquisition timings add up the work across them. The connection history shows which destinations were actually
 * tried and which were skipped until their next recheck.
 * <p>
 * To see automatic retries, add {@code withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumAttempts(3)
 * .withRetryDelay(Duration.ofSeconds(30)).build())} to the builder below and use a controlled relay that temporarily refuses submissions.
 * The demo does not simulate failures or run during CI. You can follow retries through their shared observation ID until the single final result arrives.
 * <p>
 * A newly established managed connection can show greeting, TLS and authentication progress before the first submission starts.
 * For example, you might see this abbreviated output:
 * <pre>
 * id=ab12... WAITING_FOR_GREETING attempt=0 elapsed=PT0.1S
 * id=ab12... AUTHENTICATING attempt=0 elapsed=PT2S
 * id=ab12... SUBMITTING_RECIPIENTS attempt=1 elapsed=PT3S
 * id=ab12... WAITING_FOR_RESPONSE attempt=1 elapsed=PT3.1S bytes=2048/2048
 * Result for observation ab12...: ACCEPTED
 * </pre>
 * A later send that reuses the connection does not repeat its earlier setup stages. Providers without detailed reporting can stay at
 * ACQUIRING_CONNECTION or SUBMITTING instead. Treat the example as illustrative snapshots, not an expected event list: fast stages can be combined
 * before delivery, and a dashboard should leave room for additional stage values.
 *
 * @see MailSendRetryPolicy
 */
public class MailSendObserverDemoApp extends DemoAppBase {

	private static final int EMAIL_COUNT = 5;
	private static final Object OUTPUT_LOCK = new Object();
	private static final Set<UUID> FINISHED = new HashSet<>();

	public static void main(final String[] args) throws Exception {
		final AtomicInteger completedCount = new AtomicInteger();
		final AtomicInteger successfulCount = new AtomicInteger();
		final AtomicInteger failedCount = new AtomicInteger();

		final Mailer mailer = mailerTLSBuilder
				.withSendHandlers(MailSendHandlers.builder().withProgressObserver(MailSendObserverDemoApp::reportProgress).withResultObserver(outcome -> reportResult(
						outcome,
						completedCount,
						successfulCount,
						failedCount)).build())
				.buildMailer();
		try {
			final List<CompletableFuture<Void>> completions = new ArrayList<>();
			for (int emailNumber = 1; emailNumber <= EMAIL_COUNT; emailNumber++) {
				final Email email = createEmail(emailNumber);
				completions.add(mailer.async().sendMail(email).getCompletion()
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
				.withRecipients(SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress(YOUR_GMAIL_ADDRESS).withType(RecipientType.TO).build())
				.withSubject("Mail-send observer demo " + emailNumber)
				.withPlainText("This email demonstrates live mail-send progress and a separate final result.")
				.fixingMessageId("observer-demo-" + emailNumber + "@simplejavamail.org")
				.buildEmail();
	}

	private static void reportProgress(final MailSendProgress progress) {
		synchronized (OUTPUT_LOCK) {
			// The independent dispatcher may deliver an older snapshot after the result. Never move a finished dashboard row backwards.
			if (!FINISHED.contains(progress.getObservationId())) {
				System.out.println(progress);
			}
		}
	}

	private static void reportResult(final MailSendOutcome outcome,
			final AtomicInteger completedCount,
			final AtomicInteger successfulCount,
			final AtomicInteger failedCount) {
		synchronized (OUTPUT_LOCK) {
			FINISHED.add(outcome.getObservationId());
			System.out.printf("Result for observation %s%n", outcome.getObservationId());
			final int completed = completedCount.incrementAndGet();
			if (outcome.isSuccessful()) {
				successfulCount.incrementAndGet();
			} else {
				failedCount.incrementAndGet();
			}

			final MailSendDiagnostics report = outcome.getDiagnostics().orElseThrow();
			System.out.printf("[%d/%d] %-9s id=%s preparation=%s scheduling=%s retry=%s connection=%s mime=%s submission=%s cleanup=%s"
					+ " total=%dms thread=%s%n",
					completed,
					EMAIL_COUNT,
					status(outcome),
					messageId(outcome),
					duration(report.getPreparation()),
					duration(report.getScheduling()),
					duration(report.getRetryWait()),
					duration(report.getConnectionAcquisition()),
					duration(report.getMimePreparation()),
					duration(report.getSubmission()),
					duration(report.getCleanup()),
					report.getElapsed().toMillis(),
					Thread.currentThread().getName());
			printAttemptHistory(report);
		}
	}

	private static void printAttemptHistory(final MailSendDiagnostics report) {
		if (report.getConnectionHistory().size() > 1 || report.getOmittedConnectionSteps() > 0) {
			System.out.printf("  Connection route: %s (omitted: %d)%n", report.getConnectionHistory(), report.getOmittedConnectionSteps());
		}
		if (!report.getSubmissionHistory().isEmpty()) {
			System.out.printf("  Submission attempts: %s (omitted: %d)%n", report.getSubmissionHistory(), report.getOmittedSubmissionSteps());
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

	private static String duration(final MailSendMeasurement measurement) {
		final Duration elapsed = measurement.getElapsed();
		return (elapsed == null ? "n/a" : elapsed.toMillis() + "ms")
				+ (measurement.isFailureObservedHere() ? " (failed)" : "");
	}
}
