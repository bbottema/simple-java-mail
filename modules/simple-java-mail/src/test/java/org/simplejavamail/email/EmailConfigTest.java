package org.simplejavamail.email;

import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.internal.util.MiscUtil;
import org.simplejavamail.config.ConfigLoader;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;

import static javax.mail.Message.RecipientType.*;
import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unused")
public class EmailConfigTest {

	@Before
	public void restoreOriginalStaticProperties() {
		String s = "simplejavamail.defaults.from.name=From Default\n"
				+ "simplejavamail.defaults.from.address=from@default.com\n"
				+ "simplejavamail.defaults.replyto.name=Reply-To Default\n"
				+ "simplejavamail.defaults.replyto.address=reply-to@default.com\n"
				+ "simplejavamail.defaults.bounceto.name=Bounce-To Default\n"
				+ "simplejavamail.defaults.bounceto.address=bounce-to@default.com\n"
				+ "simplejavamail.defaults.to.name=To Default\n"
				+ "simplejavamail.defaults.to.address=to@default.com\n"
				+ "simplejavamail.defaults.cc.name=CC Default\n"
				+ "simplejavamail.defaults.cc.address=cc@default.com\n"
				+ "simplejavamail.defaults.bcc.name=BCC Default\n"
				+ "simplejavamail.defaults.bcc.address=bcc@default.com";
		ConfigLoader.loadProperties(new ByteArrayInputStream(s.getBytes()), false);
	}

	@Test
	public void emailConstructor_WithoutConfig() {
		ConfigLoaderTestHelper.clearConfigProperties();
		Email email = EmailBuilder.startingBlank().buildEmail();
		assertThat(email.getFromRecipient()).isNull();
		assertThat(email.getReplyToRecipient()).isNull();
		assertThat(email.getBounceToRecipient()).isNull();
		assertThat(email.getRecipients()).isEmpty();
	}

	@Test
	public void emailConstructor_WithConfig() {
		Email email = EmailBuilder.startingBlank().buildEmail();
		assertThat(email.getFromRecipient()).isEqualToComparingFieldByField(new Recipient("From Default", "from@default.com", null));
		assertThat(email.getReplyToRecipient()).isEqualToComparingFieldByField(new Recipient("Reply-To Default", "reply-to@default.com", null));
		assertThat(email.getBounceToRecipient()).isEqualToComparingFieldByField(new Recipient("Bounce-To Default", "bounce-to@default.com", null));
		assertThat(email.getRecipients()).isNotEmpty();
		assertThat(email.getRecipients()).hasSize(3);
		assertThat(email.getRecipients()).usingFieldByFieldElementComparator().contains(new Recipient("To Default", "to@default.com", TO));
		assertThat(email.getRecipients()).usingFieldByFieldElementComparator().contains(new Recipient("CC Default", "cc@default.com", CC));
		assertThat(email.getRecipients()).usingFieldByFieldElementComparator().contains(new Recipient("BCC Default", "bcc@default.com", BCC));
	}

	@Test
	public void testBeautifyCID() {
		assertThat(MiscUtil.extractCID(null)).isNull();
		assertThat(MiscUtil.extractCID("")).isEqualTo("");
		assertThat(MiscUtil.extractCID("haha")).isEqualTo("haha");
		assertThat(MiscUtil.extractCID("<haha>")).isEqualTo("haha");
	}
}