package org.simplejavamail.converter;

import jakarta.mail.util.ByteArrayDataSource;
import org.assertj.core.api.Condition;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.Recipient;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;
import testutil.SecureTestDataHelper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static java.lang.String.format;
import static java.nio.charset.Charset.defaultCharset;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static java.util.regex.Pattern.compile;
import static org.apache.commons.codec.binary.Base64.encodeBase64Chunked;
import static org.assertj.core.api.Assertions.assertThat;
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
		assertThat(msg.getPlainText()).isNotEmpty();
		assertThat(normalizeNewlines(msg.getHTMLText())).isEqualTo("<div dir=\"auto\">Just a test to get an email with one cc recipient.</div>\n");
		assertThat(normalizeNewlines(msg.getPlainText())).isEqualTo("Just a test to get an email with one cc recipient.\n");
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
	public void testProblematicEmbeddedImage() {
		Email s1 = EmailConverter.emlToEmail(new File(RESOURCE_TEST_MESSAGES + "/#332 Email with problematic embedded image.eml"));
		assertThat(s1.getAttachments()).isEmpty();
		assertThat(s1.getEmbeddedImages()).extracting("name")
				.containsExactly("DB294AA3-160F-4825-923A-B16C8B674543@home");
		assertThat(s1.getHTMLText()).containsPattern("\"cid:DB294AA3-160F-4825-923A-B16C8B674543@home\"");
	}

	@Test
	public void testProblematicUmlautInDispositionNotificationTo() {
		Email s1 = EmailConverter.emlToEmail(new File(RESOURCE_TEST_MESSAGES + "/#500 Email with problematic umlaut in Disposition-Notification-To.eml"));
		EmailAssert.assertThat(s1).hasFromRecipient(new Recipient("Könok, Danny [Fake Company & Co. KG]", "test@fakedomain.de", null));
		EmailAssert.assertThat(s1).hasDispositionNotificationTo(new Recipient("Könok, Danny [Fake Company & Co. KG]", "test@fakedomain.de", null));
	}

	@Test
	public void testProblematic8BitContentTransferEncoding() {
		Email s1 = EmailConverter.emlToEmail(new File(RESOURCE_TEST_MESSAGES + "/#485 Email with 8Bit Content Transfer Encoding.eml"));
		EmailAssert.assertThat(s1).hasFromRecipient(new Recipient("TeleCash", "noreply@telecash.de", null, null));
		EmailAssert.assertThat(s1).hasOnlyRecipients(new Recipient(null, "abc@abcdefgh.de", TO, null));
	}

	@Test
	public void testProblematicCommasInRecipeints() {
		Email s1 = EmailConverter.emlToEmail(new File(RESOURCE_TEST_MESSAGES + "/#444 Email with encoded comma in recipients.eml"));
		EmailAssert.assertThat(s1).hasFromRecipient(new Recipient("Some Name, Jane Doe", "jane.doe@example.de", null, null));
		EmailAssert.assertThat(s1).hasOnlyRecipients(new Recipient("Some Name 2, John Doe", "john.doe@example.de", TO, null));
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

		assertThat(eml).contains("Content-ID: <dresscode.txt@" + contentIDExtractor(eml, "dresscode.txt") + ">");
		assertThat(eml).contains("Content-ID: <location.txt@" + contentIDExtractor(eml, "location.txt") + ">");
		assertThat(eml).contains("Content-ID: <thumbsup>");
		assertThat(eml).contains("Content-ID: <fixedNameWithoutFileExtensionForNamedAttachment@" + contentIDExtractor(eml, "fixedNameWithoutFileExtensionForNamedAttachment") + ".txt>");
		assertThat(eml).contains("Content-ID: <fixedNameWithoutFileExtensionForNamedEmbeddedImage>");
	}

	@NotNull
	private static String asBase64(String content) {
		return normalizeNewlines(new String(encodeBase64Chunked(content.getBytes(UTF_8)), UTF_8));
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
				+ "Content-ID: <dummy text1.txt@" + contentIDExtractor(eml, "dummy text1.txt") + ">\n"
				+ "Content-Description: This is dummy text1\n"
				+ "\n"
				+ "Cupcake ipsum dolor sit amet donut. Apple pie caramels oat cake fruitcake sesame snaps. Bear claw cotton candy toffee danish sweet roll.");
		assertThat(eml).contains("Content-Type: text/plain; filename=\"dummy text2.txt\"; name=\"dummy text2.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text2.txt\"\n"
				+ "Content-ID: <dummy text2.txt@" + contentIDExtractor(eml, "dummy text2.txt") + ">\n"
				+ "Content-Description: This is dummy text2\n"
				+ "\n"
				+ "I love pie I love donut sugar plum. I love halvah topping bonbon fruitcake brownie chocolate. Sweet tootsie roll wafer caramels sesame snaps.");
		assertThat(eml).contains("Content-Type: text/plain; filename=\"dummy text3.txt\"; name=\"dummy text3.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text3.txt\"\n"
				+ "Content-ID: <dummy text3.txt@" + contentIDExtractor(eml, "dummy text3.txt") + ">\n"
				+ "Content-Description: This is dummy text3\n"
				+ "\n"
				+ "Danish chocolate pudding cake bonbon powder bonbon. I love cookie jelly beans cake oat cake. I love I love sweet roll sweet pudding topping icing.");
		assertThat(eml).contains("Content-Type: text/plain; filename=\"dummy text4.txt\"; name=\"dummy text4.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text4.txt\"\n"
				+ "Content-ID: <dummy text4.txt@" + contentIDExtractor(eml, "dummy text4.txt") + ">\n"
				+ "\n"
				+ "this should not have a Content-Description header");

		// same assertions on the EML after converting to MimeMessage and back

		assertThat(emlRoundtrip).contains("Content-Type: text/plain; filename=\"dummy text1.txt\"; name=\"dummy text1.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text1.txt\"\n"
				+ "Content-ID: <dummy text1.txt@" + contentIDExtractor(emlRoundtrip, "dummy text1.txt") + ">\n"
				+ "Content-Description: This is dummy text1\n"
				+ "\n"
				+ "Cupcake ipsum dolor sit amet donut. Apple pie caramels oat cake fruitcake sesame snaps. Bear claw cotton candy toffee danish sweet roll.");
		assertThat(emlRoundtrip).contains("Content-Type: text/plain; filename=\"dummy text2.txt\"; name=\"dummy text2.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text2.txt\"\n"
				+ "Content-ID: <dummy text2.txt@" + contentIDExtractor(emlRoundtrip, "dummy text2.txt") + ">\n"
				+ "Content-Description: This is dummy text2\n"
				+ "\n"
				+ "I love pie I love donut sugar plum. I love halvah topping bonbon fruitcake brownie chocolate. Sweet tootsie roll wafer caramels sesame snaps.");
		assertThat(emlRoundtrip).contains("Content-Type: text/plain; filename=\"dummy text3.txt\"; name=\"dummy text3.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text3.txt\"\n"
				+ "Content-ID: <dummy text3.txt@" + contentIDExtractor(emlRoundtrip, "dummy text3.txt") + ">\n"
				+ "Content-Description: This is dummy text3\n"
				+ "\n"
				+ "Danish chocolate pudding cake bonbon powder bonbon. I love cookie jelly beans cake oat cake. I love I love sweet roll sweet pudding topping icing.");
		assertThat(emlRoundtrip).contains("Content-Type: text/plain; filename=\"dummy text4.txt\"; name=\"dummy text4.txt\"\n"
				+ "Content-Transfer-Encoding: 7bit\n"
				+ "Content-Disposition: attachment; filename=\"dummy text4.txt\"\n"
				+ "Content-ID: <dummy text4.txt@" + contentIDExtractor(emlRoundtrip, "dummy text4.txt") + ">\n"
				+ "\n"
				+ "this should not have a Content-Description header");
	}

	private static String contentIDExtractor(String eml, String filename) {
		final Matcher matcher = compile(format("Content-ID: <%s@(?<uuid>.+?)(?<optionalExtension>\\..{3})?>", filename)).matcher(eml);
		assertThat(matcher.find()).as(format("Found UUID in EML's Content-ID for filename '%s'", filename)).isTrue();
		return matcher.group("uuid");
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
				.extracting(r -> new Recipient(r.getName(), r.getAddress(), TO))
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

	@NotNull
	private List<AttachmentResource> asList(AttachmentResource attachment) {
		List<AttachmentResource> collectionAttachment = new ArrayList<>();
		collectionAttachment.add(attachment);
		return collectionAttachment;
	}
}
