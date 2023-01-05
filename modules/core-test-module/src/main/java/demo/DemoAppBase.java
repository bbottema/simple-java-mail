package demo;

import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import testutil.ConfigLoaderTestHelper;
import testutil.ImplLoader;

public class DemoAppBase {

	static final Logger LOGGER = LoggerFactory.getLogger(DemoAppBase.class);
	
	static final String YOUR_GMAIL_ADDRESS = "your_gmail_user@gmail.com";
	
	// if you have 2-factor login turned on, you need to generate a once-per app password:
	// https://security.google.com/settings/security/apppasswords
	// if you use OAUTH2 (like in the OAuth2DemoApp.java), then getting this token requires a few steps, listed here:
	// https://github.com/bbottema/simple-java-mail/issues/421#issuecomment-1371010959
	static final String YOUR_GMAIL_PASSWORD = "<your password or oauth2 token>";

	/**
	 * If you just want to see what email is being sent, just set this to true. It won't actually connect to an SMTP server then.
	 */
	private static final boolean LOGGING_MODE = false;

	static {
		// make Simple Java Mail ignore the properties file completely: that's there for the junit tests, not this demo.
		ConfigLoaderTestHelper.clearConfigProperties();

		//noinspection ConstantConditions
		if (YOUR_GMAIL_ADDRESS.equals("your_gmail_user@gmail.com")) {
			throw new AssertionError("For these demo's to work, please provide your Gnail credentials in DemoAppBase.java first (or change the SMTP config)");
		}
	}

	static final MailerRegularBuilder<?> mailerSMTPBuilder = buildMailer("smtp.gmail.com", 25, YOUR_GMAIL_ADDRESS, YOUR_GMAIL_PASSWORD, TransportStrategy.SMTP);
	static final MailerRegularBuilder<?> mailerTLSBuilder = buildMailer("smtp.gmail.com", 587, YOUR_GMAIL_ADDRESS, YOUR_GMAIL_PASSWORD, TransportStrategy.SMTP_TLS);
	static final MailerRegularBuilder<?> mailerSSLBuilder = buildMailer("smtp.gmail.com", 465, YOUR_GMAIL_ADDRESS, YOUR_GMAIL_PASSWORD, TransportStrategy.SMTPS);

	@SuppressWarnings("SameParameterValue")
	static MailerRegularBuilder<?> buildMailer(String host, int port, String gMailAddress, String gMailPassword, TransportStrategy strategy) {
		return ImplLoader.loadMailerBuilder()
				.withSMTPServer(host, port, gMailAddress, gMailPassword)
				.withSMTPServerPassword(gMailPassword)
				.withTransportStrategy(strategy)
				.withTransportModeLoggingOnly(LOGGING_MODE)
				.clearProxy();
	}
}