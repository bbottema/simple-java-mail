package org.simplejavamail.mailer;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.internal.smimesupport.model.PlainSmimeDetails;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.EmailTooBigException;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.email.internal.InternalEmail;
import org.simplejavamail.email.internal.InternalEmailPopulatingBuilder;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;
import org.simplejavamail.util.TestDataHelper;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;
import testutil.testrules.MimeMessageAndEnvelope;
import testutil.testrules.SmtpServerExtension;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static jakarta.mail.Message.RecipientType.TO;
import static java.lang.String.format;
import static java.util.Collections.singletonList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.MapEntry.entry;
import static org.simplejavamail.api.email.ContentTransferEncoding.BIT7;
import static org.simplejavamail.converter.EmailConverter.mimeMessageToEmail;
import static org.simplejavamail.converter.EmailConverter.mimeMessageToEmailBuilder;
import static org.simplejavamail.internal.util.MiscUtil.normalizeNewlines;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnullOrEmpty;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;
import static testutil.EmailHelper.readOutlookMessage;

/*
 * This class name is referrenced in pom as an exclusion for a profile that is only active during
 * remote builds (so excluded from tests in CircleCI).
 */
@SuppressWarnings("unused")
public class MailerLiveTest {

	private static final String RESOURCES = determineResourceFolder("simple-java-mail") + "/test/resources";

	private static final String RESOURCE_TEST_MESSAGES = RESOURCES + "/test-messages";

	private static final String RESOURCES_PKCS = RESOURCES + "/pkcs12";

	private static final Integer SERVER_PORT = 251;
	
	private static final String USERNAME = "usey";
	private static final String PASSWORD = "passy";

	@RegisterExtension
	static SmtpServerExtension smtpServerExtension = new SmtpServerExtension(SERVER_PORT, "usey", "passy");


	private Mailer mailer;

	// FIXME the builder should be reusable, but it fails this test when resused as a (static) field instance
	private static EmailPopulatingBuilder EMAIL_DEFAULTS() {
		return EmailBuilder.startingBlank()
			.withHeader("governanceOverrideTest1", "ignored")
			.withHeader("governanceDefaultTest1", "defaulted");
	}
	private static EmailPopulatingBuilder EMAIL_OVERRIDES() {
		return EmailBuilder.startingBlank()
				.withHeader("governanceOverrideTest1", "overridden") // overrides header from defaults-mail
				.withHeader("governanceOverrideTest2", "also overridden"); // overrides header from in-mail
	}

	@BeforeEach
	public void setup() {
		ConfigLoaderTestHelper.clearConfigProperties();
		mailer = MailerBuilder.withSMTPServer("localhost", SERVER_PORT, USERNAME, PASSWORD)
				.withEmailDefaults(EMAIL_DEFAULTS().buildEmail())
				.withEmailOverrides(EMAIL_OVERRIDES().buildEmail())
				.buildMailer();
	}
	
	@Test
	public void createMailSession_EmptySubjectAndBody()
			throws MessagingException, ExecutionException, InterruptedException {
		assertSendingEmail(EmailHelper.createDummyEmailBuilder(true, true, false, true, false, false), true, false, false, false, false);
	}

	@Test
	public void createMailSession_TestOverrideReceivers()
			throws MessagingException, ExecutionException, InterruptedException {
        val dummyEmailBuilder = EmailHelper.createDummyEmailBuilder(true, true, false, true, false, false)
				.withOverrideReceivers(new Recipient("override", "override@override.com", null));
		assertSendingEmail(dummyEmailBuilder, true, false, false, false, false);
	}

	@Test
	public void createMailSession_StandardDummyMailBasicFields()
			throws MessagingException, ExecutionException, InterruptedException {
		assertSendingEmail(EmailHelper.createDummyEmailBuilder(true, true, false, true, false, false), true, false, false, false, false);
	}

	@Test
	public void createMailSession_StandardDummyMailBasicFields_Async()
			throws MessagingException, ExecutionException, InterruptedException {
		assertSendingEmail(EmailHelper.createDummyEmailBuilder(true, true, false, true, false, false), true, false, true, false, false);
	}

	@Test
	public void createMailSession_StandardDummyMail_AllFields()
			throws MessagingException, ExecutionException, InterruptedException {
		assertSendingEmail(EmailHelper.createDummyEmailBuilder(true, false, false, true, true, true), true, false, false, false, true);
	}
	
	@Test
	public void createMailSession_StandardDummyMail_IncludingCustomHeaders()
			throws MessagingException, ExecutionException, InterruptedException {
		assertSendingEmail(EmailHelper.createDummyEmailBuilder(true, false, true, true, false, false), true, false, false, false, false);
	}

	@Test
	public void createMailSession_StandardDummyMailWithIdAndSendDate()
			throws MessagingException, ExecutionException, InterruptedException {
		assertSendingEmail(EmailHelper.createDummyEmailBuilder("<123@456>", true, false, false, true, true, false, false), true, false, false, true, false);
	}

	@Test
	public void createMailSession_OutlookMessageTest()
			throws IOException, MessagingException, ExecutionException, InterruptedException {
		val builder = readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg");
		Email email = assertSendingEmail(builder, false, false, false, true, false);
		verifyReceivedOutlookEmail(email, false, false);
	}

	@Test
	public void createMailSession_OutlookMessageSmimeSignTest()
			throws IOException, MessagingException, ExecutionException, InterruptedException {
		EmailPopulatingBuilder builder = readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg")
				.signWithSmime(new File(RESOURCES_PKCS + "/smime_keystore.pkcs12"), "letmein", "smime_test_user_alias_rsa", "letmein", null);
		Email email = assertSendingEmail(builder, false, true, false, true, false);
		verifyReceivedOutlookEmail(email, true, false);

		//noinspection deprecation
		assertThat(((InternalEmail) email).wasMergedWithSmimeSignedMessage()).isFalse();

		EmailAssert.assertThat(email).hasOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder()
				.smimeMode(SmimeMode.SIGNED)
				.smimeMime("multipart/signed")
				.smimeProtocol("application/pkcs7-signature")
				.smimeMicalg("sha-256")
				.smimeSignedBy("Benny Bottema")
				.smimeSignatureValid(true)
				.build());
	}

	@Test
	public void createMailSession_OutlookMessageSmimeSignTest_AlternativeSignatureAlgorithm()
			throws IOException, MessagingException, ExecutionException, InterruptedException {
		EmailPopulatingBuilder builder = readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg")
				.signWithSmime(new File(RESOURCES_PKCS + "/smime_keystore.pkcs12"), "letmein", "smime_test_user_alias_dsa", "letmein",  "SHA384withDSA");
		Email email = assertSendingEmail(builder, false, true, false, true, false);
		verifyReceivedOutlookEmail(email, true, false);

		//noinspection deprecation
		assertThat(((InternalEmail) email).wasMergedWithSmimeSignedMessage()).isFalse();

		EmailAssert.assertThat(email).hasOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder()
				.smimeMode(SmimeMode.SIGNED)
				.smimeMime("multipart/signed")
				.smimeProtocol("application/pkcs7-signature")
				.smimeMicalg("sha-384")
				.smimeSignedBy("Benny Bottema")
				.smimeSignatureValid(true)
				.build());
	}

	@Test
	public void createMailSession_OutlookMessageDefaultSmimeSignTest()
			throws IOException, MessagingException, ExecutionException, InterruptedException {
		// override the default from the @BeforeEach test
		mailer = MailerBuilder
				.withSMTPServer("localhost", SERVER_PORT, USERNAME, PASSWORD)
				.withEmailDefaults(EMAIL_DEFAULTS()
						.signWithSmime(new File(RESOURCES_PKCS + "/smime_keystore.pkcs12"), "letmein", "smime_test_user_alias_rsa", "letmein", null)
						.buildEmail())
				.withEmailOverrides(EMAIL_OVERRIDES()
						.buildEmail())
				.buildMailer();

		EmailPopulatingBuilder builder = readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg");
		Email email = assertSendingEmail(builder, false, true, false, true, false);

		// verify that S/MIME was indeed only configured on the mailer instance
		assertThat(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(email).getSmimeSigningConfig()).isNotNull();
		assertThat(builder.getSmimeSigningConfig()).isNull();
		assertThat(email.getSmimeEncryptionConfig()).isNull();

		verifyReceivedOutlookEmail(email, true, false);

		//noinspection deprecation
		assertThat(((InternalEmail) email).wasMergedWithSmimeSignedMessage()).isFalse();

		EmailAssert.assertThat(email).hasOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder()
				.smimeMode(SmimeMode.SIGNED)
				.smimeMime("multipart/signed")
				.smimeProtocol("application/pkcs7-signature")
				.smimeMicalg("sha-256")
				.smimeSignedBy("Benny Bottema")
				.smimeSignatureValid(true)
				.build());
	}

	@Test
	public void testOutlookMessageWithNestedOutlookMessageAttachment() {
		InputStream resourceAsStream = EmailHelper.class.getClassLoader().getResourceAsStream("test-messages/#298 Email with nested msg.msg");
		Email email = EmailConverter.outlookMsgToEmail(checkNonEmptyArgument(resourceAsStream, "resourceAsStream"));

		assertThat(email.getAttachments()).hasSize(2);
		assertThat(email.getAttachments().get(1).getName()).isEqualTo("This msg file is an attachment.eml");

		final InputStream emlInputstream = email.getAttachments().get(1).getDataSourceInputStream();
		Email nestedEmail = EmailConverter.emlToEmail(emlInputstream);

		EmailAssert.assertThat(nestedEmail).hasSubject("This msg file is an attachment");
		assertThat(normalizeNewlines(nestedEmail.getPlainText()))
				.isEqualTo("This is an email that will be attached to another email.\n");
		EmailAssert.assertThat(nestedEmail).hasOnlyRecipients(new Recipient("atmcquillen@gmail.com", "atmcquillen@gmail.com", TO));
	}

	@Test
	public void testOutlookMessageWithNestedOutlookMessageAttachmentThatHasItsOwnNestedAttachment() {
		InputStream resourceAsStream = EmailHelper.class.getClassLoader().getResourceAsStream("test-messages/#298 Email with nested msg with own attachment.msg");
		Email email = EmailConverter.outlookMsgToEmail(checkNonEmptyArgument(resourceAsStream, "resourceAsStream"));

		assertThat(email.getAttachments()).hasSize(2);
		assertThat(email.getAttachments().get(1).getName()).isEqualTo("This msg file is an attachment.eml");

		final InputStream emlInputstream = email.getAttachments().get(1).getDataSourceInputStream();
		Email nestedEmail = EmailConverter.emlToEmail(emlInputstream);

		EmailAssert.assertThat(nestedEmail).hasSubject("This msg file is an attachment");
		assertThat(normalizeNewlines(nestedEmail.getPlainText()))
				.isEqualTo("This is an email that will be attached to another email.\n");
		EmailAssert.assertThat(nestedEmail).hasOnlyRecipients(new Recipient("atmcquillen@gmail.com", "atmcquillen@gmail.com", TO));
		assertThat(nestedEmail.getAttachments()).hasSize(1);
		assertThat(nestedEmail.getAttachments().get(0).getName()).isEqualTo("Something.docx");
		assertThat(nestedEmail.getAttachments().get(0).getDataSource()).isNotNull();
	}

	@Test
	public void createMailSession_OutlookMessageSmimeEncryptTest()
			throws IOException, MessagingException, ExecutionException, InterruptedException {
		EmailPopulatingBuilder builder = readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg")
				.encryptWithSmime(SmimeEncryptionConfig.builder()
						.x509Certificate(new File(RESOURCES_PKCS + "/smime_test_user.pem.standard.crt"))
						.build());
		Email email = assertSendingEmail(builder, false, true, false, true, false);
		verifyReceivedOutlookEmail(email, false, true);

		//noinspection deprecation
		assertThat(((InternalEmail) email).wasMergedWithSmimeSignedMessage()).isTrue();

		EmailAssert.assertThat(email).hasOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder()
				.smimeMode(SmimeMode.ENCRYPTED)
				.smimeMime("application/pkcs7-mime")
				.smimeType("enveloped-data")
				.smimeName("smime.p7m")
				.build());
	}

	@Test
	public void createMailSession_OutlookMessageSmimeEncryptTest_AlternativeAlgorithms()
			throws IOException, MessagingException, ExecutionException, InterruptedException {
		EmailPopulatingBuilder builder = readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg")
				.encryptWithSmime(SmimeEncryptionConfig.builder()
						.x509Certificate(new File(RESOURCES_PKCS + "/smime_test_user.pem.standard.crt"))
						.keyEncapsulationAlgorithm("RSA_OAEP_SHA384")
						.cipherAlgorithm("AES192_CBC")
						.build());
		Email email = assertSendingEmail(builder, false, true, false, true, false);
		verifyReceivedOutlookEmail(email, false, true);

		//noinspection deprecation
		assertThat(((InternalEmail) email).wasMergedWithSmimeSignedMessage()).isTrue();

		EmailAssert.assertThat(email).hasOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder()
				.smimeMode(SmimeMode.ENCRYPTED)
				.smimeMime("application/pkcs7-mime")
				.smimeType("enveloped-data")
				.smimeName("smime.p7m")
				.build());
	}
	
	@Test
	public void createMailSession_OutlookMessageSmimeSignEncryptTest()
			throws IOException, MessagingException, ExecutionException, InterruptedException {
		EmailPopulatingBuilder builder = readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg")
				.signWithSmime(new File(RESOURCES_PKCS + "/smime_keystore.pkcs12"), "letmein", "smime_test_user_alias_rsa", "letmein", null)
				.encryptWithSmime(SmimeEncryptionConfig.builder()
						.x509Certificate(new File(RESOURCES_PKCS + "/smime_test_user.pem.standard.crt"))
						.build());
		Email email = assertSendingEmail(builder, false, true, false, true, false);
		verifyReceivedOutlookEmail(email, true, true);

		//noinspection deprecation
		assertThat(((InternalEmail) email).wasMergedWithSmimeSignedMessage()).isTrue();
		
		EmailAssert.assertThat(email).hasOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder()
				.smimeMode(SmimeMode.SIGNED_ENCRYPTED)
				.smimeMime("application/pkcs7-mime")
				.smimeType("enveloped-data")
				.smimeName("smime.p7m")
				.build());
		EmailAssert.assertThat(email.getSmimeSignedEmail()).hasOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder()
				.smimeMode(SmimeMode.SIGNED)
				.smimeMime("multipart/signed")
				.smimeProtocol("application/pkcs7-signature")
				.smimeMicalg("sha-256")
				.smimeSignatureValid(true)
				.smimeSignedBy("Benny Bottema")
				.build());
	}
	
	@Test
	public void testEncryptSendAndReceiveDecrypt()
			throws MessagingException, ExecutionException, InterruptedException {
		val builder = EmailHelper.createDummyEmailBuilder(null, true, true, true, false, false, false, false)
				.encryptWithSmime(SmimeEncryptionConfig.builder()
						.x509Certificate(new File(RESOURCES_PKCS + "/smime_test_user.pem.standard.crt"))
						.build());
		
		Email email = assertSendingEmail(builder, false, true, false, false, false);

		//noinspection deprecation
		assertThat(((InternalEmail) email).wasMergedWithSmimeSignedMessage()).isTrue();
		
		EmailAssert.assertThat(email).hasOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder()
				.smimeMode(SmimeMode.ENCRYPTED)
				.smimeMime("application/pkcs7-mime")
				.smimeType("enveloped-data")
				.smimeName("smime.p7m")
				.build());
		EmailAssert.assertThat(email.getSmimeSignedEmail()).hasOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder()
				.smimeMode(SmimeMode.PLAIN)
				.smimeMime(null)
				.smimeProtocol(null)
				.smimeMicalg(null)
				.smimeSignatureValid(null)
				.smimeSignedBy(null)
				.build());
	}

	private void verifyReceivedOutlookEmail(final Email email, final boolean smimeSigned, final boolean smimeEncrypted) throws IOException {
		// Google SMTP overrode this, Outlook recognized it as: Benny Bottema <b.bottema@gmail.com>; on behalf of; lollypop <b.bottema@projectnibble.org>
		EmailAssert.assertThat(email).hasFromRecipient(new Recipient("lollypop", "b.bottema@projectnibble.org", null));
		EmailAssert.assertThat(email).hasSubject("hey");
		// Outlook overrode this when saving the .email to match the mail account
		EmailAssert.assertThat(email).hasRecipients(new Recipient("Bottema, Benny", "benny.bottema@aegon.nl", TO));
		EmailAssert.assertThat(email).hasReplyToRecipients(new Recipient("lollypop-replyto", "lo.pop.replyto@somemail.com", null));
		assertThat(normalizeNewlines(email.getPlainText())).isEqualTo("We should meet up!\n");
		// Outlook overrode this value too OR converted the original HTML to RTF, from which OutlookMessageParser derived this HTML
		assertThat(normalizeNewlines(email.getHTMLText())).isEqualTo(
				"<b>We should meet up!</b><img src=\"cid:thumbsup\">");
		// the RTF was probably created by Outlook based on the HTML when the message was saved

		final AttachmentResource attachment1;
		final AttachmentResource attachment2;

		if (smimeSigned && smimeEncrypted) {
			assertThat(email.getAttachments()).hasSize(4);
			attachment1 = email.getAttachments().get(1);
			attachment2 = email.getAttachments().get(2);
		} else if (smimeSigned) {
			assertThat(email.getAttachments()).hasSize(3);
			attachment1 = email.getAttachments().get(0);
			attachment2 = email.getAttachments().get(1);
		} else if (smimeEncrypted) {
			assertThat(email.getAttachments()).hasSize(3);
			attachment1 = email.getAttachments().get(1);
			attachment2 = email.getAttachments().get(2);
		} else {
			assertThat(email.getAttachments()).hasSize(2);
			attachment1 = email.getAttachments().get(0);
			attachment2 = email.getAttachments().get(1);
		}

		assertThat(email.getEmbeddedImages()).hasSize(1);
		AttachmentResource embeddedImg = email.getEmbeddedImages().get(0);
		// Outlook overrode dresscode.txt, presumably because it was more than 8 character long??

		try {
			assertAttachmentMetadata(attachment1, "text/plain", "dresscode.txt");
			assertAttachmentMetadata(attachment2, "text/plain", "location.txt");
			assertThat(normalizeNewlines(attachment1.readAllData())).isEqualTo("Black Tie Optional");
			assertThat(normalizeNewlines(attachment2.readAllData())).isEqualTo("On the moon!");
		} catch (AssertionError e) {
			// might be sorting problem, try the only other possible order of attachments...
			assertAttachmentMetadata(attachment2, "text/plain", "dresscode.txt");
			assertAttachmentMetadata(attachment1, "text/plain", "location.txt");
			assertThat(normalizeNewlines(attachment2.readAllData())).isEqualTo("Black Tie Optional");
			assertThat(normalizeNewlines(attachment1.readAllData())).isEqualTo("On the moon!");
		}

		assertAttachmentMetadata(embeddedImg, "image/png", "thumbsup");
	}

	private Email assertSendingEmail(final EmailPopulatingBuilder originalEmailPopulatingBuilder, boolean compensateForDresscodeAttachmentNameOverrideErasure, boolean skipChecksDueToSmime,
			boolean async, final boolean sentDateWasFixed, final boolean dynamicImageEmbeddingWasUsed)
			throws MessagingException, ExecutionException, InterruptedException {
		Email originalEmail = originalEmailPopulatingBuilder.buildEmail();

		if (!async) {
			mailer.sendMail(originalEmail);
		} else {
			verifyNonnullOrEmpty(mailer.sendMail(originalEmail, true)).get();
		}
		MimeMessageAndEnvelope receivedMimeMessage = smtpServerExtension.getOnlyMessage();
		assertThat(receivedMimeMessage.getMimeMessage().getMessageID()).isEqualTo(originalEmail.getId());

		if (!originalEmail.getOverrideReceivers().isEmpty()) {
			assertThat(receivedMimeMessage.getEnvelopeReceiver()).isEqualTo(originalEmail.getOverrideReceivers().get(0).getAddress());
		} else {
			assertThat(receivedMimeMessage.getEnvelopeReceiver()).isEqualTo(originalEmail.getRecipients().get(0).getAddress());
		}

		if (originalEmail.getBounceToRecipient() != null) {
			assertThat(receivedMimeMessage.getEnvelopeSender()).isEqualTo(originalEmail.getBounceToRecipient().getAddress());
		} else {
			assertThat(receivedMimeMessage.getEnvelopeSender()).isEqualTo(originalEmail.getFromRecipient().getAddress());
		}

		Email receivedEmail = mimeMessageToEmailBuilder(receivedMimeMessage.getMimeMessage(), loadPkcs12KeyStore()).buildEmail();

		if (!sentDateWasFixed) {
			GregorianCalendar receiveWindowStart = new GregorianCalendar();
			receiveWindowStart.add(Calendar.SECOND, -10);
			assertThat(receivedEmail.getSentDate()).isBetween(receiveWindowStart.getTime(), new Date());
		} else {
			assertThat(receivedEmail.getSentDate()).isEqualTo(originalEmailPopulatingBuilder.getSentDate());
		}

		// ID will always be generated when sending: if set to a specific value, just assume the generated one
		if (originalEmailPopulatingBuilder.getId() == null) {
			originalEmailPopulatingBuilder.fixingMessageId(receivedEmail.getId());
		}

		// sent-date will always be generated when sending: if not set to a specific value, just assume the generated one
		if (originalEmailPopulatingBuilder.getSentDate() == null) {
			originalEmailPopulatingBuilder.fixingSentDate(verifyNonnullOrEmpty(receivedEmail.getSentDate()));
		}

		// hack: it seems Wiser automatically defaults replyTo address to the From address if left empty
		if (originalEmailPopulatingBuilder.getReplyToRecipients().isEmpty()) {
			originalEmailPopulatingBuilder.withReplyTo(originalEmailPopulatingBuilder.getFromRecipient());
		}
		// received email will always have an id, so let's make sure we're able to compare to the original email object
		if (originalEmailPopulatingBuilder.getHeaders().get("Message-ID") == null) {
			originalEmailPopulatingBuilder.withHeader("Message-ID", originalEmail.getId());
		}
		// bounce recipient is not part of the Mimemessage, but the Envelope and is configured on the Session and is not received back on the MimeMessage
		if (originalEmailPopulatingBuilder.getBounceToRecipient() != null) {
			originalEmailPopulatingBuilder.clearBounceTo();
		}
		// Jakarta Mail defaults to 7Bit Content-Transfer-Encoding for text attachments, so we need to match that
		if (!originalEmailPopulatingBuilder.getAttachments().isEmpty()) {
			val attachments = fixAttachmentResourcesWith7Bit(originalEmailPopulatingBuilder.getAttachments());
			originalEmailPopulatingBuilder.clearAttachments().withAttachments(attachments);
			((InternalEmailPopulatingBuilder) originalEmailPopulatingBuilder)
					.clearDecryptedAttachments()
					.withDecryptedAttachments(attachments);
		}
		if (!originalEmailPopulatingBuilder.getDecryptedAttachments().isEmpty()) {
			val decryptedAttachments = fixAttachmentResourcesWith7Bit(originalEmailPopulatingBuilder.getDecryptedAttachments());
			((InternalEmailPopulatingBuilder) originalEmailPopulatingBuilder)
					.clearDecryptedAttachments()
					.withDecryptedAttachments(decryptedAttachments);
		}

		if (originalEmailPopulatingBuilder.getOriginalSmimeDetails() instanceof PlainSmimeDetails) {
			// because the S/MIME module is loaded, the default PLAIN version gets replaced with the one from the module
			((InternalEmailPopulatingBuilder) originalEmailPopulatingBuilder)
					.withOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder().build());
		}

		if (compensateForDresscodeAttachmentNameOverrideErasure) {
			TestDataHelper.retrofitLostOriginalAttachmentNames(receivedEmail);
		}

		assertThat(receivedEmail.getHeaders()).containsEntry("governanceDefaultTest1", singletonList("defaulted"));
		assertThat(receivedEmail.getHeaders()).containsEntry("governanceOverrideTest1", singletonList("overridden"));
		assertThat(receivedEmail.getHeaders()).containsEntry("governanceOverrideTest2", singletonList("also overridden"));

		originalEmailPopulatingBuilder
				.withHeader("governanceDefaultTest1", "defaulted", true)
				.withHeader("governanceOverrideTest1", "overridden", true)
				.withHeader("governanceOverrideTest2", "also overridden", true);

		// envelope-level receivers would have been lost in the received Message, so we need to compensate for that
		originalEmailPopulatingBuilder.clearOverrideReceivers();

		if (!skipChecksDueToSmime) { // reading a signed mail is different from building a new one
			assertThat(receivedEmail).isEqualTo(originalEmailPopulatingBuilder.buildEmail());
		} else {
			val originalMail = originalEmailPopulatingBuilder.buildEmail();
			EmailAssert.assertThat(receivedEmail).hasSubject(originalMail.getSubject());
			EmailAssert.assertThat(receivedEmail).hasFromRecipient(originalMail.getFromRecipient());
			EmailAssert.assertThat(receivedEmail).hasOnlyRecipients(originalMail.getRecipients());
			EmailAssert.assertThat(receivedEmail).hasHTMLText(originalMail.getHTMLText());
			EmailAssert.assertThat(receivedEmail).hasPlainText(originalMail.getPlainText());
			EmailAssert.assertThat(receivedEmail).hasCalendarMethod(originalMail.getCalendarMethod());
			EmailAssert.assertThat(receivedEmail).hasCalendarText(originalMail.getCalendarText());
			EmailAssert.assertThat(receivedEmail).hasBounceToRecipient(originalMail.getBounceToRecipient());
			EmailAssert.assertThat(receivedEmail).hasDispositionNotificationTo(originalMail.getDispositionNotificationTo());
		}

		return receivedEmail;
	}

	@NotNull
	private List<AttachmentResource> fixAttachmentResourcesWith7Bit(final List<AttachmentResource> originalEmailPopulatingBuilder) {
		return originalEmailPopulatingBuilder.stream()
				.map(att -> new AttachmentResource(att.getName(), null, att.getDataSource(), att.getDescription(), ofNullable(att.getContentTransferEncoding()).orElse(BIT7)))
				.collect(toList());
	}

	@Test
	public void createMailSession_ReplyToMessage()
			throws MessagingException, ExecutionException, InterruptedException {
		// send initial mail
		mailer.sendMail(readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg").buildEmail());
		MimeMessageAndEnvelope receivedMimeMessage = smtpServerExtension.getOnlyMessage();
		EmailPopulatingBuilder receivedEmailPopulatingBuilder = mimeMessageToEmailBuilder(receivedMimeMessage.getMimeMessage());
		
		// send reply to initial mail
		Email reply = EmailBuilder
				.replyingToAll(assertSendingEmail(receivedEmailPopulatingBuilder, false, false, false, true, false))
				.from("dummy@domain.com")
				.withPlainText("This is the reply")
				.buildEmail();
		
		// test received reply to initial mail
		mailer.sendMail(reply);
		MimeMessage receivedMimeMessageReply1 = smtpServerExtension.getMessage("lo.pop.replyto@somemail.com");
		MimeMessage receivedMimeMessageReply2 = smtpServerExtension.getMessage("benny.bottema@aegon.nl");
		Email receivedReply1 = mimeMessageToEmail(receivedMimeMessageReply1);
		Email receivedReply2 = mimeMessageToEmail(receivedMimeMessageReply2);
		
		assertThat(receivedReply1).isEqualTo(receivedReply2);
		EmailAssert.assertThat(receivedReply1).hasSubject("Re: hey");
		EmailAssert.assertThat(receivedReply1).hasOnlyRecipients(
				new Recipient("lollypop-replyto", "lo.pop.replyto@somemail.com", TO),
				new Recipient("Bottema, Benny", "benny.bottema@aegon.nl", TO)
		);

		assertThat(receivedReply1.getHeaders()).contains(entry("In-Reply-To", singletonList(receivedEmailPopulatingBuilder.getId())));
		assertThat(receivedReply1.getHeaders()).contains(entry("References", singletonList(receivedEmailPopulatingBuilder.getId())));
	}
	
	@Test
	public void createMailSession_ReplyToMessage_NotAll_AndCustomReferences()
			throws MessagingException, ExecutionException, InterruptedException {
		// send initial mail
		mailer.sendMail(readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg").buildEmail());
		MimeMessageAndEnvelope receivedMimeMessage = smtpServerExtension.getOnlyMessage();
		EmailPopulatingBuilder receivedEmailPopulatingBuilder = mimeMessageToEmailBuilder(receivedMimeMessage.getMimeMessage());
		
		// send reply to initial mail
		Email reply = EmailBuilder
				.replyingTo(assertSendingEmail(receivedEmailPopulatingBuilder, false, false, false, true, false))
				.from("Moo Shmoo", "dummy@domain.com")
				.withPlainText("This is the reply")
				.buildEmail();
		
		// test received reply to initial mail
		mailer.sendMail(reply);
		MimeMessage receivedMimeMessageReply = smtpServerExtension.getOnlyMessage("lo.pop.replyto@somemail.com");
		Email receivedReply = mimeMessageToEmail(receivedMimeMessageReply);
		
		EmailAssert.assertThat(receivedReply).hasSubject("Re: hey");
		EmailAssert.assertThat(receivedReply).hasOnlyRecipients(new Recipient("lollypop-replyto", "lo.pop.replyto@somemail.com", TO));
		assertThat(receivedReply.getHeaders()).contains(entry("In-Reply-To", singletonList(receivedEmailPopulatingBuilder.getId())));
		assertThat(receivedReply.getHeaders()).contains(entry("References", singletonList(receivedEmailPopulatingBuilder.getId())));

		EmailPopulatingBuilder receivedEmailReplyPopulatingBuilder = mimeMessageToEmailBuilder(receivedMimeMessageReply);

		Email replyToReply = EmailBuilder
				.replyingTo(assertSendingEmail(receivedEmailReplyPopulatingBuilder, false, false, false, false, false))
				.from("Pappa Moo", "dummy@domain.com")
				.withPlainText("This is the reply to the reply")
				.buildEmail();

		// test received reply to initial mail
		mailer.sendMail(replyToReply);
		MimeMessage receivedMimeMessageReplyToReply = smtpServerExtension.getOnlyMessage("dummy@domain.com");
		Email receivedReplyToReply = mimeMessageToEmail(receivedMimeMessageReplyToReply);

		EmailAssert.assertThat(receivedReplyToReply).hasSubject("Re: hey");
		EmailAssert.assertThat(receivedReplyToReply).hasOnlyRecipients(new Recipient("Moo Shmoo", "dummy@domain.com", TO));
		assertThat(receivedReplyToReply.getHeaders()).contains(entry("In-Reply-To", singletonList(receivedEmailReplyPopulatingBuilder.getId())));

		val references = format("%s %s", receivedEmailPopulatingBuilder.getId(), receivedEmailReplyPopulatingBuilder.getId());
		assertThat(receivedReplyToReply.getHeaders()).contains(entry("References", singletonList(references)));
	}
	
	private void assertAttachmentMetadata(AttachmentResource attachment, String mimeType, String filename) {
		assertThat(attachment.getDataSource().getContentType()).isEqualTo(mimeType);
		assertThat(attachment.getName()).isEqualTo(filename);
	}

	@Test
	public void testMaximumEmailSize() {
		val mailer = MailerBuilder
				.withSMTPServer("localhost", SERVER_PORT, USERNAME, PASSWORD)
				.withMaximumEmailSize(4)
				.buildMailer();

		sendAndVerifyEmailTooBigException(mailer);
	}

	@Test
	public void testMaximumEmailSize_CustomMailer() {
		val mailer = MailerBuilder
				.withCustomMailer(new CustomMailer() {
					@Override
					public void testConnection(@NotNull OperationalConfig operationalConfig, @NotNull Session session) {
						throw new RuntimeException("should reach here");
					}

					@Override
					public void sendMessage(@NotNull OperationalConfig operationalConfig, @NotNull Session session, @NotNull Email email, @NotNull MimeMessage message) {
						throw new RuntimeException("should reach here");
					}
				})
				.withMaximumEmailSize(4)
				.buildMailer();

		sendAndVerifyEmailTooBigException(mailer);
	}

	@Test
	public void testMaximumEmailSize_DontSendOnlyLog() {
		val mailer = MailerBuilder
				.withTransportModeLoggingOnly()
				.withMaximumEmailSize(4)
				.buildMailer();

		sendAndVerifyEmailTooBigException(mailer);
	}

	private static void sendAndVerifyEmailTooBigException(Mailer mailer) {
		val email = EmailBuilder.startingBlank()
				.withPlainText("non empty text")
				.withSubject("email size test")
				.from("a@b.com")
				.to("a@b.com")
				.buildEmail();

		assertThatThrownBy(() -> mailer.sendMail(email))
				.hasMessageStartingWith("Failed to send email [ID:")
				.getCause()
				.isInstanceOf(EmailTooBigException.class)
				.hasMessageContaining("bytes exceeds maximum allowed size of 4 bytes");
	}

	@Test
	public void testNonASCIIAttachementNames() throws MessagingException {
		val email = EmailConverter.emlToEmail(new File(RESOURCE_TEST_MESSAGES + "/#293 Email with vers quoted printable.eml"));

		mailer.sendMail(email);

		val receivedEmail = mimeMessageToEmail(smtpServerExtension.getOnlyMessage().getMimeMessage());

		assertThat(receivedEmail.getAttachments()).hasSize(1);

		val attachment = receivedEmail.getAttachments().get(0);

		assertThat(attachment.getName()).isEqualTo("Configure_SSO_for_Admin_Console_Access_\u2013_Silverfort.pdf.html");
		assertThat(attachment.getDescription()).isEqualTo("Configure_SSO_for_Admin_Console_Access_\u2013_Silverfort.pdf.html");
		assertThat(attachment.getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
	}
}