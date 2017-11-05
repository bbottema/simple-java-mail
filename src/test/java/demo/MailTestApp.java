package demo;

import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.Email;
import org.simplejavamail.mailer.Mailer;
import org.simplejavamail.mailer.config.ServerConfig;
import org.simplejavamail.mailer.config.TransportStrategy;
import testutil.ConfigLoaderTestHelper;

import javax.mail.internet.MimeMessage;
import javax.mail.util.ByteArrayDataSource;
import java.nio.charset.Charset;

import static javax.xml.bind.DatatypeConverter.parseBase64Binary;

/**
 * Demonstration program for the Simple Java Mail framework. Just fill your gmail, password and press GO.
 *
 * @author Benny Bottema
 */
@SuppressWarnings({ "WeakerAccess", "UnusedAssignment" })
public class MailTestApp {

	private static final String YOUR_GMAIL_ADDRESS = "your_gmail_user@gmail.com";

	// if you have 2-factor login turned on, you need to generate a once-per app password
	// https://security.google.com/settings/security/apppasswords
	private static final String YOUR_GMAIL_PASSWORD = "your_gmail_password";

	private static final ServerConfig serverConfigSMTP = new ServerConfig("smtp.gmail.com", 25, YOUR_GMAIL_ADDRESS, YOUR_GMAIL_PASSWORD);
	private static final ServerConfig serverConfigTLS = new ServerConfig("smtp.gmail.com", 587, YOUR_GMAIL_ADDRESS, YOUR_GMAIL_PASSWORD);
	private static final ServerConfig serverConfigSSL = new ServerConfig("smtp.gmail.com", 465, YOUR_GMAIL_ADDRESS, YOUR_GMAIL_PASSWORD);

	/**
	 * If you just want to see what email is being sent, just set this to true. It won't actually connect to an SMTP server then.
	 */
	private static final boolean LOGGING_MODE = false;

	public static void main(final String[] args)
			throws Exception {
		// make Simple Java Mail ignore the properties file completely: that's there for the junit tests, not this demo.
		ConfigLoaderTestHelper.clearConfigProperties();

		final Email emailNormal = new Email();
		emailNormal.setFromAddress("lollypop", "lol.pop@somemail.com");
		// don't forget to add your own address here ->
		emailNormal.addNamedToRecipients("C.Cane", YOUR_GMAIL_ADDRESS);
		emailNormal.setText("We should meet up!");
		emailNormal.setTextHTML("<b>We should meet up!</b><img src='cid:thumbsup'>");
		emailNormal.setSubject("hey");

		// add two text files in different ways and a black thumbs up embedded image ->
		emailNormal.addAttachment("dresscode.txt", new ByteArrayDataSource("Black Tie Optional", "text/plain"));
		emailNormal.addAttachment("location.txt", "On the moon!".getBytes(Charset.defaultCharset()), "text/plain");
		String base64String = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAABeElEQVRYw2NgoAAYGxu3GxkZ7TY1NZVloDcAWq4MxH+B+D8Qv3FwcOCgtwM6oJaDMTAUXOhmuYqKCjvQ0pdoDrCnmwNMTEwakC0H4u8GBgYC9Ap6DSD+iewAoIPm0ctyLqBlp9F8/x+YE4zpYT8T0LL16JYD8U26+B7oyz4sloPwenpYno3DchCeROsUbwa05A8eB3wB4kqgIxOAuArIng7EW4H4EhC/B+JXQLwDaI4ryZaDSjeg5mt4LCcFXyIn1fdSyXJQVt1OtMWGhoai0OD8T0W8GohZifE1PxD/o7LlsPLiFNAKRrwOABWptLAcqc6QGDAHQEOAYaAc8BNotsJAOgAUAosG1AFA/AtUoY3YEFhKMAvS2AE7iC1+WaG1H6gY3gzE36hUFJ8mqzbU1dUVBBqQBzTgIDQRkWo5qCZdpaenJ0Zx1aytrc0DDB0foIG1oAYKqC0IZK8D4n1AfA6IzwPxXpCFoGoZVEUDaRGGUTAKRgEeAAA2eGJC+ETCiAAAAABJRU5ErkJggg==";
		emailNormal.addEmbeddedImage("thumbsup", parseBase64Binary(base64String), "image/png");

		// let's try producing and then consuming a MimeMessage ->
		final MimeMessage mimeMessage = EmailConverter.emailToMimeMessage(emailNormal);
		final Email emailFromMimeMessage = EmailConverter.mimeMessageToEmail(mimeMessage);

		// note: the following statements will produce 6 new emails!
		sendMail(emailNormal);
		sendMail(emailFromMimeMessage); // should produce the exact same result as emailNormal!
	}

	private static void sendMail(final Email email) {
		// ProxyConfig proxyconfig = new ProxyConfig("localhost", 1030);
		sendMail(serverConfigSMTP, TransportStrategy.SMTP_TLS, email);
		sendMail(serverConfigTLS, TransportStrategy.SMTP_TLS, email);
		sendMail(serverConfigSSL, TransportStrategy.SMTPS, email);
	}

	private static void sendMail(ServerConfig serverConfigSMTP, TransportStrategy smtpTls, Email email) {
		Mailer mailer = new Mailer(serverConfigSMTP, smtpTls);
		mailer.setTransportModeLoggingOnly(LOGGING_MODE);
		mailer.sendMail(email);
	}
}