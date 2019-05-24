package demo;

import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.EmailBuilder;
import testutil.CalendarHelper;

import javax.mail.internet.MimeMessage;
import javax.mail.util.ByteArrayDataSource;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Demonstration program for the Simple Java Mail framework. Just fill your gmail, password and press GO.
 */
public class FullEmailDemoApp extends DemoAppBase {
	
	public static void main(final String[] args) throws IOException {
		testMixedRelatedAlternativeIncludingCalendarAndMessageParsingUsingVariousMailers();
	}
	
	private static void testMixedRelatedAlternativeIncludingCalendarAndMessageParsingUsingVariousMailers() throws IOException {
		final EmailPopulatingBuilder emailPopulatingBuilderNormal = EmailBuilder.startingBlank();
		emailPopulatingBuilderNormal.from("Simple Java Mail demo", "simplejavamail@demo.app");
		// don't forget to add your own address here ->
		emailPopulatingBuilderNormal.to("C.Cane", YOUR_GMAIL_ADDRESS);
		emailPopulatingBuilderNormal.withPlainText("Plain text content (ignored in favor of HTML by modern browsers)");
		emailPopulatingBuilderNormal.withHTMLText("<p>This is an email with \"mixed\", \"related\" and \"alternative\" content: it contains a plain " +
				"text part (ignored in favor of HTML by modern clients), an HTML content part (this HTML text) which references a related " +
				"content part (the embedded image) and a iCalendar content part. In addition this email contains a separate attachment as " +
				"well.</p><img src='cid:thumbsup'>" +
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
				"</ul></p>"); // makes it alternative
		
		emailPopulatingBuilderNormal.withSubject("Email with mixed + related + alternative content (including iCalendar event)");
		
		// add two text files in different ways and a black thumbs up embedded image ->
		emailPopulatingBuilderNormal.withAttachment("dresscode.txt", new ByteArrayDataSource("Black Tie Optional", "text/plain"));
		emailPopulatingBuilderNormal.withAttachment("location.txt", "On the moon!".getBytes(Charset.defaultCharset()), "text/plain");
		emailPopulatingBuilderNormal.withEmbeddedImage("thumbsup", produceThumbsUpImage(), "image/png");
		emailPopulatingBuilderNormal.withCalendarText(CalendarMethod.REQUEST, CalendarHelper.createCalendarEvent());
		
		// let's try producing and then consuming a MimeMessage ->
		Email emailNormal = emailPopulatingBuilderNormal.buildEmail();
		final MimeMessage mimeMessage = EmailConverter.emailToMimeMessage(emailNormal);
		final Email emailFromMimeMessage = EmailConverter.mimeMessageToEmail(mimeMessage);

		mailerSMTP.sendMail(emailNormal);
		mailerTLS.sendMail(emailNormal);
		mailerSSL.sendMail(emailNormal);
		mailerTLS.sendMail(emailFromMimeMessage); // should produce the exact same result as emailPopulatingBuilderNormal!
	}
}