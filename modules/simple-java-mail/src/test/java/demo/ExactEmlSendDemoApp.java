package demo;

import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;

import java.nio.file.Path;

/**
 * Demonstrates submitting an already finalized EML file without rebuilding its MIME content.
 * <p>
 * Run with {@code <eml-file> <envelope-recipient> [envelope-sender]}. Configure SMTP credentials in {@link DemoAppBase} first, or temporarily enable
 * its logging-only mode for a no-SMTP demonstration. The input must already use canonical CRLF line endings and be safe for direct submission: exact
 * mode deliberately retains every header, including {@code Bcc} and {@code Resent-Bcc}.
 */
public final class ExactEmlSendDemoApp extends DemoAppBase {

	private static final int REQUIRED_ARGUMENT_COUNT = 2;

	private ExactEmlSendDemoApp() {
	}

	public static void main(final String[] arguments) throws Exception {
		requireEmlFileAndEnvelopeRecipient(arguments);

		final ExactEmailBuilder exactEmailBuilder = SimpleJavaMail.fromDefaults().emailBuilder()
				.startingFromExactEml(Path.of(arguments[0]))
				.withEnvelopeRecipients(arguments[1]);
		if (arguments.length > REQUIRED_ARGUMENT_COUNT) {
			exactEmailBuilder.withEnvelopeSender(arguments[2]);
		}
		final Email email = exactEmailBuilder.buildEmail();

		try (Mailer mailer = mailerTLSBuilder.buildMailer()) {
			final MailSubmissionReceipt receipt = mailer.sendMailAndGetReceiptSync(email);
			System.out.printf("Submitted exact EML: status=%s Message-ID=%s accepted=%s%n",
					receipt.getStatus(), receipt.getEmailId(), receipt.getAcceptedRecipients());
		}
	}

	private static void requireEmlFileAndEnvelopeRecipient(final String[] arguments) {
		if (arguments.length < REQUIRED_ARGUMENT_COUNT || arguments.length > REQUIRED_ARGUMENT_COUNT + 1) {
			throw new IllegalArgumentException(
					"Usage: ExactEmlSendDemoApp <eml-file> <envelope-recipient> [envelope-sender]");
		}
	}
}
