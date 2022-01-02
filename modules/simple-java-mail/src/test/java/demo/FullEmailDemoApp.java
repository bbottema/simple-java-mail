package demo;

import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.EmailBuilder;
import testutil.CalendarHelper;

import java.io.IOException;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.Charset.defaultCharset;

/**
 * Demonstration program for the Simple Java Mail framework. Just fill your gmail, password and press GO.
 */
public class FullEmailDemoApp extends DemoAppBase {

	public static void main(final String[] args) throws IOException {
		testMixedRelatedAlternativeIncludingCalendarAndMessageParsingUsingVariousMailers();
	}
	
	private static void testMixedRelatedAlternativeIncludingCalendarAndMessageParsingUsingVariousMailers() throws IOException {
		final String resourcesPathOnDisk = determineResourceFolder("simple-java-mail") + "/test/resources";

		final EmailPopulatingBuilder emailPopulatingBuilderNormal = EmailBuilder.startingBlank()
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true);

		emailPopulatingBuilderNormal.from("Simple Java Mail demo", "simplejavamail@demo.app");
		// don't forget to add your own address here ->
		emailPopulatingBuilderNormal.to("C.Cane", YOUR_GMAIL_ADDRESS);
		emailPopulatingBuilderNormal.withPlainText("Plain text content (ignored in favor of HTML by modern browsers)");
		emailPopulatingBuilderNormal.withHTMLText("<p>This is an email with \"mixed\", \"related\" and \"alternative\" content: it contains a plain " +
				"text part (ignored in favor of HTML by modern clients), an HTML content part (this HTML text) which references a related " +
				"content part (the embedded image) and a iCalendar content part. In addition this email contains a separate attachment as " +
				"well.</p>" +
				"<ol>" +
				"   <li>Image embedded with fixed cid and hardcoded datasource: <img width=25 src='cid:thumbsup'></li>" +
				"   <li>Image embedded with generated cid datasource resolved from classpath:<img src='/test-dynamicembedded-image/excellent.png' style='width:25px'></li>" +
				"   <li>Image embedded with generated cid datasource resolved from disk:<img src='" + resourcesPathOnDisk + "/test-dynamicembedded-image/excellent.png' style='width:25px'></li>" +
				"   <li>Image embedded with generated cid datasource resolved from URL!:<img src='https://www.simplejavamail.org/assets/github-ribbon-topright@2x.png' style='width:32px' title='Fork me on GitHub'></li>" +
				"</ol>" +
				"<p><b>Formal structure:</b><br>" +
				"<ul>" +
				"   <li>mixed (root)<ul>" +
				"   	<li>related<ul>" +
				"   		<li>alternative<ul>" +
				"   			<li>plain text</li>" +
				"				<li>HTML text</li>" +
				"			</ul></li>" +
				"			<li>embeddable images (1x fixed: cid:thumbsup and 3x dynamically resolved)</li>" +
				"		</ul></li>" +
				"		<li>attachment</li>" +
				"	</ul></li>" +
				"</ul></p>"); // makes it alternative
		
		emailPopulatingBuilderNormal.withSubject("Email with mixed + related + alternative content (including iCalendar event)");
		
		// add two text files in different ways and a black thumbs up embedded image ->
		emailPopulatingBuilderNormal.withAttachment("dresscode.txt", new ByteArrayDataSource("Black Tie Optional", "text/plain"));
		emailPopulatingBuilderNormal.withAttachment("location.txt", "On the moon!".getBytes(defaultCharset()), "text/plain");
		emailPopulatingBuilderNormal.withAttachment("special_łąąśćńółęĄŻŹĆŃÓŁĘ.txt", "doorcode: Ken sent me".getBytes(defaultCharset()), "text/plain");
		emailPopulatingBuilderNormal.withEmbeddedImage("thumbsup", produceThumbsUpImage(), "image/png");
		emailPopulatingBuilderNormal.withCalendarText(CalendarMethod.REQUEST, CalendarHelper.createCalendarEvent());
		
		// let's try producing and then consuming a MimeMessage ->
		Email emailNormal = emailPopulatingBuilderNormal.buildEmail();
		final MimeMessage mimeMessage = EmailConverter.emailToMimeMessage(emailNormal);
		final Email emailFromMimeMessage = EmailConverter.mimeMessageToEmail(mimeMessage);

		mailerSMTPBuilder.buildMailer().sendMail(emailNormal);
		mailerTLSBuilder.buildMailer().sendMail(emailNormal);
		mailerSSLBuilder.buildMailer().sendMail(emailNormal);
		mailerTLSBuilder.buildMailer().sendMail(emailFromMimeMessage); // should produce the exact same result as emailPopulatingBuilderNormal!
	}
}