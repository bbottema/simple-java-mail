package org.simplejavamail.email;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.util.MiscUtil;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unused")
public class EmailConfigTest {
	private SimpleJavaMail configuredMail;

	@BeforeEach
	public void configureDefaults() {
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
		configuredMail = SimpleJavaMail.withConfig(ConfigLoader.builder()
				.withInputStream(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)))
				.load());
	}

	@Test
	public void emailConstructor_WithoutConfig() {
		Email email = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).emailBuilder().startingBlank().buildEmail();
		assertThat(email.getFromRecipient()).isNull();
		assertThat(email.getReplyToRecipients()).isEmpty();
		assertThat(email.getBounceToRecipient()).isNull();
		assertThat(email.getRecipients()).isEmpty();
	}

	@Test
	public void emailConstructor_WithConfig() {
		Email email = configuredMail.emailBuilder().startingBlank().buildEmailCompletedWithDefaultsAndOverrides();
		assertThat(email.getFromRecipient()).isEqualToComparingFieldByField(new Recipient("From Default", "from@default.com", null, null));
		assertThat(email.getReplyToRecipients()).hasSize(1);
		assertThat(email.getReplyToRecipients().get(0)).isEqualToComparingFieldByField(new Recipient("Reply-To Default", "reply-to@default.com", null, null));
		assertThat(email.getBounceToRecipient()).isEqualToComparingFieldByField(new Recipient("Bounce-To Default", "bounce-to@default.com", null, null));
		assertThat(email.getRecipients()).isNotEmpty();
		assertThat(email.getRecipients()).hasSize(3);
		assertThat(email.getRecipients()).usingFieldByFieldElementComparator().contains(new Recipient("To Default", "to@default.com", TO, null));
		assertThat(email.getRecipients()).usingFieldByFieldElementComparator().contains(new Recipient("CC Default", "cc@default.com", CC, null));
		assertThat(email.getRecipients()).usingFieldByFieldElementComparator().contains(new Recipient("BCC Default", "bcc@default.com", BCC, null));
	}

	@Test
	public void testBeautifyCID() {
		assertThat(MiscUtil.extractCID(null)).isNull();
		assertThat(MiscUtil.extractCID("")).isEqualTo("");
		assertThat(MiscUtil.extractCID("haha")).isEqualTo("haha");
		assertThat(MiscUtil.extractCID("<haha>")).isEqualTo("haha");
	}
}
