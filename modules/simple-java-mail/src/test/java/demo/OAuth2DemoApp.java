package demo;

import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.io.IOException;

/**
 * Demonstration program for the Simple Java Mail framework. Just fill your gmail, password and press GO.
 */
public class OAuth2DemoApp extends DemoAppBase {

	static final MailerRegularBuilder<?> mailerOAuth2Builder = buildMailer("smtp.gmail.com", 587, YOUR_GMAIL_ADDRESS, YOUR_GMAIL_PASSWORD, TransportStrategy.SMTP_OAUTH2);

	public static void main(final String[] args) throws IOException {
		Email email = FullEmailDemoApp.produceMixedRelatedAlternativeIncludingCalendarAndMessageParsingUsingVariousMailersEmail();

		mailerOAuth2Builder.buildMailer().sendMail(email);
	}
}