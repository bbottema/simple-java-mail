package demo;

import org.simplejavamail.api.mailer.AsyncResponse;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.email.EmailBuilder;

import javax.mail.util.ByteArrayDataSource;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstration program for the Simple Java Mail framework. Just fill your gmail, password and press GO.
 */
@SuppressWarnings({ "WeakerAccess" })
public class EmailTypesDemoApp extends DemoAppBase {

	public static void main(final String[] args) throws IOException {
		Mailer mailerTLS = DemoAppBase.mailerTLSBuilder.buildMailer();

		testSimplePlainText(mailerTLS);
		testSimpleHTMLText(mailerTLS);
		testMixed(mailerTLS);
		testRelated(mailerTLS);
		testAlternative(mailerTLS);
		testMixedRelated(mailerTLS);
		testMixedAlternative(mailerTLS);
		testRelatedAlternative(mailerTLS);
		testMixedRelatedAlternative(mailerTLS);
	}

	private static void testSimplePlainText(Mailer mailerTLS) {
		final AsyncResponse result = mailerTLS.sendMail(EmailBuilder.startingBlank()
				.to(YOUR_GMAIL_ADDRESS)
				.from("Simple Java Mail demo", "simplejavamail@demo.app")
				.withSubject("Demo email - simple (using plain text)")
				.withPlainText("This is a simple email, the source contains no \"mixed\", \"related\" or \"alternative\" content.\n" +
						"\n" +
						"The root content is plain simple text.\n" +
						"\n" +
						"Formal structure:\n" +
						"- plain text (root)")
				.clearHTMLText()
				.buildEmail());
		
		assertThat(result).isNull();
	}

	private static void testSimpleHTMLText(Mailer mailerTLS) {
		mailerTLS.sendMail(EmailBuilder.startingBlank()
				.to(YOUR_GMAIL_ADDRESS)
				.from("Simple Java Mail demo", "simplejavamail@demo.app")
				.withSubject("Demo email - simple (using HTML text)")
				.withHTMLText("<p>This is a simple email, the source contains no \"mixed\", \"related\" or \"alternative\" content.</p>" +
						"<p><i>The root content is <b>HTML</b> text.</i></p>" +
						"<p><b>Formal structure:</b><br>" +
						"<ul>" +
						"   <li>HTML text (root)</li>" +
						"</ul></p>")
				.clearPlainText()
				.buildEmail());
	}

	private static void testMixed(Mailer mailerTLS) throws IOException {
		mailerTLS.sendMail(EmailBuilder.startingBlank()
				.to(YOUR_GMAIL_ADDRESS)
				.from("Simple Java Mail demo", "simplejavamail@demo.app")
				.withSubject("Demo email - mixed")
				.withPlainText("This is an email with \"mixed\" content: it contains a content part (this plain text) and an attachment\n" +
						"\n" +
						"Formal structure:\n" +
						"-mixed (root)\n" +
						"   - plain text\n" +
						"   - attachment")
				.withAttachment("dresscode.txt", new ByteArrayDataSource("Black Tie Optional", "text/plain")) // makes it mixed
				.buildEmail());
	}

	private static void testRelated(Mailer mailerTLS) {
		// makes it related
		mailerTLS.sendMail(EmailBuilder.startingBlank()
				.to(YOUR_GMAIL_ADDRESS)
				.from("Simple Java Mail demo", "simplejavamail@demo.app")
				.withSubject("Demo email - related")
				.withHTMLText("<p>This is an email with \"related\" content: it contains a content part (this HTML text) which references a " +
						"related content part (the embedded image).</p><img src='cid:thumbsup'>" +
						"<p><b>Formal structure:</b><br>" +
						"<ul>" +
						"   <li>related (root)<ul>" +
						"		<li>HTML text</li>" +
						"		<li>embeddable image (cid:thumbsup) </li>" +
						"	</ul></li>" +
						"</ul></p>")
				.withEmbeddedImage("thumbsup", produceThumbsUpImage(), "image/png") // makes it related
				.clearPlainText()
				.buildEmail());
	}

	private static void testAlternative(Mailer mailerTLS) {
		mailerTLS.sendMail(EmailBuilder.startingBlank()
				.to(YOUR_GMAIL_ADDRESS)
				.from("Simple Java Mail demo", "simplejavamail@demo.app")
				.withSubject("Demo email - alternative")
				.withPlainText("plain text body here which should be ignored by any modern mail client")
				.withHTMLText("<p>This is an email with \"alternative\" content: it contains a plain text content part (ignored in favor " +
						"of HTML by modern mail clients) and a HTML content part (this HTML text)." +
						"<p><b>Formal structure:</b><br>" +
						"<ul>" +
						"   <li>alternative (root)<ul>" +
						"		<li>Plain text</li>" +
						"		<li>HTML text</li>" +
						"	</ul></li>" +
						"</ul></p>") // makes it alternative
				.buildEmail());
	}

	private static void testMixedRelated(Mailer mailerTLS) throws IOException {
		mailerTLS.sendMail(EmailBuilder.startingBlank()
				.to(YOUR_GMAIL_ADDRESS)
				.from("Simple Java Mail demo", "simplejavamail@demo.app")
				.withSubject("Demo email - mixed + related")
				.withHTMLText("<p>This is an email with \"mixed\" and \"related\" content: it contains a content part (this HTML text) which references a " +
						"related content part (the embedded image) and contains an attachment as well.</p><img src='cid:thumbsup'>" +
						"<p><b>Formal structure:</b><br>" +
						"<ul>" +
						"   <li>mixed (root)<ul>" +
						"		<li>related<ul>" +
						"			<li>HTML text</li>" +
						"			<li>embeddable image (cid:thumbsup) </li>" +
						"		</ul></li>" +
						"		<li>attachment</li>" +
						"	</ul></li>" +
						"</ul></p>")
				.withAttachment("dresscode.txt", new ByteArrayDataSource("Black Tie Optional", "text/plain")) // makes it mixed
				.withEmbeddedImage("thumbsup", produceThumbsUpImage(), "image/png") // makes it related
				.clearPlainText()
				.buildEmail());
	}

	private static void testMixedAlternative(Mailer mailerTLS) throws IOException {
		mailerTLS.sendMail(EmailBuilder.startingBlank()
				.to(YOUR_GMAIL_ADDRESS)
				.from("Simple Java Mail demo", "simplejavamail@demo.app")
				.withSubject("Demo email - mixed + alternative")
				.withPlainText("plain text body here which should be ignored by any modern mail client")
				.withAttachment("dresscode.txt", new ByteArrayDataSource("Black Tie Optional", "text/plain")) // makes it mixed
				.withHTMLText("<p>This is an email with \"mixed\" and \"alternative\" content: it contains a plain text content part " +
						"(ignored in favor of HTML by modern mail clients) and a HTML content part (this HTML text) and contains an " +
						"attachment as well.</p>" +
						"<p><b>Formal structure:</b><br>" +
						"<ul>" +
						"   <li>mixed (root)<ul>" +
						"		<li>alternative<ul>" +
						"			<li>Plain text</li>" +
						"			<li>HTML text</li>" +
						"		</ul></li>" +
						"		<li>attachment</li>" +
						"	</ul></li>" +
						"</ul></p>") // makes it alternative
				.buildEmail());
	}

	private static void testRelatedAlternative(Mailer mailerTLS) {
		mailerTLS.sendMail(EmailBuilder.startingBlank()
				.to(YOUR_GMAIL_ADDRESS)
				.from("Simple Java Mail demo", "simplejavamail@demo.app")
				.withSubject("Demo email - related + alternative")
				.withPlainText("plain text body here which should be ignored by any modern mail client")
				.withEmbeddedImage("thumbsup", produceThumbsUpImage(), "image/png") // makes it related
				.withHTMLText("<p>This is an email with \"related\" and \"alternative\" content: it contains a plain text part (ignored in " +
						"favor of HTML by modern clients) and an HTML content part (this HTML text) which references a " +
						"related content part (the embedded image).</p><img src='cid:thumbsup'>" +
						"<p><b>Formal structure:</b><br>" +
						"<ul>" +
						"   <li>related (root)<ul>" +
						"   	<li>alternative<ul>" +
						"			<li>Plain text</li>" +
						"			<li>HTML text</li>" +
						"		</ul></li>" +
						"		<li>embeddable image (cid:thumbsup)</li>" +
						"	</ul></li>" +
						"</ul></p>") // makes it alternative
				.buildEmail());
	}

	private static void testMixedRelatedAlternative(Mailer mailerTLS) throws IOException {
		mailerTLS.sendMail(EmailBuilder.startingBlank()
				.to(YOUR_GMAIL_ADDRESS)
				.from("Simple Java Mail demo", "simplejavamail@demo.app")
				.withSubject("Demo email - mixed + related + alternative")
				.withPlainText("plain text body here which should be ignored by any modern mail client")
				.withAttachment("dresscode.txt", new ByteArrayDataSource("Black Tie Optional", "text/plain")) // makes it mixed
				.withEmbeddedImage("thumbsup", produceThumbsUpImage(), "image/png") // makes it related
				.withHTMLText("<p>This is an email with \"mixed\", \"related\" and \"alternative\" content: it contains a plain text part " +
						"(ignored in favor of HTML by modern clients) and an HTML content part (this HTML text) which references a related " +
						"content part (the embedded image). In addition this email contains a separate attachment as well.</p>" +
						"<img src='cid:thumbsup'>" +
						"<p><b>Formal structure:</b><br>" +
						"<ul>" +
						"   <li>mixed (root)<ul>" +
						"   	<li>related<ul>" +
						"   		<li>alternative<ul>" +
						"   			<li>plain text</li>" +
						"				<li>HTML text</li>" +
						"			</ul></li>" +
						"			<li>embeddable image (cid:thumbsup) </li>" +
						"		</ul></li>" +
						"		<li>attachment</li>" +
						"	</ul></li>" +
						"</ul></p>") // makes it alternative
				.buildEmail());
	}
}