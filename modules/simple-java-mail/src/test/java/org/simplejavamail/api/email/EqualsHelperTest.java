package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.email.EmailBuilder;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;

import javax.mail.util.ByteArrayDataSource;
import java.io.IOException;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;

import static java.util.Calendar.SEPTEMBER;
import static javax.mail.Message.RecipientType.BCC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.CalendarMethod.ADD;
import static org.simplejavamail.api.email.CalendarMethod.REPLY;

public class EqualsHelperTest {

	@Before
	public void clearDefaults() {
		ConfigLoaderTestHelper.clearConfigProperties();
	}

	@Test
	public void testEqualsEmail_EqualityCompletelyBlank() {
		assertEmailEqual(b().buildEmail(), b().buildEmail(), true);
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
		assertEmailEqual(b().fixingSentDate(new Date()).buildEmail(), b().fixingSentDate(new Date()).buildEmail(), true);
		assertEmailEqual(b().fixingSentDate(new Date()).buildEmail(), b().fixingSentDate(date(2011, SEPTEMBER, 15)).buildEmail(), false);
		assertEmailEqual(b().fixingSentDate(new Date()).buildEmail(), b().buildEmail(), false);
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
		final Email email = EmailHelper.createDummyEmailBuilder(true, false, true, true).buildEmail();
		final Email emailOther = EmailHelper.createDummyEmailBuilder(false, true, false, false).buildEmail();
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

	private void assertEmailEqual(Email e1, Email e2, boolean expected) {
		assertThat(EqualsHelper.equalsEmail(e1, e2)).isEqualTo(expected);
		assertThat(EqualsHelper.equalsEmail(e2, e1)).isEqualTo(expected);
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
		return new GregorianCalendar(year, month, dayOfMonth).getTime();
	}

	@NotNull
	@SuppressWarnings("SameParameterValue")
	private static Map<String, Object> map(String name1, int value1, String name2, String value2) {
		Map<String, Object> map = new HashMap<>();
		map.put(name1, value1);
		map.put(name2, value2);
		return map;
	}

	@NotNull
	@SuppressWarnings("SameParameterValue")
	private static Map<String, Object> map(String name1, int value1) {
		Map<String, Object> map = new HashMap<>();
		map.put(name1, value1);
		return map;
	}
}