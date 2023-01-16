package org.simplejavamail.api.email;

import jakarta.mail.util.ByteArrayDataSource;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.internal.util.NamedDataSource;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import static jakarta.mail.Message.RecipientType.BCC;
import static java.nio.charset.Charset.defaultCharset;
import static java.util.Calendar.APRIL;
import static java.util.Calendar.SEPTEMBER;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.CalendarMethod.ADD;
import static org.simplejavamail.api.email.CalendarMethod.REPLY;
import static org.simplejavamail.api.email.ContentTransferEncoding.BASE_64;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;
import static testutil.ThumbsUpImage.produceThumbsUpImage;

public class EmailTest {

	@Before
	public void clearDefaults() {
		ConfigLoaderTestHelper.clearConfigProperties();
	}

	@Test
	public void testSerialization() throws IOException {
		Email e = EmailBuilder.startingBlank()
				.from("lollypop", "lol.pop@somemail.com")
				.withReplyTo("lollypop-reply", "lol.pop.reply@somemail.com")
				.withBounceTo("lollypop-bounce", "lol.pop.bounce@somemail.com")
				.to("C.Cane", "candycane@candyshop.org")
				.withPlainText("We should meet up!")
				.withHTMLText("<b>We should meet up!</b><img src='cid:thumbsup'>")
				.withSubject("hey")
				.withDispositionNotificationTo("simple@address.com")
				.withReturnReceiptTo("Complex Email", "simple@address.com")
				.withHeader("dummyHeader", "dummyHeaderValue")
				.buildEmail();

		OutputStream fileOut = new ByteArrayOutputStream();
		ObjectOutputStream out = new ObjectOutputStream(fileOut);
		out.writeObject(e);
		out.close();
		fileOut.close();
	}

	@Test
	public void testToStringEmpty() {
		assertThat(EmailBuilder.startingBlank().buildEmail().toString()).isEqualTo("Email{\n"
				+ "\tid=null\n"
				+ "\tsentDate=null\n"
				+ "\tfromRecipient=null,\n"
				+ "\treplyToRecipient=null,\n"
				+ "\tbounceToRecipient=null,\n"
				+ "\ttext='null',\n"
				+ "\ttextHTML='null',\n"
				+ "\ttextCalendar='null (method: null)',\n"
				+ "\tcontentTransferEncoding='quoted-printable',\n"
				+ "\tsubject='null',\n"
				+ "\trecipients=[]\n"
				+ "}");
	}

	@Test
	public void testToStringFull() {
		Email e = EmailBuilder.forwarding(EmailBuilder.startingBlank().buildEmail())
				.fixingMessageId("some_id")
				.fixingSentDate(date(2011, APRIL, 11))
				.from("lollypop", "lol.pop@somemail.com")
				.withReplyTo("lollypop-reply", "lol.pop.reply@somemail.com")
				.withBounceTo("lollypop-bounce", "lol.pop.bounce@somemail.com")
				.to("C.Cane", "candycane@candyshop.org")
				.withPlainText("We should meet up!")
				.withHTMLText("<b>We should meet up!</b><img src='cid:thumbsup'>")
				.withSubject("hey")
				.withDispositionNotificationTo("dispo to", "simple@address.com")
				.withReturnReceiptTo("Complex Email", "simple@address.com")
				.withHeader("dummyHeader1", "dummyHeaderValue1")
				.withHeader("dummyHeader2", "dummyHeaderValue2")
				.withCalendarText(CalendarMethod.ADD, "Calendar text")
				.signWithDomainKey("dkim_key", "dkim_domain", "dkim_selector")
				.withEmbeddedImage("the_image", produceThumbsUpImage(), "image/png")
				.withAttachment("the_attachment", produceThumbsUpImage(), "image/png")
				.withAttachment("described_attachment", "blah".getBytes(defaultCharset()), "text/plain", "cool description", BASE_64)
				.buildEmail();

		assertThat(e.toString()).isEqualTo("Email{\n"
				+ "	id=some_id\n"
				+ "	sentDate=2011-04-11 12:00:00\n"
				+ "	fromRecipient=Recipient{name='lollypop', address='lol.pop@somemail.com', type=null},\n"
				+ "	replyToRecipient=Recipient{name='lollypop-reply', address='lol.pop.reply@somemail.com', type=null},\n"
				+ "	bounceToRecipient=Recipient{name='lollypop-bounce', address='lol.pop.bounce@somemail.com', type=null},\n"
				+ "	text='We should meet up!',\n"
				+ "	textHTML='<b>We should meet up!</b><img src='cid:thumbsup'>',\n"
				+ "	textCalendar='Calendar text (method: ADD)',\n"
				+ "	contentTransferEncoding='quoted-printable',\n"
				+ "	subject='hey',\n"
				+ "	recipients=[Recipient{name='C.Cane', address='candycane@candyshop.org', type=To}],\n"
				+ "	applyDKIMSignature=true,\n"
				+ "		dkimSelector=dkim_selector,\n"
				+ "		dkimSigningDomain=dkim_domain,\n"
				+ "	useDispositionNotificationTo=true,\n"
				+ "		dispositionNotificationTo=Recipient{name='dispo to', address='simple@address.com', type=null},\n"
				+ "	useReturnReceiptTo=true,\n"
				+ "		returnReceiptTo=Recipient{name='Complex Email', address='simple@address.com', type=null},\n"
				+ "	headers={dummyHeader1=[dummyHeaderValue1], dummyHeader2=[dummyHeaderValue2]},\n"
				+ "	embeddedImages=[AttachmentResource{\n"
				+ "		name='the_image',\n"
				+ "		dataSource.name=the_image,\n"
				+ "		dataSource.getContentType=image/png,\n"
				+ "		description=null,\n"
				+ "		contentTransferEncoding=null\n"
				+ "	}],\n"
				+ "	attachments=[AttachmentResource{\n"
				+ "		name='the_attachment',\n"
				+ "		dataSource.name=the_attachment,\n"
				+ "		dataSource.getContentType=image/png,\n"
				+ "		description=null,\n"
				+ "		contentTransferEncoding=null\n"
				+ "	}, AttachmentResource{\n"
				+ "		name='described_attachment',\n"
				+ "		dataSource.name=described_attachment,\n"
				+ "		dataSource.getContentType=text/plain,\n"
				+ "		description='cool description',\n"
				+ "		contentTransferEncoding='base64'\n"
				+ "	}],\n"
				+ "	forwardingEmail=true\n"
				+ "}");
	}

	@Test
	public void testEqualsEmail_EqualityCompletelyBlank() {
		final Email emptyEmail = b().buildEmail();
		assertThat(emptyEmail).isNotEqualTo(null);
		assertThat(emptyEmail).isNotEqualTo(22);
		assertThat(emptyEmail).isEqualTo(emptyEmail);
	}

	@Test
	public void testEqualsEmail_EqualityFieldByField() throws IOException {
		// From recipient
		assertEmailEqual(b().from("a@b.c").buildEmail(), b().from("a@b.c").buildEmail(), true);
		assertEmailEqual(b().from("name", "a@b.c").buildEmail(), b().from(new Recipient("name", "a@b.c", null)).buildEmail(), true);
		assertEmailEqual(b().from("a@b.c").buildEmail(), b().from("some@thing.else").buildEmail(), false);
		assertEmailEqual(b().from("name", "a@b.c").buildEmail(), b().from(new Recipient("different name", "a@b.c", null)).buildEmail(), false);
		assertEmailEqual(b().from("a@b.c").buildEmail(), b().buildEmail(), false);
		// ID
		assertEmailEqual(b().fixingMessageId(null).buildEmail(), b().fixingMessageId(null).buildEmail(), true);
		assertEmailEqual(b().fixingMessageId("").buildEmail(), b().fixingMessageId("").buildEmail(), true);
		assertEmailEqual(b().fixingMessageId("moo").buildEmail(), b().fixingMessageId("moo").buildEmail(), true);
		assertEmailEqual(b().fixingMessageId("moo").buildEmail(), b().fixingMessageId("shmoo").buildEmail(), false);
		assertEmailEqual(b().fixingMessageId("moo").buildEmail(), b().buildEmail(), false);
		// subject
		assertEmailEqual(b().withSubject(null).buildEmail(), b().withSubject(null).buildEmail(), true);
		assertEmailEqual(b().withSubject("").buildEmail(), b().withSubject("").buildEmail(), true);
		assertEmailEqual(b().withSubject("moo").buildEmail(), b().withSubject("moo").buildEmail(), true);
		assertEmailEqual(b().withSubject("moo").buildEmail(), b().withSubject("shmoo").buildEmail(), false);
		assertEmailEqual(b().withSubject("moo").buildEmail(), b().buildEmail(), false);
		// sentDate
		final Date now = new Date();
		assertEmailEqual(b().fixingSentDate(now).buildEmail(), b().fixingSentDate(now).buildEmail(), true);
		assertEmailEqual(b().fixingSentDate(now).buildEmail(), b().fixingSentDate(date(2011, SEPTEMBER, 15)).buildEmail(), false);
		assertEmailEqual(b().fixingSentDate(now).buildEmail(), b().buildEmail(), false);
		// replyTo recipient
		assertEmailEqual(b().withReplyTo("a@b.c").buildEmail(), b().withReplyTo("a@b.c").buildEmail(), true);
		assertEmailEqual(b().withReplyTo("name", "a@b.c").buildEmail(), b().withReplyTo(new Recipient("name", "a@b.c", null)).buildEmail(), true);
		assertEmailEqual(b().withReplyTo("a@b.c").buildEmail(), b().withReplyTo("some@thing.else").buildEmail(), false);
		assertEmailEqual(b().withReplyTo("name", "a@b.c").buildEmail(), b().withReplyTo(new Recipient("different name", "a@b.c", null)).buildEmail(), false);
		assertEmailEqual(b().withReplyTo("a@b.c").buildEmail(), b().buildEmail(), false);
		// bounceTo recipient
		assertEmailEqual(b().withBounceTo("a@b.c").buildEmail(), b().withBounceTo("a@b.c").buildEmail(), true);
		assertEmailEqual(b().withBounceTo("name", "a@b.c").buildEmail(), b().withBounceTo(new Recipient("name", "a@b.c", null)).buildEmail(), true);
		assertEmailEqual(b().withBounceTo("a@b.c").buildEmail(), b().withBounceTo("some@thing.else").buildEmail(), false);
		assertEmailEqual(b().withBounceTo("name", "a@b.c").buildEmail(), b().withBounceTo(new Recipient("different name", "a@b.c", null)).buildEmail(), false);
		assertEmailEqual(b().withBounceTo("a@b.c").buildEmail(), b().buildEmail(), false);
		// dispositionNotificationTo recipient
		assertEmailEqual(b().withDispositionNotificationTo("a@b.c").buildEmail(), b().withDispositionNotificationTo("a@b.c").buildEmail(), true);
		assertEmailEqual(b().withDispositionNotificationTo("name", "a@b.c").buildEmail(), b().withDispositionNotificationTo(new Recipient("name", "a@b.c", null)).buildEmail(), true);
		assertEmailEqual(b().withDispositionNotificationTo("a@b.c").buildEmail(), b().withDispositionNotificationTo("some@thing.else").buildEmail(), false);
		assertEmailEqual(b().withDispositionNotificationTo("name", "a@b.c").buildEmail(), b().withDispositionNotificationTo(new Recipient("different name", "a@b.c", null)).buildEmail(), false);
		assertEmailEqual(b().withDispositionNotificationTo("a@b.c").buildEmail(), b().buildEmail(), false);
		// returnReceiptTo recipient
		assertEmailEqual(b().withReturnReceiptTo("a@b.c").buildEmail(), b().withReturnReceiptTo("a@b.c").buildEmail(), true);
		assertEmailEqual(b().withReturnReceiptTo("name", "a@b.c").buildEmail(), b().withReturnReceiptTo(new Recipient("name", "a@b.c", null)).buildEmail(), true);
		assertEmailEqual(b().withReturnReceiptTo("a@b.c").buildEmail(), b().withReturnReceiptTo("some@thing.else").buildEmail(), false);
		assertEmailEqual(b().withReturnReceiptTo("name", "a@b.c").buildEmail(), b().withReturnReceiptTo(new Recipient("different name", "a@b.c", null)).buildEmail(), false);
		assertEmailEqual(b().withReturnReceiptTo("a@b.c").buildEmail(), b().buildEmail(), false);
		// plainText
		assertEmailEqual(b().withPlainText((String) null).buildEmail(), b().withPlainText((String) null).buildEmail(), true);
		assertEmailEqual(b().withPlainText("").buildEmail(), b().withPlainText("").buildEmail(), true);
		assertEmailEqual(b().withPlainText("moo").buildEmail(), b().withPlainText("moo").buildEmail(), true);
		assertEmailEqual(b().withPlainText("moo").buildEmail(), b().withPlainText("shmoo").buildEmail(), false);
		assertEmailEqual(b().withPlainText("moo").buildEmail(), b().buildEmail(), false);
		// htmlText
		assertEmailEqual(b().withHTMLText((String) null).buildEmail(), b().withHTMLText((String) null).buildEmail(), true);
		assertEmailEqual(b().withHTMLText("").buildEmail(), b().withHTMLText("").buildEmail(), true);
		assertEmailEqual(b().withHTMLText("moo").buildEmail(), b().withHTMLText("moo").buildEmail(), true);
		assertEmailEqual(b().withHTMLText("moo").buildEmail(), b().withHTMLText("shmoo").buildEmail(), false);
		assertEmailEqual(b().withHTMLText("moo").buildEmail(), b().buildEmail(), false);
		// calendarText
		assertEmailEqual(b().withCalendarText(REPLY, "").buildEmail(), b().withCalendarText(REPLY, "").buildEmail(), true);
		assertEmailEqual(b().withCalendarText(ADD, "moo").buildEmail(), b().withCalendarText(ADD, "moo").buildEmail(), true);
		assertEmailEqual(b().withCalendarText(ADD, "moo").buildEmail(), b().withCalendarText(ADD, "shmoo").buildEmail(), false);
		assertEmailEqual(b().withCalendarText(ADD, "moo").buildEmail(), b().withCalendarText(REPLY, "moo").buildEmail(), false);
		assertEmailEqual(b().withCalendarText(ADD, "moo").buildEmail(), b().buildEmail(), false);
		// forWardEmail
		final Email email = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, false).buildEmail();
		final Email emailOther = EmailHelper.createDummyEmailBuilder(false, true, false, false, false, false).buildEmail();
		assertEmailEqual(f(email).buildEmail(), f(email).buildEmail(), true);
		assertEmailEqual(f(email).buildEmail(), b().buildEmail(), false);
		assertEmailEqual(f(email).buildEmail(), f(emailOther).buildEmail(), false);
		assertEmailEqual(f(emailOther).buildEmail(), f(emailOther).buildEmail(), true);
		// recipients various combinations
		assertEmailEqual(b().cc("a@b.c;1@2.3").buildEmail(), b().cc("a@b.c;1@2.3").buildEmail(), true);
		assertEmailEqual(b().cc("a@b.c;1@2.3").buildEmail(), b().cc("a@b.c").cc("1@2.3").buildEmail(), true);
		assertEmailEqual(b().cc("a@b.c").bcc("name", "a@b.c").buildEmail(), b().cc("a@b.c").withRecipient("name", "a@b.c", BCC).buildEmail(), true);
		assertEmailEqual(b().cc("a@b.c").bcc("name", "a@b.c").buildEmail(), b().cc("a@b.c").withRecipient("a@b.c", BCC).buildEmail(), false);
		assertEmailEqual(b().cc("a@b.c;1@2.3").buildEmail(), b().buildEmail(), false);
		assertEmailEqual(b().cc("a@b.c;1@2.3").buildEmail(), b().cc("a@b.c;1@2.other").buildEmail(), false);
		assertEmailEqual(b().cc("a@b.c;1@2.3").buildEmail(), b().cc("a@b.c").buildEmail(), false);
		assertEmailEqual(b().cc("a@b.c").bcc("name", "a@b.c").buildEmail(), b().withRecipient("a@b.c", BCC).buildEmail(), false);
		// headers
		assertEmailEqual(b().withHeader("name", 44).buildEmail(), b().withHeader("name", 44).buildEmail(), true);
		assertEmailEqual(b().withHeaders(map("name1", 44, "name2", "value")).buildEmail(), b().withHeaders(map("name1", 44, "name2", "value")).buildEmail(), true);
		assertEmailEqual(b().withHeaders(map("name1", 44, "name2", "value")).buildEmail(), b().withHeader("name1", 44).withHeader("name2", "value").buildEmail(), true);
		assertEmailEqual(b().withHeaders(map("name1", 44, "name2", "value")).buildEmail(), b().withHeaders(map("name1", 44)).buildEmail(), false);
		assertEmailEqual(b().withHeaders(map("name1", 44, "name2", "value")).buildEmail(), b().withHeader("name1", 44).buildEmail(), false);
		assertEmailEqual(b().withHeaders(map("name1", 44, "name2", "value")).buildEmail(), b().withHeaders(map("name1", 45, "name2", "value")).buildEmail(), false);
		assertEmailEqual(b().withHeaders(map("name1", 44, "name2", "value")).buildEmail(), b().withHeader("name3", 44).withHeader("name2", "value").buildEmail(), false);
		assertEmailEqual(b().withHeader("name", 44).buildEmail(), b().buildEmail(), false);
	}

	@Test
	public void testEqualsEmail_EqualityEmbeddedImages() throws IOException {
		assertEmailEqual(b().withEmbeddedImage("name", new byte[]{'a'}, "image/png").buildEmail(), b().withEmbeddedImage("name", new byte[]{'a'}, "image/png").buildEmail(), true);
		assertEmailEqual(b().withEmbeddedImage(null, new NamedDataSource("n1", new ByteArrayDataSource("data", "image/png"))).buildEmail(), b().withEmbeddedImage(null, new NamedDataSource("n1", new ByteArrayDataSource("data", "image/png"))).buildEmail(), true);
		assertEmailEqual(b().withEmbeddedImage("name", new ByteArrayDataSource("data", "image/png")).buildEmail(), b().withEmbeddedImage("name", new ByteArrayDataSource("data", "image/png")).buildEmail(), true);
		assertEmailEqual(b()
				.withEmbeddedImage("name", new byte[]{'a'}, "image/png")
				.withEmbeddedImage("name2", new byte[]{'b'}, "image/bmp").buildEmail(), b()
				.withEmbeddedImage("name", new byte[]{'a'}, "image/png")
				.withEmbeddedImage("name2", new byte[]{'b'}, "image/bmp").buildEmail(), true);
		assertEmailEqual(b().withEmbeddedImage(null, new NamedDataSource("n1", new ByteArrayDataSource("data", "image/png"))).buildEmail(), b().withEmbeddedImage(null, new NamedDataSource("n2", new ByteArrayDataSource("data", "image/png"))).buildEmail(), false);
		assertEmailEqual(b().withEmbeddedImage(null, new NamedDataSource("n1", new ByteArrayDataSource("data", "image/png"))).buildEmail(), b().withEmbeddedImage(null, new NamedDataSource("n1", new ByteArrayDataSource("data", "image/jpg"))).buildEmail(), false);
		assertEmailEqual(b().withEmbeddedImage("name", new ByteArrayDataSource("data", "image/png")).buildEmail(), b().withEmbeddedImage("nameOther", new ByteArrayDataSource("data", "image/png")).buildEmail(), false);
		assertEmailEqual(b().withEmbeddedImage("name", new ByteArrayDataSource("data", "image/png")).buildEmail(), b().withEmbeddedImage("name", new ByteArrayDataSource("data", "image/jpg")).buildEmail(), false);
		assertEmailEqual(b()
				.withEmbeddedImage("name", new byte[]{'a'}, "image/png")
				.withEmbeddedImage("name2", new byte[]{'b'}, "image/bmp").buildEmail(), b()
				.withEmbeddedImage("name", new byte[]{'a'}, "image/png").buildEmail(), false);
		assertEmailEqual(b()
				.withEmbeddedImage("name", new byte[]{'a'}, "image/png")
				.withEmbeddedImage("name2", new byte[]{'b'}, "image/bmp").buildEmail(), b()
				.withEmbeddedImage("name", new byte[]{'a'}, "image/png")
				.withEmbeddedImage("name3", new byte[]{'b'}, "image/bmp").buildEmail(), false);
		assertEmailEqual(b().withEmbeddedImage("name", new byte[]{'a'}, "image/png").buildEmail(), b().buildEmail(), false);
	}

	@Test
	public void testEqualsEmail_EqualityAttachments() throws IOException {
		assertEmailEqual(b().withAttachment("name", new byte[]{'a'}, "image/png").buildEmail(), b().withAttachment("name", new byte[]{'a'}, "image/png").buildEmail(), true);
		assertEmailEqual(b().withAttachment(null, new NamedDataSource("n1", new ByteArrayDataSource("data", "image/png"))).buildEmail(), b().withAttachment(null, new NamedDataSource("n1", new ByteArrayDataSource("data", "image/png"))).buildEmail(), true);
		assertEmailEqual(b().withAttachment("name", new ByteArrayDataSource("data", "image/png")).buildEmail(), b().withAttachment("name", new ByteArrayDataSource("data", "image/png")).buildEmail(), true);
		assertEmailEqual(b()
				.withAttachment("name", new byte[]{'a'}, "image/png")
				.withAttachment("name2", new byte[]{'b'}, "image/bmp").buildEmail(), b()
				.withAttachment("name", new byte[]{'a'}, "image/png")
				.withAttachment("name2", new byte[]{'b'}, "image/bmp").buildEmail(), true);
		assertEmailEqual(b().withAttachment(null, new NamedDataSource("n1", new ByteArrayDataSource("data", "image/png"))).buildEmail(), b().withAttachment(null, new NamedDataSource("n2", new ByteArrayDataSource("data", "image/png"))).buildEmail(), false);
		assertEmailEqual(b().withAttachment(null, new NamedDataSource("n1", new ByteArrayDataSource("data", "image/png"))).buildEmail(), b().withAttachment(null, new NamedDataSource("n1", new ByteArrayDataSource("data", "image/jpg"))).buildEmail(), false);
		assertEmailEqual(b().withAttachment("name", new ByteArrayDataSource("data", "image/png")).buildEmail(), b().withAttachment("nameOther", new ByteArrayDataSource("data", "image/png")).buildEmail(), false);
		assertEmailEqual(b().withAttachment("name", new ByteArrayDataSource("data", "image/png")).buildEmail(), b().withAttachment("name", new ByteArrayDataSource("data", "image/jpg")).buildEmail(), false);
		assertEmailEqual(b()
				.withAttachment("name", new byte[]{'a'}, "image/png")
				.withAttachment("name2", new byte[]{'b'}, "image/bmp").buildEmail(), b()
				.withAttachment("name", new byte[]{'a'}, "image/png").buildEmail(), false);
		assertEmailEqual(b()
				.withAttachment("name", new byte[]{'a'}, "image/png")
				.withAttachment("name2", new byte[]{'b'}, "image/bmp").buildEmail(), b()
				.withAttachment("name", new byte[]{'a'}, "image/png")
				.withAttachment("name3", new byte[]{'b'}, "image/bmp").buildEmail(), false);
		assertEmailEqual(b().withAttachment("name", new byte[]{'a'}, "image/png").buildEmail(), b().buildEmail(), false);
	}

	@Test
	public void testEqualsEmail_EqualityPkcs12Config() {
		final Pkcs12Config pkcs12KeyStore = loadPkcs12KeyStore();
		final Pkcs12Config pkcs12KeyStoreOther = Pkcs12Config.builder()
				.pkcs12Store("src/test/resources/pkcs12/smime_keystore.pkcs12")
				.storePassword("password")
				.keyAlias("alias")
				.keyPassword("password").build();
		assertEmailEqual(b().signWithSmime(pkcs12KeyStore).buildEmail(), b().signWithSmime(pkcs12KeyStore).buildEmail(), true);
		assertEmailEqual(b().signWithSmime(pkcs12KeyStore).buildEmail(), b().signWithSmime(pkcs12KeyStoreOther).buildEmail(), false);
		assertEmailEqual(b().signWithSmime(pkcs12KeyStore).buildEmail(), b().buildEmail(), false);
	}

	private void assertEmailEqual(Email e1, Email e2, boolean expected) {
		assertThat(e1.equals(e2)).isEqualTo(expected);
		assertThat(e2.equals(e1)).isEqualTo(expected);
	}

	@NotNull
	// keeps the test code terse
	private static EmailPopulatingBuilder b() {
		return EmailBuilder.startingBlank();
	}

	@NotNull
	private EmailPopulatingBuilder f(Email email) {
		return EmailBuilder.forwarding(email);
	}

	@NotNull
	@SuppressWarnings("SameParameterValue")
	private Date date(int year, int month, int dayOfMonth) {
		final GregorianCalendar cal = new GregorianCalendar(year, month, dayOfMonth);
		cal.setTimeZone(TimeZone.getTimeZone("UTC"));
		return cal.getTime();
	}

	@NotNull
	@SuppressWarnings("SameParameterValue")
	private static Map<String, Collection<Object>> map(String name1, int value1, String name2, String value2) {
		Map<String, Collection<Object>> map = new HashMap<>();
		map.put(name1, singletonList(value1));
		map.put(name2, singletonList(value2));
		return map;
	}

	@NotNull
	@SuppressWarnings("SameParameterValue")
	private static Map<String, Collection<Object>> map(String name1, int value1) {
		Map<String, Collection<Object>> map = new HashMap<>();
		map.put(name1, singletonList(value1));
		return map;
	}

}