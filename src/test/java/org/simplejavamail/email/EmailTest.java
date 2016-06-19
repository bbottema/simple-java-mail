package org.simplejavamail.email;

import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.mailer.Mailer;
import org.simplejavamail.internal.util.ConfigLoader;
import testutil.ConfigHelper;

import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.mail.util.ByteArrayDataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Properties;

import static javax.mail.Message.RecipientType.*;
import static javax.xml.bind.DatatypeConverter.parseBase64Binary;
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
		ConfigLoader.loadProperties(new ByteArrayInputStream(s.getBytes()));
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
		final Email emailNormal = createDummyEmail();

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

	@Test
	public void testBuilder()
			throws IOException {
		ByteArrayDataSource namedAttachment = new ByteArrayDataSource("Black Tie Optional", "text/plain");
		namedAttachment.setName("dresscode.txt"); // normally not needed, but otherwise the equals will fail
		String base64String = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAABeElEQVRYw2NgoAAYGxu3GxkZ7TY1NZVloDcAWq4MxH+B+D8Qv3FwcOCgtwM6oJaDMTAUXOhmuYqKCjvQ0pdoDrCnmwNMTEwakC0H4u8GBgYC9Ap6DSD+iewAoIPm0ctyLqBlp9F8/x+YE4zpYT8T0LL16JYD8U26+B7oyz4sloPwenpYno3DchCeROsUbwa05A8eB3wB4kqgIxOAuArIng7EW4H4EhC/B+JXQLwDaI4ryZaDSjeg5mt4LCcFXyIn1fdSyXJQVt1OtMWGhoai0OD8T0W8GohZifE1PxD/o7LlsPLiFNAKRrwOABWptLAcqc6QGDAHQEOAYaAc8BNotsJAOgAUAosG1AFA/AtUoY3YEFhKMAvS2AE7iC1+WaG1H6gY3gzE36hUFJ8mqzbU1dUVBBqQBzTgIDQRkWo5qCZdpaenJ0Zx1aytrc0DDB0foIG1oAYKqC0IZK8D4n1AfA6IzwPxXpCFoGoZVEUDaRGGUTAKRgEeAAA2eGJC+ETCiAAAAABJRU5ErkJggg==";

		final Email emailNormal = new Email.Builder()
				.from("lollypop", "lol.pop@somemail.com")
				.replyTo("lollypop", "lol.pop@somemail.com")
				.to("C.Cane", "candycane@candyshop.org")
				.text("We should meet up!")
				.textHTML("<b>We should meet up!</b><img src='cid:thumbsup'>")
				.subject("hey")
				.addAttachment("dresscode.txt", namedAttachment)
				.addAttachment("location.txt", "On the moon!".getBytes(Charset.defaultCharset()), "text/plain")
				.embedImage("thumbsup", parseBase64Binary(base64String), "image/png")
				.build();

		assertThat(emailNormal).isEqualTo(createDummyEmail());
	}

	public static Email createDummyEmail()
			throws IOException {
		final Email emailNormal = new Email();
		emailNormal.setFromAddress("lollypop", "lol.pop@somemail.com");
		// normally not needed, but for the test it is because the MimeMessage will
		// have it added automatically as well, so the parsed Email will also have it then
		emailNormal.setReplyToAddress("lollypop", "lol.pop@somemail.com");
		// don't forget to add your own address here ->
		emailNormal.addRecipient("C.Cane", "candycane@candyshop.org", TO);
		emailNormal.setText("We should meet up!");
		emailNormal.setTextHTML("<b>We should meet up!</b><img src='cid:thumbsup'>");
		emailNormal.setSubject("hey");

		// add two text files in different ways and a black thumbs up embedded image ->
		ByteArrayDataSource namedAttachment = new ByteArrayDataSource("Black Tie Optional", "text/plain");
		namedAttachment.setName("dresscode.txt"); // normally not needed, but otherwise the equals will fail
		emailNormal.addAttachment("dresscode.txt", namedAttachment);
		emailNormal.addAttachment("location.txt", "On the moon!".getBytes(Charset.defaultCharset()), "text/plain");
		String base64String = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAABeElEQVRYw2NgoAAYGxu3GxkZ7TY1NZVloDcAWq4MxH+B+D8Qv3FwcOCgtwM6oJaDMTAUXOhmuYqKCjvQ0pdoDrCnmwNMTEwakC0H4u8GBgYC9Ap6DSD+iewAoIPm0ctyLqBlp9F8/x+YE4zpYT8T0LL16JYD8U26+B7oyz4sloPwenpYno3DchCeROsUbwa05A8eB3wB4kqgIxOAuArIng7EW4H4EhC/B+JXQLwDaI4ryZaDSjeg5mt4LCcFXyIn1fdSyXJQVt1OtMWGhoai0OD8T0W8GohZifE1PxD/o7LlsPLiFNAKRrwOABWptLAcqc6QGDAHQEOAYaAc8BNotsJAOgAUAosG1AFA/AtUoY3YEFhKMAvS2AE7iC1+WaG1H6gY3gzE36hUFJ8mqzbU1dUVBBqQBzTgIDQRkWo5qCZdpaenJ0Zx1aytrc0DDB0foIG1oAYKqC0IZK8D4n1AfA6IzwPxXpCFoGoZVEUDaRGGUTAKRgEeAAA2eGJC+ETCiAAAAABJRU5ErkJggg==";
		emailNormal.addEmbeddedImage("thumbsup", parseBase64Binary(base64String), "image/png");
		return emailNormal;
	}
}