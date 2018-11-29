package demo;

import org.simplejavamail.email.Email;
import org.simplejavamail.email.EmailBuilder;

import javax.activation.URLDataSource;
import java.io.IOException;
import java.net.URL;

/**
 * Demonstration program for the Simple Java Mail framework. Just fill your gmail, password and press GO.
 */
@SuppressWarnings({"WeakerAccess", "UnusedAssignment"})
public class FullEmailDemoApp extends DemoAppBase {
	
	public static void main(final String[] args) throws IOException {
		testMixedRelatedAlternativeIncludingCalendarAndMessageParsingUsingVariousMailers();
	}
	
	private static void testMixedRelatedAlternativeIncludingCalendarAndMessageParsingUsingVariousMailers() throws IOException {
		URL url = new URL("https://booking.skypicker.com/api/v0.1/get_file/6543520?file_id=551534031&simple_token=a7dd0427-743f-44ef-87ad-096321333ef4");
		URLDataSource resource = new URLDataSource(url);
		Email email = EmailBuilder.startingBlank()
				.from("b.bottema@gmail.com")
				.to("b.bottema@gmail.com")
				.withSubject("Simple Email 测试")
				.withPlainText("这是一封测试Simple Email的测试邮件")
				.withAttachment("invoice.pdf", resource)
				.buildEmail();
		
		mailerSMTP.sendMail(email);
//		mailerTLS.sendMail(emailNormal);
//		mailerSSL.sendMail(emailNormal);
//		mailerTLS.sendMail(emailFromMimeMessage); // should produce the exact same result as emailPopulatingBuilderNormal!
	}
}