package demo;

import org.simplejavamail.mailer.Mailer;
import org.simplejavamail.mailer.MailerBuilder;
import org.simplejavamail.mailer.config.TransportStrategy;
import testutil.ConfigLoaderTestHelper;

import static javax.xml.bind.DatatypeConverter.parseBase64Binary;

public class DemoAppBase {
	
	static final String YOUR_GMAIL_ADDRESS = "yourname@gmail.com";
	
	// if you have 2-factor login turned on, you need to generate a once-per app password
	// https://security.google.com/settings/security/apppasswords
	private static final String YOUR_GMAIL_PASSWORD = "<your password>";
	
	/**
	 * If you just want to see what email is being sent, just set this to true. It won't actually connect to an SMTP server then.
	 */
	private static final boolean LOGGING_MODE = false;
	
	static String SOURCE_FOLDER = System.getProperty("user.dir").matches(".*[\\\\/]maven-master-project.*$")
			? "modules\\core-module\\src"
			: "src";
	
	static {
		// make Simple Java Mail ignore the properties file completely: that's there for the junit tests, not this demo.
		ConfigLoaderTestHelper.clearConfigProperties();
		
		//noinspection ConstantConditions
		if (YOUR_GMAIL_ADDRESS.equals("your_gmail_user@gmail.com")) {
			throw new RuntimeException("For these demo's to work, please provide your Gnail credentials in DemoAppBase.java first (or change the SMTP config)");
		}
	}
	
	static final Mailer mailerSMTP = buildMailer("smtp.gmail.com", 25, YOUR_GMAIL_ADDRESS, YOUR_GMAIL_PASSWORD, TransportStrategy.SMTP);
	static final Mailer mailerTLS = buildMailer("smtp.gmail.com", 587, YOUR_GMAIL_ADDRESS, YOUR_GMAIL_PASSWORD, TransportStrategy.SMTP_TLS);
	static final Mailer mailerSSL = buildMailer("smtp.gmail.com", 465, YOUR_GMAIL_ADDRESS, YOUR_GMAIL_PASSWORD, TransportStrategy.SMTPS);
	
	@SuppressWarnings("SameParameterValue")
	private static final Mailer buildMailer(String host, int port, String gMailAddress, String gMailPassword, TransportStrategy strategy) {
		return MailerBuilder
				.withSMTPServer(host, port, gMailAddress, gMailPassword)
				.withTransportStrategy(strategy)
				.withTransportModeLoggingOnly(LOGGING_MODE)
				.clearProxy()
				.buildMailer();
	}
	
	static byte[] produceThumbsUpImage() {
		String base64String = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAABeElEQVRYw2NgoAAYGxu3GxkZ7TY1NZVloDcAWq4MxH+B+D8Qv3FwcOCgtwM6oJaDMTAUXOhmuYqKCjvQ0pdoDrCnmwNMTEwakC0H4u8GBgYC9Ap6DSD+iewAoIPm0ctyLqBlp9F8/x+YE4zpYT8T0LL16JYD8U26+B7oyz4sloPwenpYno3DchCeROsUbwa05A8eB3wB4kqgIxOAuArIng7EW4H4EhC/B+JXQLwDaI4ryZaDSjeg5mt4LCcFXyIn1fdSyXJQVt1OtMWGhoai0OD8T0W8GohZifE1PxD/o7LlsPLiFNAKRrwOABWptLAcqc6QGDAHQEOAYaAc8BNotsJAOgAUAosG1AFA/AtUoY3YEFhKMAvS2AE7iC1+WaG1H6gY3gzE36hUFJ8mqzbU1dUVBBqQBzTgIDQRkWo5qCZdpaenJ0Zx1aytrc0DDB0foIG1oAYKqC0IZK8D4n1AfA6IzwPxXpCFoGoZVEUDaRGGUTAKRgEeAAA2eGJC+ETCiAAAAABJRU5ErkJggg==";
		return parseBase64Binary(base64String);
	}
}