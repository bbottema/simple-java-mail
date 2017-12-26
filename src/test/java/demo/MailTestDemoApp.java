package demo;

import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.Email;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.email.EmailPopulatingBuilder;
import org.simplejavamail.mailer.Mailer;
import org.simplejavamail.mailer.MailerBuilder;
import org.simplejavamail.mailer.config.TransportStrategy;
import testutil.ConfigLoaderTestHelper;

import javax.mail.internet.MimeMessage;
import javax.mail.util.ByteArrayDataSource;
import java.nio.charset.Charset;

import static javax.xml.bind.DatatypeConverter.parseBase64Binary;

/**
 * Demonstration program for the Simple Java Mail framework. Just fill your gmail, password and press GO.
 */
@SuppressWarnings({ "WeakerAccess", "UnusedAssignment" })
public class MailTestDemoApp {

	private static final String YOUR_GMAIL_ADDRESS = "your_gmail_user@gmail.com";

	// if you have 2-factor login turned on, you need to generate a once-per app password
	// https://security.google.com/settings/security/apppasswords
	private static final String YOUR_GMAIL_PASSWORD = "your_gmail_password";
	
	private static final Mailer mailerSMTP = buildMailer("smtp.gmail.com", 25, YOUR_GMAIL_ADDRESS, YOUR_GMAIL_PASSWORD, TransportStrategy.SMTP);
	private static final Mailer mailerSSL = buildMailer("smtp.gmail.com", 465, YOUR_GMAIL_ADDRESS, YOUR_GMAIL_PASSWORD, TransportStrategy.SMTPS);
	private static final Mailer mailerTLS = buildMailer("smtp.gmail.com", 587, YOUR_GMAIL_ADDRESS, YOUR_GMAIL_PASSWORD, TransportStrategy.SMTP_TLS);
	
	/**
	 * If you just want to see what email is being sent, just set this to true. It won't actually connect to an SMTP server then.
	 */
	private static final boolean LOGGING_MODE = false;
	
	private static final Mailer buildMailer(String host, int port, String gMailAddress, String gMailPassword, TransportStrategy strategy) {
		return MailerBuilder
				.withSMTPServer(host, port, gMailAddress, gMailPassword)
				.withTransportStrategy(strategy)
				.withTransportModeLoggingOnly(LOGGING_MODE)
				.buildMailer();
	}

	public static void main(final String[] args)
			throws Exception {
		// make Simple Java Mail ignore the properties file completely: that's there for the junit tests, not this demo.
		ConfigLoaderTestHelper.clearConfigProperties();

		final EmailPopulatingBuilder emailPopulatingBuilderNormal = EmailBuilder.startingBlank();
		emailPopulatingBuilderNormal.from("lollypop", "lol.pop@somemail.com");
		// don't forget to add your own address here ->
		emailPopulatingBuilderNormal.to("C.Cane", YOUR_GMAIL_ADDRESS);
		emailPopulatingBuilderNormal.withPlainText("We should meet up!");
		emailPopulatingBuilderNormal.withHTMLText("<b>We should meet up!</b><img src='cid:thumbsup'>");
		emailPopulatingBuilderNormal.withSubject("hey");

		// add two text files in different ways and a black thumbs up embedded image ->
		emailPopulatingBuilderNormal.withAttachment("dresscode.txt", new ByteArrayDataSource("Black Tie Optional", "text/plain"));
		emailPopulatingBuilderNormal.withAttachment("location.txt", "On the moon!".getBytes(Charset.defaultCharset()), "text/plain");
		String base64String = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAABeElEQVRYw2NgoAAYGxu3GxkZ7TY1NZVloDcAWq4MxH+B+D8Qv3FwcOCgtwM6oJaDMTAUXOhmuYqKCjvQ0pdoDrCnmwNMTEwakC0H4u8GBgYC9Ap6DSD+iewAoIPm0ctyLqBlp9F8/x+YE4zpYT8T0LL16JYD8U26+B7oyz4sloPwenpYno3DchCeROsUbwa05A8eB3wB4kqgIxOAuArIng7EW4H4EhC/B+JXQLwDaI4ryZaDSjeg5mt4LCcFXyIn1fdSyXJQVt1OtMWGhoai0OD8T0W8GohZifE1PxD/o7LlsPLiFNAKRrwOABWptLAcqc6QGDAHQEOAYaAc8BNotsJAOgAUAosG1AFA/AtUoY3YEFhKMAvS2AE7iC1+WaG1H6gY3gzE36hUFJ8mqzbU1dUVBBqQBzTgIDQRkWo5qCZdpaenJ0Zx1aytrc0DDB0foIG1oAYKqC0IZK8D4n1AfA6IzwPxXpCFoGoZVEUDaRGGUTAKRgEeAAA2eGJC+ETCiAAAAABJRU5ErkJggg==";
		emailPopulatingBuilderNormal.withEmbeddedImage("thumbsup", parseBase64Binary(base64String), "image/png");

		// let's try producing and then consuming a MimeMessage ->
		Email emailNormal = emailPopulatingBuilderNormal.buildEmail();
		final MimeMessage mimeMessage = EmailConverter.emailToMimeMessage(emailNormal);
		final Email emailFromMimeMessage = EmailConverter.mimeMessageToEmail(mimeMessage);

		// note: the following statements will produce 6 new emails!
		sendMail(emailNormal);
		sendMail(emailFromMimeMessage); // should produce the exact same result as emailPopulatingBuilderNormal!
	}

	private static void sendMail(final Email email) {
		mailerSMTP.sendMail(email);
		mailerTLS.sendMail(email);
		mailerSSL.sendMail(email);
	}
}