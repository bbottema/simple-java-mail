package org.simplejavamail.converter;

import jakarta.mail.util.ByteArrayDataSource;
import org.apache.commons.codec.binary.Base64;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.Recipient;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;
import testutil.SecureTestDataHelper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static java.nio.charset.Charset.defaultCharset;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.ContentTransferEncoding.BIT7;
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
	public void testProblematicEmbeddedImage() {
		Email s1 = EmailConverter.emlToEmail(new File(RESOURCE_TEST_MESSAGES + "/#332 Email with problematic embedded image.eml"));
		assertThat(s1.getAttachments()).isEmpty();
		assertThat(s1.getEmbeddedImages()).extracting("name")
				.containsExactly("DB294AA3-160F-4825-923A-B16C8B674543@home");
		assertThat(s1.getHTMLText()).containsPattern("\"cid:DB294AA3-160F-4825-923A-B16C8B674543@home\"");
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
	public void testContentTransferEncodingQuotedPrintable() throws IOException {
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
	public void testContentTransferEncodingBase64() throws IOException {
		ConfigLoaderTestHelper.clearConfigProperties();

		final Email email = EmailHelper.createDummyEmailBuilder(true, true, false, false, false, false)
				.withContentTransferEncoding(ContentTransferEncoding.BASE_64).buildEmail();
		final String eml = normalizeNewlines(EmailConverter.emailToEML(email));
		final String emlRoundtrip = normalizeNewlines(EmailConverter.emailToEML(EmailConverter.emlToEmail(EmailConverter.emailToEML(email))));

		assertThat(eml).contains("Content-Transfer-Encoding: base64\n"
				+ "\n"
				+ new String(Base64.encodeBase64("We should meet up!".getBytes())));
		assertThat(eml).contains("Content-Transfer-Encoding: base64\n"
				+ "\n"
				+ new String(Base64.encodeBase64("<b>We should meet up!</b><img src='cid:thumbsup'>".getBytes())));

		assertThat(emlRoundtrip).contains("Content-Transfer-Encoding: base64\n"
				+ "\n"
				+ new String(Base64.encodeBase64("We should meet up!".getBytes())));
		assertThat(emlRoundtrip).contains("Content-Transfer-Encoding: base64\n"
				+ "\n"
				+ new String(Base64.encodeBase64("<b>We should meet up!</b><img src='cid:thumbsup'>".getBytes())));
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

		assertThat(eml).contains("Content-Type: text/plain; filename=\"dummy text1.txt\"; name=\"dummy text1.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text1.txt\"\n"
				+ "Content-ID: <dummy text1.txt>\n"
				+ "Content-Description: This is dummy text1\n"
				+ "\n"
				+ "Cupcake ipsum dolor sit amet donut. Apple pie caramels oat cake fruitcake sesame snaps. Bear claw cotton candy toffee danish sweet roll.");
		assertThat(eml).contains("Content-Type: text/plain; filename=\"dummy text2.txt\"; name=\"dummy text2.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text2.txt\"\n"
				+ "Content-ID: <dummy text2.txt>\n"
				+ "Content-Description: This is dummy text2\n"
				+ "\n"
				+ "I love pie I love donut sugar plum. I love halvah topping bonbon fruitcake brownie chocolate. Sweet tootsie roll wafer caramels sesame snaps.");
		assertThat(eml).contains("Content-Type: text/plain; filename=\"dummy text3.txt\"; name=\"dummy text3.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text3.txt\"\n"
				+ "Content-ID: <dummy text3.txt>\n"
				+ "Content-Description: This is dummy text3\n"
				+ "\n"
				+ "Danish chocolate pudding cake bonbon powder bonbon. I love cookie jelly beans cake oat cake. I love I love sweet roll sweet pudding topping icing.");
		assertThat(eml).contains("Content-Type: text/plain; filename=\"dummy text4.txt\"; name=\"dummy text4.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text4.txt\"\n"
				+ "Content-ID: <dummy text4.txt>\n"
				+ "\n"
				+ "this should not have a Content-Description header");

		// same assertions on the EML after converting to MimeMessage and back

		assertThat(emlRoundtrip).contains("Content-Type: text/plain; filename=\"dummy text1.txt\"; name=\"dummy text1.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text1.txt\"\n"
				+ "Content-ID: <dummy text1.txt>\n"
				+ "Content-Description: This is dummy text1\n"
				+ "\n"
				+ "Cupcake ipsum dolor sit amet donut. Apple pie caramels oat cake fruitcake sesame snaps. Bear claw cotton candy toffee danish sweet roll.");
		assertThat(emlRoundtrip).contains("Content-Type: text/plain; filename=\"dummy text2.txt\"; name=\"dummy text2.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text2.txt\"\n"
				+ "Content-ID: <dummy text2.txt>\n"
				+ "Content-Description: This is dummy text2\n"
				+ "\n"
				+ "I love pie I love donut sugar plum. I love halvah topping bonbon fruitcake brownie chocolate. Sweet tootsie roll wafer caramels sesame snaps.");
		assertThat(emlRoundtrip).contains("Content-Type: text/plain; filename=\"dummy text3.txt\"; name=\"dummy text3.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text3.txt\"\n"
				+ "Content-ID: <dummy text3.txt>\n"
				+ "Content-Description: This is dummy text3\n"
				+ "\n"
				+ "Danish chocolate pudding cake bonbon powder bonbon. I love cookie jelly beans cake oat cake. I love I love sweet roll sweet pudding topping icing.");
		assertThat(emlRoundtrip).contains("Content-Type: text/plain; filename=\"dummy text4.txt\"; name=\"dummy text4.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text4.txt\"\n"
				+ "Content-ID: <dummy text4.txt>\n"
				+ "\n"
				+ "this should not have a Content-Description header");
	}

	@NotNull
	private List<AttachmentResource> asList(AttachmentResource attachment) {
		List<AttachmentResource> collectionAttachment = new ArrayList<>();
		collectionAttachment.add(attachment);
		return collectionAttachment;
	}
}