package org.simplejavamail.converter;

import jakarta.mail.Session;
import jakarta.mail.util.ByteArrayDataSource;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Content;
import net.fortuna.ical4j.model.Property;
import org.assertj.core.api.Condition;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.email.EmailBuilder;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;
import testutil.SecureTestDataHelper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.TimeZone;
import java.util.regex.Matcher;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static java.lang.String.format;
import static java.nio.charset.Charset.defaultCharset;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static java.util.Calendar.JUNE;
import static java.util.regex.Pattern.compile;
import static org.apache.commons.codec.binary.Base64.encodeBase64Chunked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.ContentTransferEncoding.BASE_64;
import static org.simplejavamail.api.email.ContentTransferEncoding.BIT7;
import static org.simplejavamail.internal.util.MiscUtil.normalizeNewlines;

public class EmailConverterTest {

	private static final String RESOURCES = determineResourceFolder("simple-java-mail") + "/test/resources";
	private static final String RESOURCE_TEST_MESSAGES = RESOURCES + "/test-messages";

	@Test
	public void testOutlookBasicConversions() {
		final Recipient elias = new Recipient("Elias Laugher", "elias.laugher@gmail.com", null, null);
		final Recipient sven = new Recipient("Sven Sielenkemper", "sielenkemper@otris.de", TO, null);
		final Recipient niklas = new Recipient("niklas.lindson@gmail.com", "niklas.lindson@gmail.com", CC, null);

		@NotNull Email msg = EmailConverter.outlookMsgToEmail(new File(RESOURCE_TEST_MESSAGES + "/simple email with TO and CC.msg"));
		EmailAssert.assertThat(msg).hasFromRecipient(elias);
		EmailAssert.assertThat(msg).hasSubject("Test E-Mail");
		EmailAssert.assertThat(msg).hasOnlyRecipients(sven, niklas);
		EmailAssert.assertThat(msg).hasNoAttachments();
		assertThat(msg.getHeaders()).containsEntry("x-pmx-scanned", singletonList("Mail was scanned by Sophos Pure Message"));
		assertThat(msg.getHeaders()).doesNotContainKeys("CC", "Cc", "cc", "BCC", "Bcc", "bcc", "TO", "To", "to");
		assertThat(msg.getPlainText()).isNotEmpty();
		assertThat(normalizeNewlines(msg.getHTMLText())).isEqualTo("<div dir=\"auto\">Just a test to get an email with one cc recipient.</div>\n");
		assertThat(normalizeNewlines(msg.getPlainText())).isEqualTo("Just a test to get an email with one cc recipient.\n");
	}

	@Test
	public void testOutlookConversionWithOutlookDataExposesIgnoredSourceHeaders()
			throws IOException {
		final File msgFile = new File(RESOURCE_TEST_MESSAGES + "/simple email with TO and CC.msg");

		final OutlookEmailConversionResult result = EmailConverter.outlookMsgToEmailBuilderWithOutlookData(msgFile);
		final Email msg = result.buildEmail();

		assertThat(msg.getHeaders()).containsEntry("x-pmx-scanned", singletonList("Mail was scanned by Sophos Pure Message"));
		assertThat(msg.getHeaders()).doesNotContainKeys("CC", "Cc", "cc", "BCC", "Bcc", "bcc", "TO", "To", "to");
		assertThat(result.getOutlookMessageData().getHeaderValues("to")).isNotEmpty();
		assertThat(result.getOutlookMessageData().getHeaderValues("cc")).isNotEmpty();
		assertThat(result.getOutlookMessageData().getRawHeaders()).contains("To: \"Sven Sielenkemper\"", "niklas.lindson@gmail.com");

		try (InputStream inputStream = new FileInputStream(msgFile)) {
			final OutlookEmailConversionResult streamResult = EmailConverter.outlookMsgToEmailBuilderWithOutlookData(inputStream);
			assertThat(streamResult.getOutlookMessageData().getHeaderValues("to")).isNotEmpty();
		}
	}

	@Test
	public void testOutlookConversionWithOutlookDataExposesLastModifierNameGithubIssue645() {
		final File msgFile = new File(RESOURCE_TEST_MESSAGES + "/chinese message.msg");

		final OutlookEmailConversionResult result = EmailConverter.outlookMsgToEmailBuilderWithOutlookData(msgFile);
		final Email msg = result.buildEmail();

		assertThat(result.getOutlookMessageData().getLastModifierName()).isEqualTo("haozl@Ctrip.com");
		EmailAssert.assertThat(msg).hasFromRecipient(new Recipient(null, "donotreply@unknown-from-address.net", null, null));
	}

	@Test
	public void testOutlookBasicConversionsGithubIssue482() {
		final Recipient ramonFrom = new Recipient("Boss Ramon", "ramon.boss@mobi.ch", null, null);
		final Recipient ramonTo = new Recipient("Boss Ramon", "ramon.boss@mobi.ch", TO, null);

		@NotNull Email msg = EmailConverter.outlookMsgToEmail(new File(RESOURCE_TEST_MESSAGES + "/#482 emailAddressList_is_required.msg"));
		EmailAssert.assertThat(msg).hasFromRecipient(ramonFrom);
		EmailAssert.assertThat(msg).hasSubject("subj");
		EmailAssert.assertThat(msg).hasOnlyRecipients(ramonTo);
		EmailAssert.assertThat(msg).hasNoAttachments();
	}

	@Test
	public void testOutlookBasicConversionsGithubIssue484() {
		final Recipient ramonFrom = new Recipient("Boss Ramon", "ramon.boss@mobi.ch", null, null);
		final Recipient ramonTo = new Recipient("Boss Ramon", "ramon.boss@mobi.ch", TO, null);

		@NotNull Email msg = EmailConverter.outlookMsgToEmail(new File(RESOURCE_TEST_MESSAGES + "/#484 Email with problematic disposition_notification_to.msg"));
		EmailAssert.assertThat(msg).hasFromRecipient(ramonFrom);
		EmailAssert.assertThat(msg).hasSubject("subject");
		EmailAssert.assertThat(msg).hasOnlyRecipients(ramonTo);
		EmailAssert.assertThat(msg).hasDispositionNotificationTo(ramonFrom);
	}

	@Test
	public void testOutlookDuplicateRecipientBucketsGithubIssue504() {
		final Recipient to = new Recipient("Andrew McQuillen", "andrew.mcquillen@civica.co.uk", TO, null);

		final Email distinctNames = EmailConverter.outlookMsgToEmail(new File(RESOURCE_TEST_MESSAGES + "/#504 TestingCC.msg"));
		EmailAssert.assertThat(distinctNames).hasOnlyRecipients(
				to,
				new Recipient("test@example.com", "test@example.com", CC, null));

		final Email sameName = EmailConverter.outlookMsgToEmail(new File(RESOURCE_TEST_MESSAGES + "/#504 TestingCCSameName.msg"));
		EmailAssert.assertThat(sameName).hasOnlyRecipients(
				to,
				new Recipient("Andrew McQuillen", "atmcquillen@gmail.com", CC, null));
	}

	@Test
	public void testOutlookSentDateGithubIssue534() {
		final Date expectedSentDate = amsterdamDate(2024, JUNE, 4, 15, 31, 19);

		final Email original = EmailConverter.outlookMsgToEmail(new File(RESOURCE_TEST_MESSAGES + "/#534 test.msg"));
		assertThat(original.getSentDate()).isEqualTo(expectedSentDate);

		final Email corrected = EmailConverter.outlookMsgToEmail(new File(RESOURCE_TEST_MESSAGES + "/#534 test_corrected.msg"));
		assertThat(corrected.getSentDate()).isEqualTo(expectedSentDate);
	}

	@Test
	public void testOutlookRtfOnlyMessagesGithubIssue576() {
		assertConvertedRtfOnlyMessage("#576 RtfSampleEmail.msg", "BOOK ONE: 1805");
		assertConvertedRtfOnlyMessage("#576 RtfSampleEmailWithAttachment.msg", "This is a sample RTF email with an attachment");
	}

	@Test
	public void testOutlookPlainTextRtfDoesNotUsePreWrapperGithubIssue651() {
		final Email email = EmailConverter.outlookMsgToEmail(new File(RESOURCE_TEST_MESSAGES + "/#651 simple sent.msg"));

		assertThat(normalizeNewlines(email.getHTMLText()))
				.contains("<div style=\"white-space:pre-wrap\">")
				.contains("Dear BitDaddys Corp.")
				.contains("Sincerely,\nJohn Doe")
				.doesNotContain("<pre", "font-family: monospace");
	}

	@Test
	public void testOutlookUnicode() {
		final Recipient kalejs = new Recipient("m.kalejs@outlook.com", "m.kalejs@outlook.com", null, null);
		final Recipient dummy = new Recipient("doesnotexist@doesnt.com", "doesnotexist@doesnt.com", TO, null);

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
		final Recipient time2talk = new Recipient("time2talk@online-convert.com", "time2talk@online-convert.com", TO, null);

		@NotNull Email msg = EmailConverter.outlookMsgToEmail(new File(RESOURCE_TEST_MESSAGES + "/unsent draft.msg"));
		EmailAssert.assertThat(msg).hasFromRecipient(new Recipient(null, "donotreply@unknown-from-address.net", null, null));
		EmailAssert.assertThat(msg).hasSubject("MSG Test File");
		EmailAssert.assertThat(msg).hasOnlyRecipients(time2talk);
		EmailAssert.assertThat(msg).hasNoAttachments();
		assertThat(msg.getPlainText()).isNotEmpty();
		assertThat(normalizeNewlines(msg.getHTMLText())).isNotEmpty();
	}

	@Test
	public void testEmlWithQuotablePrintableCalendarAttachment()
			throws Exception {
		SecureTestDataHelper.runTestWithSecureTestData(passwords -> {
			File file = new File(RESOURCES + "/secure-testdata/secure-testdata/calendar-quotable-printable-email/qp-calendar-multipart.eml");
			final Email email = EmailConverter.emlToEmail(file);
			assertThat(email.getCalendarMethod()).isEqualTo(CalendarMethod.REQUEST);
			assertThat(email.getCalendarText()).startsWith("BEGIN:VCALENDAR");
		});
	}

	@Test
	public void testMimeMessageWithNestedMessages()
			throws Exception {
		SecureTestDataHelper.runTestWithSecureTestData(passwords -> {
			String fileNameMsg = RESOURCES + "/secure-testdata/secure-testdata/nested-mimemessages-without-name-email/4990344.eml";
			Email email = EmailConverter.emlToEmail(new File(fileNameMsg));
			assertThat(email.getAttachments()).extracting("name").containsExactly("ForwardedMessage.eml", "ForwardedMessage.eml");
		});
	}

	@Test
	public void testOutlookMessageWithNestedMessages()
			throws Exception {
		SecureTestDataHelper.runTestWithSecureTestData(passwords -> {
			String fileNameMsg = RESOURCES + "/secure-testdata/secure-testdata/nested-mimemessages-without-name-email/4990344.msg";
			Email email = EmailConverter.outlookMsgToEmail(new File(fileNameMsg));
			assertThat(email.getAttachments()).extracting("name").containsExactly("NDPB.eml", "Voicemail .eml");
		});
	}

	@Test
	public void testAttachmentSize() {
		Email email = EmailConverter.emlToEmail(new File(RESOURCE_TEST_MESSAGES + "/#349 Email with special attachment or something.eml"));
		assertThat(email.getAttachments()).hasSize(2);
		assertThat(email.getAttachments()).extracting("name").containsExactly("ForwardedMessage.eml", "ForwardedMessage.eml");
	}

	@Test
	public void testOutlookMessageWithEmptyAttachments() {
		Email s1 = EmailConverter.outlookMsgToEmail(new File(RESOURCE_TEST_MESSAGES + "/#318 Email with nodata-attachment.msg"));
		assertThat(s1.getAttachments()).extracting("name").containsExactlyInAnyOrder("ecblank.gif", "logo_imabenelux.jpg");
		Email s2 = EmailConverter.outlookMsgToEmail(new File(RESOURCE_TEST_MESSAGES + "/#318 Email with nodata-attachment2.msg"));
		assertThat(s2.getAttachments()).extracting("name").containsExactlyInAnyOrder("ETS Andre Glotz SA CP 1.doc");
	}

	@Test
	public void testOutlookMessageWithUndecryptableSmimeAttachment() {
		final Email email = EmailConverter.outlookMsgToEmail(new File(RESOURCE_TEST_MESSAGES + "/#572 Nullpointer in SMIMESupport.isMimeMessageAttachment.msg"));

		assertThat(email.getAttachments()).hasSize(1);
		assertThat(email.getAttachments()).extracting("name").containsExactly("smime.p7m");
		assertThat(email.getDecryptedAttachments()).hasSize(1);
		assertThat(email.getDecryptedAttachments()).extracting("name").containsExactly("smime.p7m");
		assertThat(email.getSmimeSignedEmail()).isNull();
	}

	@Test
	public void testProblematicEmbeddedImage() {
		Email s1 = EmailConverter.emlToEmail(new File(RESOURCE_TEST_MESSAGES + "/#332 Email with problematic embedded image.eml"));
		assertThat(s1.getAttachments()).isEmpty();
		assertThat(s1.getEmbeddedImages()).singleElement().satisfies(embeddedImage -> {
			assertThat(embeddedImage.getName()).isEqualTo("filename.png");
			assertThat(embeddedImage.getContentId()).isEqualTo("DB294AA3-160F-4825-923A-B16C8B674543@home");
		});
		assertThat(s1.getHTMLText()).containsPattern("\"cid:DB294AA3-160F-4825-923A-B16C8B674543@home\"");
	}

	@Test
	public void testProblematicUmlautInDispositionNotificationTo() {
		Email s1 = EmailConverter.emlToEmail(new File(RESOURCE_TEST_MESSAGES + "/#500 Email with problematic umlaut in Disposition-Notification-To.eml"));
		EmailAssert.assertThat(s1).hasFromRecipient(new Recipient("Könok, Danny [Fake Company & Co. KG]", "test@fakedomain.de", null, null));
		EmailAssert.assertThat(s1).hasDispositionNotificationTo(new Recipient("Könok, Danny [Fake Company & Co. KG]", "test@fakedomain.de", null, null));
	}

	@Test
	public void testProblematic8BitContentTransferEncoding() {
		Email s1 = EmailConverter.emlToEmail(new File(RESOURCE_TEST_MESSAGES + "/#485 Email with 8Bit Content Transfer Encoding.eml"));
		EmailAssert.assertThat(s1).hasFromRecipient(new Recipient("TeleCash", "noreply@telecash.de", null, null));
		EmailAssert.assertThat(s1).hasOnlyRecipients(new Recipient(null, "abc@abcdefgh.de", TO, null));
	}

	@Test
	public void testSmtpUtf8HeadersCanBeParsedWithCustomSession() {
		final String eml = "MIME-Version: 1.0\r\n" +
				"Message-ID: <1234@mail.gmail.com>\r\n" +
				"Subject: test\r\n" +
				"From: Test <tester@test.com.com>\r\n" +
				"To: Martín Mallea <martín@receiver.com>\r\n" +
				"Content-Type: multipart/alternative; boundary=\"0000000000004b527e05dbff4a78\"\r\n" +
				"\r\n" +
				"--0000000000004b527e05dbff4a78\r\n" +
				"Content-Type: text/plain; charset=\"UTF-8\"\r\n" +
				"\r\n" +
				"This is a test: ñ\r\n" +
				"\r\n" +
				"--0000000000004b527e05dbff4a78\r\n" +
				"Content-Type: text/html; charset=\"UTF-8\"\r\n" +
				"\r\n" +
				"<div dir=\"ltr\">This is a test: ñ</div>\r\n" +
				"\r\n" +
				"--0000000000004b527e05dbff4a78--\r\n";
		final Properties properties = new Properties();
		properties.put("mail.mime.allowutf8", "true");
		final Session smtpUtf8Session = Session.getInstance(properties);

		final Email email = EmailConverter.emlToEmail(new ByteArrayInputStream(eml.getBytes(UTF_8)), null, smtpUtf8Session);

		EmailAssert.assertThat(email).hasFromRecipient(new Recipient("Test", "tester@test.com.com", null, null));
		EmailAssert.assertThat(email).hasOnlyRecipients(new Recipient("Martín Mallea", "martín@receiver.com", TO, null));
		assertThat(email.getPlainText()).isEqualTo("This is a test: ñ\r\n");
	}

	@Test
	public void testProblematicCommasInRecipients() {
		Email s1 = EmailConverter.emlToEmail(new File(RESOURCE_TEST_MESSAGES + "/#444 Email with encoded comma in recipients.eml"));
		EmailAssert.assertThat(s1).hasFromRecipient(new Recipient("Some Name, Jane Doe", "jane.doe@example.de", null, null));
		EmailAssert.assertThat(s1).hasOnlyRecipients(new Recipient("Some Name 2, John Doe", "john.doe@example.de", TO, null));
	}

	@Test
	public void testProblematicCcHeader() {
		Email recipientsCamelcase = EmailConverter.emlToEmail(new File(RESOURCE_TEST_MESSAGES + "/#502 Recipients camelcase header.eml"));
		EmailAssert.assertThat(recipientsCamelcase).hasFromRecipient(new Recipient("from someone", "from@example.com", null, null));
		EmailAssert.assertThat(recipientsCamelcase).hasOnlyRecipients(
				new Recipient("to person", "to@example.com", TO, null),
				new Recipient("cc person", "cc@example.com", CC, null),
				new Recipient("bcc person", "bcc@example.com", BCC, null));
		EmailAssert.assertThat(recipientsCamelcase).hasHeaders(new HashMap<>());

		Email recipientsCapitals = EmailConverter.emlToEmail(new File(RESOURCE_TEST_MESSAGES + "/#502 Recipients capitals header.eml"));
		Email recipientsLowercase = EmailConverter.emlToEmail(new File(RESOURCE_TEST_MESSAGES + "/#502 Recipients lowercase header.eml"));

		assertThat(recipientsCapitals).isEqualTo(recipientsCamelcase);
		assertThat(recipientsLowercase).isEqualTo(recipientsCamelcase);
	}

	@Test
	public void testProblematicExchangeDeliveryReceipts() throws Exception {
		SecureTestDataHelper.runTestWithSecureTestData(passwords -> {
			String fileNameMsg = RESOURCES + "/secure-testdata/secure-testdata/nested-empty-outlook-msg/Outlook msg with empty nested msg attachment.msg";
			Email email = EmailConverter.outlookMsgToEmail(new File(fileNameMsg));
			EmailAssert.assertThat(email).hasNoAttachments();
			EmailAssert.assertThat(email).hasNoDecryptedAttachments();
		});
	}

	@Test
	public void testContentTransferEncodingQuotedPrintable() {
		ConfigLoaderTestHelper.clearConfigProperties();

		final Email email = EmailHelper.createDummyEmailBuilder(true, true, false, false, false, false).buildEmail();
		final String eml = normalizeNewlines(EmailConverter.emailToEML(email));
		final String emlRoundtrip = normalizeNewlines(EmailConverter.emailToEML(EmailConverter.emlToEmail(eml)));

		assertThat(normalizeNewlines(eml)).contains("Content-Transfer-Encoding: quoted-printable\n"
				+ "\n"
				+ "We should meet up!");
		assertThat(normalizeNewlines(eml)).contains("Content-Transfer-Encoding: quoted-printable\n"
				+ "\n"
				+ "<b>We should meet up!</b><img src=3D'cid:thumbsup'>");

		assertThat(normalizeNewlines(emlRoundtrip)).contains("Content-Transfer-Encoding: quoted-printable\n"
				+ "\n"
				+ "We should meet up!");
		assertThat(normalizeNewlines(emlRoundtrip)).contains("Content-Transfer-Encoding: quoted-printable\n"
				+ "\n"
				+ "<b>We should meet up!</b><img src=3D'cid:thumbsup'>");
	}

	@Test
	public void testContentTransferEncodingBase64() {
		ConfigLoaderTestHelper.clearConfigProperties();

		final Email email = EmailHelper.createDummyEmailBuilder(true, true, false, false, false, false)
				.withContentTransferEncoding(ContentTransferEncoding.BASE_64).buildEmail();
		final String eml = normalizeNewlines(EmailConverter.emailToEML(email));
		final String emlRoundtrip = normalizeNewlines(EmailConverter.emailToEML(EmailConverter.emlToEmail(EmailConverter.emailToEML(email))));

		assertThat(eml).contains("Content-Transfer-Encoding: base64\n\n"
				+ asBase64("We should meet up!"));
		assertThat(eml).contains("Content-Transfer-Encoding: base64\n\n"
				+ asBase64("<b>We should meet up!</b><img src='cid:thumbsup'><img src='cid:fixedNameWithoutFileExtensionForNamedEmbeddedImage'>"));

		assertThat(emlRoundtrip).contains("Content-Transfer-Encoding: base64\n\n"
				+ asBase64("We should meet up!"));
		assertThat(emlRoundtrip).contains("Content-Transfer-Encoding: base64\n\n"
				+ asBase64("<b>We should meet up!</b><img src='cid:thumbsup'><img src='cid:fixedNameWithoutFileExtensionForNamedEmbeddedImage'>"));

		assertThat(eml).contains("Content-ID: <" + contentIDExtractor(eml, "dresscode.txt") + ">");
		assertThat(eml).contains("Content-ID: <" + contentIDExtractor(eml, "location.txt") + ">");
		assertThat(eml).contains("Content-ID: <thumbsup>");
		assertThat(eml).contains("Content-ID: <" + contentIDExtractor(eml, "fixedNameWithoutFileExtensionForNamedAttachment.txt") + ">");
		assertThat(eml).contains("Content-ID: <fixedNameWithoutFileExtensionForNamedEmbeddedImage>");
	}

	@Test
	public void testGithub605_BodyPartContentTransferEncodingsAreIndependent() {
		ConfigLoaderTestHelper.clearConfigProperties();

		final String calendarText = "BEGIN:VCALENDAR\r\n"
				+ "METHOD:REQUEST\r\n"
				+ "BEGIN:VEVENT\r\n"
				+ "UID:body-cte-test\r\n"
				+ "DTSTAMP:20260703T090000Z\r\n"
				+ "DTSTART:20260703T100000Z\r\n"
				+ "DTEND:20260703T103000Z\r\n"
				+ "SUMMARY:Body CTE test\r\n"
				+ "END:VEVENT\r\n"
				+ "END:VCALENDAR";
		final Email email = EmailBuilder.startingBlank()
				.from("sender@example.com")
				.withRecipients(null, false, TO, "recipient@example.com")
				.withSubject("Body CTE")
				.withPlainText("plain body")
				.withHTMLText("<b>html body</b>")
				.withCalendarText(CalendarMethod.REQUEST, calendarText)
				.withContentTransferEncoding(BIT7)
				.withHTMLTextContentTransferEncoding(ContentTransferEncoding.BASE_64)
				.withCalendarTextContentTransferEncoding(ContentTransferEncoding.QUOTED_PRINTABLE)
				.buildEmail();

		final String eml = normalizeNewlines(EmailConverter.emailToEML(email));
		final Email roundtripEmail = EmailConverter.emlToEmail(eml);
		final String roundtripEml = normalizeNewlines(EmailConverter.emailToEML(roundtripEmail));

		assertContentTransferEncodingForContentType(eml, "text/plain", "7bit");
		assertContentTransferEncodingForContentType(eml, "text/html", "base64");
		assertContentTransferEncodingForContentType(eml, "text/calendar", "quoted-printable");
		assertThat(roundtripEmail.getContentTransferEncoding()).isEqualTo(BIT7);
		assertThat(roundtripEmail.getPlainTextContentTransferEncoding()).isNull();
		assertThat(roundtripEmail.getHTMLTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
		assertThat(roundtripEmail.getCalendarTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.QUOTED_PRINTABLE);
		assertContentTransferEncodingForContentType(roundtripEml, "text/plain", "7bit");
		assertContentTransferEncodingForContentType(roundtripEml, "text/html", "base64");
		assertContentTransferEncodingForContentType(roundtripEml, "text/calendar", "quoted-printable");
	}

	@Test
	public void testGithub566_AttachmentContentIdSurvivesRoundtrip() throws IOException {
		ConfigLoaderTestHelper.clearConfigProperties();

		final String customContentId = "custom-id-12345";
		final Email email = EmailBuilder.startingBlank()
				.from("sender@example.com")
				.withRecipients(null, false, TO, "recipient@example.com")
				.withSubject("Test Content-ID")
				.withPlainText("body")
				.withAttachment("file.pdf", new ByteArrayDataSource("pdf content", "application/pdf"), null, BIT7, customContentId)
				.buildEmail();

		final String eml = normalizeNewlines(EmailConverter.emailToEML(email));
		final Email roundtripEmail = EmailConverter.emlToEmail(eml);
		final String roundtripEml = normalizeNewlines(EmailConverter.emailToEML(roundtripEmail));

		assertThat(eml).contains("Content-ID: <" + customContentId + ">");
		assertThat(roundtripEmail.getAttachments()).singleElement().satisfies(attachment -> {
			assertThat(attachment.getName()).isEqualTo("file.pdf");
			assertThat(attachment.getContentId()).isEqualTo(customContentId);
		});
		assertThat(roundtripEml).contains("Content-ID: <" + customContentId + ">");
	}

	@Test
	public void testGithub597_EmbeddedImageContentIdCanDifferFromFilename() throws IOException {
		ConfigLoaderTestHelper.clearConfigProperties();

		final String customContentId = "logo-content-id";
		final Email email = EmailBuilder.startingBlank()
				.from("sender@example.com")
				.withRecipients(null, false, TO, "recipient@example.com")
				.withSubject("Embedded image")
				.withHTMLText("<img src=\"cid:" + customContentId + "\">")
				.withEmbeddedImage("logo.png", new ByteArrayDataSource("image content", "image/png"), customContentId)
				.buildEmail();

		final String eml = normalizeNewlines(EmailConverter.emailToEML(email));

		assertThat(eml).contains("Content-ID: <" + customContentId + ">");
		assertThat(eml).contains("Content-Type: image/png; name=logo.png");
		assertThat(eml).contains("Content-Disposition: inline; filename=logo.png");
		assertThat(eml).doesNotContain("filename=\"" + customContentId + "\"");
		assertThat(eml).contains("cid:" + customContentId);
	}

	@Test
	public void testGithub602_EmbeddedImageFilenameAndContentIdStaySeparateAfterParsing() {
		ConfigLoaderTestHelper.clearConfigProperties();

		final String contentId = "emf08a6e26-b330-4662-b8b1-5122ade7f2f2@2856f0a1.com";
		final String eml = normalizeNewlines("From: sender@example.com\n"
				+ "To: recipient@example.com\n"
				+ "Subject: Embedded image\n"
				+ "MIME-Version: 1.0\n"
				+ "Content-Type: multipart/related; boundary=\"related-boundary\"\n"
				+ "\n"
				+ "--related-boundary\n"
				+ "Content-Type: text/html; charset=UTF-8\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "\n"
				+ "<img src=\"cid:" + contentId + "\">\n"
				+ "--related-boundary\n"
				+ "Content-Type: image/png; name=ss.png\n"
				+ "Content-Transfer-Encoding: base64\n"
				+ "Content-Disposition: inline; filename=ss.png\n"
				+ "Content-ID: <" + contentId + ">\n"
				+ "\n"
				+ "aW1hZ2UgY29udGVudA==\n"
				+ "--related-boundary--");

		final Email email = EmailConverter.emlToEmail(eml);
		final String roundtripEml = normalizeNewlines(EmailConverter.emailToEML(email));

		assertThat(email.getEmbeddedImages()).singleElement().satisfies(embeddedImage -> {
			assertThat(embeddedImage.getName()).isEqualTo("ss.png");
			assertThat(embeddedImage.getContentId()).isEqualTo(contentId);
		});
		assertThat(roundtripEml).contains("Content-ID: <" + contentId + ">");
		assertThat(roundtripEml).contains("Content-Type: image/png; name=ss.png");
		assertThat(roundtripEml).contains("Content-Disposition: inline; filename=ss.png");
		assertThat(roundtripEml).doesNotContain("filename=\"" + contentId + "\"");
	}

	@Test
	public void testGithub607_GeneratedAttachmentContentIdIsValidEvenWhenFilenameContainsSpecialCharacters() throws IOException {
		ConfigLoaderTestHelper.clearConfigProperties();

		final String filename = "Attachment %^$(()_()&^&^^:@/\\|{}[]#~`- special chars.txt";
		final Email email = EmailBuilder.startingBlank()
				.from("sender@example.com")
				.withRecipients(null, false, TO, "recipient@example.com")
				.withSubject("Attachment")
				.withPlainText("body")
				.withAttachment(filename, new ByteArrayDataSource("Attachment with special chars", "text/plain"))
				.buildEmail();

		final String eml = normalizeNewlines(EmailConverter.emailToEML(email));
		final Matcher matcher = compile("Content-ID: <(?<contentId>[^>]+)>").matcher(eml);

		assertThat(matcher.find()).as("Content-ID header exists").isTrue();
		assertThat(matcher.group("contentId"))
				.isNotEqualTo(filename)
				.doesNotContain(" ")
				.doesNotContain("[")
				.doesNotContain("]")
				.doesNotContain("\\")
				.matches("[A-Za-z0-9!#$%&'*+\\-/=?^_`{|}~.]+@[A-Za-z0-9.-]+");
		assertThat(eml).contains("filename=\"Attachment %^$(()_()&^&^^:@/\\\\|{}[]#~`- special chars.txt\"");
	}

	@Test
	public void testGithub606_AttachmentContentTypeIsNormalizedBeforeSerializing()
			throws IOException {
		ConfigLoaderTestHelper.clearConfigProperties();

		final Email email = EmailBuilder.startingBlank()
				.from("sender@example.com")
				.withRecipients(null, false, TO, "recipient@example.com")
				.withSubject("Nested email attachment")
				.withPlainText("body")
				.withAttachment("nested.eml", new ByteArrayDataSource("payload", "message/rfc822\r\nX-Bad: yes"))
				.buildEmail();

		final String eml = normalizeNewlines(EmailConverter.emailToEML(email));

		assertThat(extractResourceHeaderBlock(eml, "nested.eml"))
				.contains("Content-Type: message/rfc822;")
				.contains("Content-Disposition: attachment; filename=nested.eml")
				.doesNotContain("X-Bad");
	}

	@Test
	public void testGithub606_InvalidEmbeddedImageContentTypeFallsBackBeforeSerializing()
			throws IOException {
		ConfigLoaderTestHelper.clearConfigProperties();

		final Email email = EmailBuilder.startingBlank()
				.from("sender@example.com")
				.withRecipients(null, false, TO, "recipient@example.com")
				.withSubject("Embedded image")
				.withHTMLText("<img src=\"cid:logo\">")
				.withEmbeddedImage("logo", new ByteArrayDataSource("image content", " "))
				.buildEmail();

		final String eml = normalizeNewlines(EmailConverter.emailToEML(email));

		assertThat(extractResourceHeaderBlock(eml, "logo"))
				.contains("Content-Type: application/octet-stream;")
				.contains("Content-Disposition: inline; filename=logo");
	}

	@NotNull
	private static String asBase64(String content) {
		return normalizeNewlines(new String(encodeBase64Chunked(content.getBytes(UTF_8)), UTF_8));
	}

	private static void assertContentTransferEncodingForContentType(String eml, String contentType, String contentTransferEncoding) {
		final int contentTypeIndex = eml.indexOf("Content-Type: " + contentType);
		assertThat(contentTypeIndex).as("Content-Type header for %s", contentType).isNotEqualTo(-1);
		final int headerEndIndex = eml.indexOf("\n\n", contentTypeIndex);
		assertThat(headerEndIndex).as("header block end for %s", contentType).isNotEqualTo(-1);
		assertThat(eml.substring(contentTypeIndex, headerEndIndex))
				.contains("Content-Transfer-Encoding: " + contentTransferEncoding);
	}

	@Test
	public void testPreEncodedAttachmentIsNotReencoded() {
		ConfigLoaderTestHelper.clearConfigProperties();

		final String rawAttachment = "The stored database payload is already base64 encoded.";
		final String encodedAttachment = asBase64(rawAttachment);
		final String reencodedAttachment = asBase64(encodedAttachment);

		final Email email = EmailBuilder.startingBlank()
				.from("sender@example.com")
				.withRecipients(null, false, TO, "receiver@example.com")
				.withPlainText("See attachment")
				.withPreEncodedAttachment("preencoded.txt", encodedAttachment.getBytes(UTF_8), "text/plain", BASE_64)
				.buildEmail();

		final String eml = normalizeNewlines(EmailConverter.emailToEML(email));

		assertContentTransferEncodingForResource(eml, "preencoded.txt", "base64");
		assertThat(extractResourceBody(eml, "preencoded.txt")).isEqualTo(encodedAttachment.trim());
		assertThat(eml).doesNotContain(reencodedAttachment.trim());
	}

	@Test
	public void testPreEncodedEmbeddedImageIsNotReencoded() {
		ConfigLoaderTestHelper.clearConfigProperties();

		final String rawImage = "fake image bytes already stored as base64";
		final String encodedImage = asBase64(rawImage);
		final String reencodedImage = asBase64(encodedImage);

		final Email email = EmailBuilder.startingBlank()
				.from("sender@example.com")
				.withRecipients(null, false, TO, "receiver@example.com")
				.withHTMLText("<img src='cid:logo'>")
				.withPreEncodedEmbeddedImage("logo", encodedImage.getBytes(UTF_8), "image/png", BASE_64)
				.buildEmail();

		final String eml = normalizeNewlines(EmailConverter.emailToEML(email));

		assertContentTransferEncodingForResource(eml, "logo", "base64");
		assertThat(extractResourceBody(eml, "logo")).isEqualTo(encodedImage.trim());
		assertThat(eml).doesNotContain(reencodedImage.trim());
	}

	@Test
	public void testContentDescriptionAndContentTransferEncoding() throws IOException {
		ConfigLoaderTestHelper.clearConfigProperties();

		String dummyAttachment1 = "Cupcake ipsum dolor sit amet donut. Apple pie caramels oat cake fruitcake sesame snaps. Bear claw cotton candy toffee danish sweet roll.";
		String dummyAttachment2 = "I love pie I love donut sugar plum. I love halvah topping bonbon fruitcake brownie chocolate. Sweet tootsie roll wafer caramels sesame snaps.";
		String dummyAttachment3 = "Danish chocolate pudding cake bonbon powder bonbon. I love cookie jelly beans cake oat cake. I love I love sweet roll sweet pudding topping icing.";

		final Email email = EmailHelper.createDummyEmailBuilder(true, true, false, false, false, false)
				.clearAttachments()
				.withAttachment("dummy text1.txt", dummyAttachment1.getBytes(defaultCharset()), "text/plain", "This is dummy text1", BIT7)
				.withAttachment("dummy text2.txt", new ByteArrayDataSource(dummyAttachment2, "text/plain"), "This is dummy text2", BIT7)
				.withAttachments(asList(new AttachmentResource("dummy text3.txt", new ByteArrayDataSource(dummyAttachment3, "text/plain"), "This is dummy text3", BIT7)))
				.withAttachment("dummy text4.txt", new ByteArrayDataSource("this should not have a Content-Description header", "text/plain"), null, BIT7)
				.buildEmail();

		final String eml = normalizeNewlines(EmailConverter.emailToEML(email));
		final String emlRoundtrip = normalizeNewlines(EmailConverter.emailToEML(EmailConverter.emlToEmail(EmailConverter.emailToEML(email))));

		assertThat(eml).contains("Content-Type: text/plain; name=\"dummy text1.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text1.txt\"\n"
				+ "Content-ID: <" + contentIDExtractor(eml, "dummy text1.txt") + ">\n"
				+ "Content-Description: This is dummy text1\n"
				+ "\n"
				+ "Cupcake ipsum dolor sit amet donut. Apple pie caramels oat cake fruitcake sesame snaps. Bear claw cotton candy toffee danish sweet roll.");
		assertThat(eml).contains("Content-Type: text/plain; name=\"dummy text2.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text2.txt\"\n"
				+ "Content-ID: <" + contentIDExtractor(eml, "dummy text2.txt") + ">\n"
				+ "Content-Description: This is dummy text2\n"
				+ "\n"
				+ "I love pie I love donut sugar plum. I love halvah topping bonbon fruitcake brownie chocolate. Sweet tootsie roll wafer caramels sesame snaps.");
		assertThat(eml).contains("Content-Type: text/plain; name=\"dummy text3.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text3.txt\"\n"
				+ "Content-ID: <" + contentIDExtractor(eml, "dummy text3.txt") + ">\n"
				+ "Content-Description: This is dummy text3\n"
				+ "\n"
				+ "Danish chocolate pudding cake bonbon powder bonbon. I love cookie jelly beans cake oat cake. I love I love sweet roll sweet pudding topping icing.");
		assertThat(eml).contains("Content-Type: text/plain; name=\"dummy text4.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text4.txt\"\n"
				+ "Content-ID: <" + contentIDExtractor(eml, "dummy text4.txt") + ">\n"
				+ "\n"
				+ "this should not have a Content-Description header");

		// same assertions on the EML after converting to MimeMessage and back

		assertThat(emlRoundtrip).contains("Content-Type: text/plain; name=\"dummy text1.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text1.txt\"\n"
				+ "Content-ID: <" + contentIDExtractor(emlRoundtrip, "dummy text1.txt") + ">\n"
				+ "Content-Description: This is dummy text1\n"
				+ "\n"
				+ "Cupcake ipsum dolor sit amet donut. Apple pie caramels oat cake fruitcake sesame snaps. Bear claw cotton candy toffee danish sweet roll.");
		assertThat(emlRoundtrip).contains("Content-Type: text/plain; name=\"dummy text2.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text2.txt\"\n"
				+ "Content-ID: <" + contentIDExtractor(emlRoundtrip, "dummy text2.txt") + ">\n"
				+ "Content-Description: This is dummy text2\n"
				+ "\n"
				+ "I love pie I love donut sugar plum. I love halvah topping bonbon fruitcake brownie chocolate. Sweet tootsie roll wafer caramels sesame snaps.");
		assertThat(emlRoundtrip).contains("Content-Type: text/plain; name=\"dummy text3.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text3.txt\"\n"
				+ "Content-ID: <" + contentIDExtractor(emlRoundtrip, "dummy text3.txt") + ">\n"
				+ "Content-Description: This is dummy text3\n"
				+ "\n"
				+ "Danish chocolate pudding cake bonbon powder bonbon. I love cookie jelly beans cake oat cake. I love I love sweet roll sweet pudding topping icing.");
		assertThat(emlRoundtrip).contains("Content-Type: text/plain; name=\"dummy text4.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text4.txt\"\n"
				+ "Content-ID: <" + contentIDExtractor(emlRoundtrip, "dummy text4.txt") + ">\n"
				+ "\n"
				+ "this should not have a Content-Description header");
	}

	private static String contentIDExtractor(String eml, String filename) {
		final Matcher matcher = compile(format("Content-Disposition: attachment;[\\s\\S]*?filename=\"?%s\"?[\\s\\S]*?Content-ID: <(?<uuid>[^>]+)>", java.util.regex.Pattern.quote(filename))).matcher(eml);
		assertThat(matcher.find()).as(format("Found UUID in EML's Content-ID for filename '%s'", filename)).isTrue();
		assertThat(matcher.group("uuid")).matches("sjm-[A-Za-z0-9-]+@simplejavamail\\.generated");
		return matcher.group("uuid");
	}

	private static Date amsterdamDate(int year, int month, int dayOfMonth, int hourOfDay, int minute, int second) {
		final GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("Europe/Amsterdam"));
		calendar.set(year, month, dayOfMonth, hourOfDay, minute, second);
		calendar.set(GregorianCalendar.MILLISECOND, 0);
		return calendar.getTime();
	}

	private static void assertConvertedRtfOnlyMessage(String fileName, String expectedHtmlContent) {
		final Email email = EmailConverter.outlookMsgToEmail(new File(RESOURCE_TEST_MESSAGES + "/" + fileName));
		assertThat(email.getHTMLText())
				.contains(expectedHtmlContent)
				.doesNotContain("{\\rtf", "\\fromtext", "\\pard");
		assertThat(email.getPlainText()).isNotEmpty();
	}

	private static String extractResourceBody(String eml, String filename) {
		final Matcher matcher = compile(format("filename=\"?%s\"?[\\s\\S]*?\\n\\n(?<body>[\\s\\S]*?)\\n--", java.util.regex.Pattern.quote(filename))).matcher(eml);
		assertThat(matcher.find()).as("MIME body for resource %s", filename).isTrue();
		return matcher.group("body").trim();
	}

	private static String extractResourceHeaderBlock(String eml, String filename) {
		final Matcher matcher = compile(format("Content-Type: (?:(?!\\n\\n)[\\s\\S])*?Content-Disposition: (?:attachment|inline); filename=\"?%s\"?(?:(?!\\n\\n)[\\s\\S])*?\\n\\n", java.util.regex.Pattern.quote(filename))).matcher(eml);
		assertThat(matcher.find()).as("MIME header block for resource %s", filename).isTrue();
		return matcher.group();
	}

	private static void assertContentTransferEncodingForResource(String eml, String filename, String contentTransferEncoding) {
		assertThat(extractResourceHeaderBlock(eml, filename)).contains("Content-Transfer-Encoding: " + contentTransferEncoding);
	}

	@Test
	public void testGithub486_InvalidSignedOutlookMessage() {
		Email emailMime = EmailConverter.emlToEmail(new File(RESOURCE_TEST_MESSAGES + "/#486 TestValidSignedMimeMessage.eml"));
		Email emailOutlook = EmailConverter.outlookMsgToEmail(new File(RESOURCE_TEST_MESSAGES + "/#486 TestInvalidSignedOutlookMessage.msg"));

		assertThat(emailMime.getEmbeddedImages()).areExactly(1, new Condition<>(at -> at.getName().contains(".jpg"), null));
		assertThat(emailMime.getAttachments()).areExactly(2, new Condition<>(at -> at.getName().contains(".jpg"), null));

		assertThat(emailMime.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.SIGNED);
		assertThat(emailOutlook.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.SIGNED);

		assertThat(emailOutlook.getFromRecipient()).isEqualTo(emailMime.getFromRecipient());
		assertThat(emailOutlook.getId()).isEqualTo(emailMime.getId());
		assertThat(emailOutlook.getSentDate()).isEqualTo(emailMime.getSentDate());
		assertThat(emailOutlook.getBounceToRecipient()).isEqualTo(emailMime.getBounceToRecipient());
		assertThat(normalizeNewlines(emailOutlook.getPlainText())).isEqualTo(normalizeNewlines(emailMime.getPlainText()));
		assertThat(emailOutlook.getCalendarText()).isEqualTo(emailMime.getCalendarText());
		assertThat(emailOutlook.getCalendarMethod()).isEqualTo(emailMime.getCalendarMethod());
		assertThat(normalizeNewlines(emailOutlook.getHTMLText())).isEqualTo(normalizeNewlines(emailMime.getHTMLText()));
		assertThat(emailOutlook.getSubject()).isEqualTo(emailMime.getSubject());
		assertThat(emailOutlook.getRecipients())
				.extracting(r -> new Recipient(r.getName(), r.getAddress(), TO, null))
				.containsExactlyElementsOf(emailMime.getRecipients());
		assertThat(emailOutlook.getOverrideReceivers()).containsExactlyElementsOf(emailMime.getOverrideReceivers());
		assertThat(emailOutlook.getEmbeddedImages()).containsExactlyElementsOf(emailMime.getEmbeddedImages());
		assertThat(emailOutlook.getUseDispositionNotificationTo()).isEqualTo(emailMime.getUseDispositionNotificationTo());
		assertThat(emailOutlook.getUseReturnReceiptTo()).isEqualTo(emailMime.getUseReturnReceiptTo());
		assertThat(emailOutlook.getDispositionNotificationTo()).isEqualTo(emailMime.getDispositionNotificationTo());
		assertThat(emailOutlook.getSmimeSigningConfig()).isEqualTo(emailMime.getSmimeSigningConfig());
		assertThat(emailOutlook.getSmimeEncryptionConfig()).isEqualTo(emailMime.getSmimeEncryptionConfig());
		assertThat(emailOutlook.getReturnReceiptTo()).isEqualTo(emailMime.getReturnReceiptTo());
	}

	@Test
	public void testGithub491_EmailWithMultiPurposeAttachments() {
		Email emailMime = EmailConverter.emlToEmail(new File(RESOURCE_TEST_MESSAGES + "/#491 Email with dual purpose datasources.eml"));

		assertThat(emailMime.getEmbeddedImages()).satisfiesExactly(
				at -> {
					at.getName().equals("ii_lrkua30a0");
					at.getDataSource().getName().equals("doclife.jpg");
				});
		assertThat(emailMime.getAttachments()).satisfiesExactlyInAnyOrder(
				at -> at.getName().equals("Il Viaggio delle Ombre.pdf"),
				at -> at.getName().equals("Nyan Cat! [Official]-(480p).mp4"),
				at -> at.getName().equals("doclife.jpg"));
	}

	@Test
	public void testGithub551_ContentTransferEncodingEndsWithSpaceBug() {
		Email emailMime = EmailConverter.emlToEmail(new File(RESOURCE_TEST_MESSAGES + "/#551 Email with extra space in Content-Transfer-Encoding.eml"));

		assertThat(emailMime.getContentTransferEncoding()).isEqualTo(BIT7);
	}

	@Test
	public void testGithub552_BrokenCalendarMethod() throws ParserException, IOException {
		Email emailMime = EmailConverter.emlToEmail(new File(RESOURCE_TEST_MESSAGES + "/#552 broken calendar method.eml"));

		assertThat(emailMime.getCalendarMethod()).isEqualTo(CalendarMethod.REQUEST);
		assertThat(emailMime.getCalendarText()).isNotEmpty();

        Calendar calendar = new CalendarBuilder()
				.build(new StringReader(emailMime.getCalendarText()));

		assertThat(getPropertyValue(calendar, "SUMMARY")).contains("TestYandex");
		assertThat(getPropertyValue(calendar, "DTSTART")).contains("20240813T170000");
		assertThat(getPropertyValue(calendar, "DTEND")).contains("20240813T173000");
		assertThat(getPropertyValue(calendar, "UID")).contains("141zhi60x8914s7bzxzq27i0syandex.ru");
		assertThat(getPropertyValue(calendar, "SEQUENCE")).contains("0");
		assertThat(getPropertyValue(calendar, "DTSTAMP")).contains("20240813T135030Z");
		assertThat(getPropertyValue(calendar, "CREATED")).contains("20240813T135030Z");
		assertThat(getPropertyValue(calendar, "LAST-MODIFIED")).contains("20240813T135030Z");
		assertThat(getPropertyValue(calendar, "ORGANIZER"))
				.hasValueSatisfying(org -> assertThat(org).contains("mailto:"))
				.hasValueSatisfying(org -> assertThat(org).contains("ipopov"));
		assertThat(calendar.getComponent("VEVENT")
				.map(e -> e.getProperties("ATTENDEE")))
				.hasValueSatisfying(
						attendees -> assertThat(attendees).satisfiesExactlyInAnyOrder(
								attendeeProp -> assertThat(attendeeProp.getValue()).satisfies(attendee -> {
									assertThat(attendee).contains("mailto:");
									assertThat(attendee).contains("ipopov");
								}),
								attendeeProp -> assertThat(attendeeProp.getValue()).satisfies(attendee -> {
									assertThat(attendee).contains("mailto:");
									assertThat(attendee).contains("skyvv1sp");
								})
						)
				);
		assertThat(getPropertyValue(calendar, "URL")).contains("https://calendar.yandex.ru/event?event_id=2182739972");
		assertThat(getPropertyValue(calendar, "TRANSP")).contains("OPAQUE");
		assertThat(getPropertyValue(calendar, "CATEGORIES")).contains("Мои события");
		assertThat(getPropertyValue(calendar, "CLASS")).contains("PRIVATE");
		assertThat(getPropertyValue(calendar, "DESCRIPTION")).contains("");
		assertThat(getPropertyValue(calendar, "LOCATION")).contains("");
	}

	private static @NotNull Optional<String> getPropertyValue(Calendar calendar, String propertyName) {
        return calendar
                .getComponent("VEVENT")
                .<Property>flatMap(e -> e.getProperty(propertyName))
				.map(Property::getValue);
	}

	@NotNull
	private List<AttachmentResource> asList(AttachmentResource attachment) {
		List<AttachmentResource> collectionAttachment = new ArrayList<>();
		collectionAttachment.add(attachment);
		return collectionAttachment;
	}
}
