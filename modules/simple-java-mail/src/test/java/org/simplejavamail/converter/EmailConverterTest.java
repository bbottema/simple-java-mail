package org.simplejavamail.converter;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.Recipient;
import testutil.SecureTestDataHelper;
import testutil.SecureTestDataHelper.PasswordsConsumer;

import java.io.File;
import java.util.Properties;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static javax.mail.Message.RecipientType.CC;
import static javax.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.internal.util.MiscUtil.normalizeNewlines;

public class EmailConverterTest {

	private static final String RESOURCES = determineResourceFolder("simple-java-mail") + "/test/resources";
	private static final String RESOURCE_TEST_MESSAGES = RESOURCES + "/test-messages";

	@Test
	public void testOutlookBasicConversions() {
		final Recipient elias = new Recipient("Elias Laugher", "elias.laugher@gmail.com", null);
		final Recipient sven = new Recipient("Sven Sielenkemper", "sielenkemper@otris.de", TO);
		final Recipient niklas = new Recipient("niklas.lindson@gmail.com", "niklas.lindson@gmail.com", CC);

		@NotNull Email msg = EmailConverter.outlookMsgToEmail(new File(RESOURCE_TEST_MESSAGES + "/simple email with TO and CC.msg"));
		EmailAssert.assertThat(msg).hasFromRecipient(elias);
		EmailAssert.assertThat(msg).hasSubject("Test E-Mail");
		EmailAssert.assertThat(msg).hasOnlyRecipients(sven, niklas);
		EmailAssert.assertThat(msg).hasNoAttachments();
		assertThat(msg.getPlainText()).isNotEmpty();
		assertThat(normalizeNewlines(msg.getHTMLText())).isEqualTo("<div dir=\"auto\">Just a test to get an email with one cc recipient.</div>\n");
		assertThat(normalizeNewlines(msg.getPlainText())).isEqualTo("Just a test to get an email with one cc recipient.\n");
	}

	@Test
	public void testOutlookUnicode() {
		final Recipient kalejs = new Recipient("m.kalejs@outlook.com", "m.kalejs@outlook.com", null);
		final Recipient dummy = new Recipient("doesnotexist@doesnt.com", "doesnotexist@doesnt.com", TO);

		@NotNull Email msg = EmailConverter.outlookMsgToEmail(new File(RESOURCE_TEST_MESSAGES + "/tst_unicode.msg"));
		EmailAssert.assertThat(msg).hasFromRecipient(kalejs);
		EmailAssert.assertThat(msg).hasSubject("Testcase");
		EmailAssert.assertThat(msg).hasOnlyRecipients(dummy);
		EmailAssert.assertThat(msg).hasNoAttachments();
		assertThat(msg.getPlainText()).isNotEmpty();
		assertThat(normalizeNewlines(msg.getHTMLText())).isNotEmpty();
		assertThat(normalizeNewlines(msg.getPlainText())).isEqualTo("-/-\n" +
				"Char-å-Char\n" +
				"-/-\n" +
				"Char-Å-Char\n" +
				"-/-\n" +
				"Char-ø-Char\n" +
				"-/-\n" +
				"Char-Ø-Char\n" +
				"-/-\n" +
				"Char-æ-Char\n" +
				"-/-\n" +
				"Char-Æ-Char\n" +
				" \n");
	}

	@Test
	public void testOutlookUnsentDraft() {
		final Recipient time2talk = new Recipient("time2talk@online-convert.com", "time2talk@online-convert.com", TO);

		@NotNull Email msg = EmailConverter.outlookMsgToEmail(new File(RESOURCE_TEST_MESSAGES + "/unsent draft.msg"));
		EmailAssert.assertThat(msg).hasFromRecipient(new Recipient(null, "donotreply@unknown-from-address.net", null));
		EmailAssert.assertThat(msg).hasSubject("MSG Test File");
		EmailAssert.assertThat(msg).hasOnlyRecipients(time2talk);
		EmailAssert.assertThat(msg).hasNoAttachments();
		assertThat(msg.getPlainText()).isNotEmpty();
		assertThat(normalizeNewlines(msg.getHTMLText())).isNotEmpty();
	}

	@Test
	public void testEmlWithQuotablePrintableCalendarAttachment()
			throws Exception {
		SecureTestDataHelper.runTestWithSecureTestData(new PasswordsConsumer() {
			@Override
			public void accept(final Properties passwords) {
				File file = new File(RESOURCES + "/secure-testdata/secure-testdata/calendar-quotable-printable-email/qp-calendar-multipart.eml");
				final Email email = EmailConverter.emlToEmail(file);
				assertThat(email.getCalendarMethod()).isEqualTo(CalendarMethod.REQUEST);
				assertThat(email.getCalendarText()).startsWith("BEGIN:VCALENDAR");
			}
		});
	}
}