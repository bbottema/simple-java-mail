package org.simplejavamail.email;

import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.internal.util.ConfigLoader;
import org.simplejavamail.mailer.Mailer;
import testutil.ConfigHelper;
import testutil.EmailHelper;

import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Properties;

import static javax.mail.Message.RecipientType.*;
import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unused")
public class EmailTest {

	@Before
	public void restoreOriginalStaticProperties()
			throws IOException {
		String s = "simplejavamail.defaults.from.name=From Default\n"
				+ "simplejavamail.defaults.from.address=from@default.com\n"
				+ "simplejavamail.defaults.replyto.name=Reply-To Default\n"
				+ "simplejavamail.defaults.replyto.address=reply-to@default.com\n"
				+ "simplejavamail.defaults.to.name=To Default\n"
				+ "simplejavamail.defaults.to.address=to@default.com\n"
				+ "simplejavamail.defaults.cc.name=CC Default\n"
				+ "simplejavamail.defaults.cc.address=cc@default.com\n"
				+ "simplejavamail.defaults.bcc.name=BCC Default\n"
				+ "simplejavamail.defaults.bcc.address=bcc@default.com";
		ConfigLoader.loadProperties(new ByteArrayInputStream(s.getBytes()), false);
	}

	@Test
	public void emailConstructor_WithoutConfig()
			throws Exception {
		ConfigHelper.clearConfigProperties();
		Email email = new Email();
		assertThat(email.getFromRecipient()).isNull();
		assertThat(email.getReplyToRecipient()).isNull();
		assertThat(email.getRecipients()).isEmpty();
	}

	@Test
	public void emailConstructor_WithConfig() {
		Email email = new Email();
		assertThat(email.getFromRecipient()).isEqualToComparingFieldByField(new Recipient("From Default", "from@default.com", null));
		assertThat(email.getReplyToRecipient()).isEqualToComparingFieldByField(new Recipient("Reply-To Default", "reply-to@default.com", null));
		assertThat(email.getRecipients()).isNotEmpty();
		assertThat(email.getRecipients()).hasSize(3);
		assertThat(email.getRecipients()).usingFieldByFieldElementComparator().contains(new Recipient("To Default", "to@default.com", TO));
		assertThat(email.getRecipients()).usingFieldByFieldElementComparator().contains(new Recipient("CC Default", "cc@default.com", CC));
		assertThat(email.getRecipients()).usingFieldByFieldElementComparator().contains(new Recipient("BCC Default", "bcc@default.com", BCC));
	}

	@Test
	public void testParser()
			throws Exception {
		final Email emailNormal = EmailHelper.createDummyEmail();

		// let's try producing and then consuming a MimeMessage ->
		final MimeMessage mimeMessage = Mailer.produceMimeMessage(emailNormal, Session.getDefaultInstance(new Properties()));
		final Email emailFromMimeMessage = new Email(mimeMessage);

		assertThat(emailFromMimeMessage).isEqualTo(emailNormal);
	}

	@Test
	public void testBeautifyCID() {
		assertThat(Email.extractCID(null)).isNull();
		assertThat(Email.extractCID("")).isEqualTo("");
		assertThat(Email.extractCID("haha")).isEqualTo("haha");
		assertThat(Email.extractCID("<haha>")).isEqualTo("haha");
	}
}