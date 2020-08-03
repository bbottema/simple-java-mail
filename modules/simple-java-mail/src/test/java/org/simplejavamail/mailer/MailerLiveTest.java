package org.simplejavamail.mailer;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.internal.smimesupport.model.PlainSmimeDetails;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.email.internal.InternalEmailPopulatingBuilder;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;
import org.simplejavamail.util.TestDataHelper;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;
import testutil.testrules.MimeMessageAndEnvelope;
import testutil.testrules.SmtpServerRule;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;
import java.io.File;
import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.concurrent.ExecutionException;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.lang.String.format;
import static javax.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.MapEntry.entry;
import static org.simplejavamail.converter.EmailConverter.mimeMessageToEmail;
import static org.simplejavamail.converter.EmailConverter.mimeMessageToEmailBuilder;
import static org.simplejavamail.internal.util.MiscUtil.normalizeNewlines;
import static org.simplejavamail.internal.util.Preconditions.assumeNonNull;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;
import static testutil.EmailHelper.readOutlookMessage;

/*
 * This class name is referrenced in pom as an exclusion for a profile that is only active during
 * remote builds (so excluded from tests in CircleCI).
 */
@SuppressWarnings("unused")
public class MailerLiveTest {

	private static final String RESOURCES_PKCS = determineResourceFolder("simple-java-mail") + "/test/resources/pkcs12";

	private static final Integer SERVER_PORT = 251;

	@Rule
	public final SmtpServerRule smtpServerRule = new SmtpServerRule(SERVER_PORT);

	private Mailer mailer;

	@Before
	public void setup() {
		ConfigLoaderTestHelper.clearConfigProperties();
		mailer = MailerBuilder.withSMTPServer("localhost", SERVER_PORT).buildMailer();
	}
	
	@Test
	public void createMailSession_EmptySubjectAndBody()
			throws IOException, MessagingException, ExecutionException, InterruptedException {
		assertSendingEmail(EmailHelper.createDummyEmailBuilder(true, true, false, true, false, false), true, false, false, false, false);
	}

	@Test
	public void createMailSession_StandardDummyMailBasicFields()
			throws IOException, MessagingException, ExecutionException, InterruptedException {
		assertSendingEmail(EmailHelper.createDummyEmailBuilder(true, true, false, true, false, false), true, false, false, false, false);
	}

	@Test
	public void createMailSession_StandardDummyMailBasicFields_Async()
			throws IOException, MessagingException, ExecutionException, InterruptedException {
		assertSendingEmail(EmailHelper.createDummyEmailBuilder(true, true, false, true, false, false), true, false, true, false, false);
	}

	@Test
	public void createMailSession_StandardDummyMail_AllFields()
			throws IOException, MessagingException, ExecutionException, InterruptedException {
		assertSendingEmail(EmailHelper.createDummyEmailBuilder(true, false, false, true, true, true), true, false, false, false, true);
	}
	
	@Test
	public void createMailSession_StandardDummyMail_IncludingCustomHeaders()
			throws IOException, MessagingException, ExecutionException, InterruptedException {
		assertSendingEmail(EmailHelper.createDummyEmailBuilder(true, false, true, true, false, false), true, false, false, false, false);
	}

	@Test
	public void createMailSession_StandardDummyMailWithIdAndSendDate()
			throws IOException, MessagingException, ExecutionException, InterruptedException {
		assertSendingEmail(EmailHelper.createDummyEmailBuilder("<123@456>", true, false, false, true, true, false, false), true, false, false, true, false);
	}

	@Test
	public void createMailSession_OutlookMessageTest()
			throws IOException, MessagingException, ExecutionException, InterruptedException {
		Email email = assertSendingEmail(readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg"), false, false, false, true, false);
		verifyReceivedOutlookEmail(email, false, false);
	}

	@Test
	public void createMailSession_OutlookMessageSmimeSignTest()
			throws IOException, MessagingException, ExecutionException, InterruptedException {
		EmailPopulatingBuilder builder = readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg");
		builder.signWithSmime(new File(RESOURCES_PKCS + "/smime_keystore.pkcs12"), "letmein", "smime_test_user_alias", "letmein");
		Email email = assertSendingEmail(builder, false, true, false, true, false);
		verifyReceivedOutlookEmail(email, true, false);

		EmailAssert.assertThat(email).wasNotMergedWithSmimeSignedMessage();

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
	public void createMailSession_OutlookMessageSmimeEncryptTest()
			throws IOException, MessagingException, ExecutionException, InterruptedException {
		EmailPopulatingBuilder builder = readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg");
		builder.encryptWithSmime(new File(RESOURCES_PKCS + "/smime_test_user.pem.standard.crt"));
		Email email = assertSendingEmail(builder, false, true, false, true, false);
		verifyReceivedOutlookEmail(email, false, true);

		EmailAssert.assertThat(email).wasMergedWithSmimeSignedMessage();

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
		EmailPopulatingBuilder builder = readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg");
		builder.signWithSmime(new File(RESOURCES_PKCS + "/smime_keystore.pkcs12"), "letmein", "smime_test_user_alias", "letmein");
		builder.encryptWithSmime(new File(RESOURCES_PKCS + "/smime_test_user.pem.standard.crt"));
		Email email = assertSendingEmail(builder, false, true, false, true, false);
		verifyReceivedOutlookEmail(email, true, true);

		EmailAssert.assertThat(email).wasMergedWithSmimeSignedMessage();

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

	private void verifyReceivedOutlookEmail(final Email email, final boolean smimeSigned, final boolean smimeEncrypted) throws IOException {
		// Google SMTP overrode this, Outlook recognized it as: Benny Bottema <b.bottema@gmail.com>; on behalf of; lollypop <b.bottema@projectnibble.org>
		EmailAssert.assertThat(email).hasFromRecipient(new Recipient("lollypop", "b.bottema@projectnibble.org", null));
		EmailAssert.assertThat(email).hasSubject("hey");
		// Outlook overrode this when saving the .email to match the mail account
		EmailAssert.assertThat(email).hasRecipients(new Recipient("Bottema, Benny", "benny.bottema@aegon.nl", TO));
		EmailAssert.assertThat(email).hasReplyToRecipient(new Recipient("lollypop-replyto", "lo.pop.replyto@somemail.com", null));
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
			assumeNonNull(mailer.sendMail(originalEmail, async)).getFuture().get();
		}
		MimeMessageAndEnvelope receivedMimeMessage = smtpServerRule.getOnlyMessage();
		assertThat(receivedMimeMessage.getMimeMessage().getMessageID()).isEqualTo(originalEmail.getId());

		if (originalEmail.getBounceToRecipient() != null) {
			assertThat(receivedMimeMessage.getEnvelopeSender()).isEqualTo(originalEmail.getBounceToRecipient().getAddress());
		} else {
			assertThat(receivedMimeMessage.getEnvelopeSender()).isEqualTo(originalEmail.getFromRecipient().getAddress());
		}

		Email receivedEmail = mimeMessageToEmail(receivedMimeMessage.getMimeMessage(), loadPkcs12KeyStore());

		if (!sentDateWasFixed) {
			GregorianCalendar receiveWindowStart = new GregorianCalendar();
			receiveWindowStart.add(Calendar.SECOND, -5);
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
			originalEmailPopulatingBuilder.fixingSentDate(assumeNonNull(receivedEmail.getSentDate()));
		}

		// hack: it seems Wiser automatically defaults replyTo address to the From address if left empty
		if (originalEmailPopulatingBuilder.getReplyToRecipient() == null) {
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

		if (originalEmailPopulatingBuilder.getOriginalSmimeDetails() instanceof PlainSmimeDetails) {
			// because the S/MIME module is loaded, the default PLAIN version gets replaced with the one from the module
			((InternalEmailPopulatingBuilder) originalEmailPopulatingBuilder)
					.withOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder().build());
		}

		if (compensateForDresscodeAttachmentNameOverrideErasure) {
			TestDataHelper.fixDresscodeAttachment(receivedEmail);
		}

		if (!skipChecksDueToSmime) { // reading a signed mail is different from building a new one
			assertThat(receivedEmail).isEqualTo(originalEmailPopulatingBuilder.buildEmail());
		}

		return receivedEmail;
	}
	
	@Test
	public void createMailSession_ReplyToMessage()
			throws MessagingException, ExecutionException, InterruptedException {
		// send initial mail
		mailer.sendMail(readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg").buildEmail());
		MimeMessageAndEnvelope receivedMimeMessage = smtpServerRule.getOnlyMessage();
		EmailPopulatingBuilder receivedEmailPopulatingBuilder = mimeMessageToEmailBuilder(receivedMimeMessage.getMimeMessage());
		
		// send reply to initial mail
		Email reply = EmailBuilder
				.replyingToAll(assertSendingEmail(receivedEmailPopulatingBuilder, false, false, false, true, false))
				.from("dummy@domain.com")
				.withPlainText("This is the reply")
				.buildEmail();
		
		// test received reply to initial mail
		mailer.sendMail(reply);
		MimeMessage receivedMimeMessageReply1 = smtpServerRule.getMessage("lo.pop.replyto@somemail.com");
		MimeMessage receivedMimeMessageReply2 = smtpServerRule.getMessage("benny.bottema@aegon.nl");
		Email receivedReply1 = mimeMessageToEmail(receivedMimeMessageReply1);
		Email receivedReply2 = mimeMessageToEmail(receivedMimeMessageReply2);
		
		assertThat(receivedReply1).isEqualTo(receivedReply2);
		EmailAssert.assertThat(receivedReply1).hasSubject("Re: hey");
		EmailAssert.assertThat(receivedReply1).hasOnlyRecipients(
				new Recipient("lollypop-replyto", "lo.pop.replyto@somemail.com", TO),
				new Recipient("Bottema, Benny", "benny.bottema@aegon.nl", TO)
		);
		assertThat(receivedReply1.getHeaders()).contains(entry("In-Reply-To", receivedEmailPopulatingBuilder.getId()));
		assertThat(receivedReply1.getHeaders()).contains(entry("References", receivedEmailPopulatingBuilder.getId()));
	}
	
	@Test
	public void createMailSession_ReplyToMessage_NotAll_AndCustomReferences()
			throws MessagingException, ExecutionException, InterruptedException {
		// send initial mail
		mailer.sendMail(readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg").buildEmail());
		MimeMessageAndEnvelope receivedMimeMessage = smtpServerRule.getOnlyMessage();
		EmailPopulatingBuilder receivedEmailPopulatingBuilder = mimeMessageToEmailBuilder(receivedMimeMessage.getMimeMessage());
		
		// send reply to initial mail
		Email reply = EmailBuilder
				.replyingTo(assertSendingEmail(receivedEmailPopulatingBuilder, false, false, false, true, false))
				.from("Moo Shmoo", "dummy@domain.com")
				.withPlainText("This is the reply")
				.buildEmail();
		
		// test received reply to initial mail
		mailer.sendMail(reply);
		MimeMessage receivedMimeMessageReply = smtpServerRule.getOnlyMessage("lo.pop.replyto@somemail.com");
		Email receivedReply = mimeMessageToEmail(receivedMimeMessageReply);
		
		EmailAssert.assertThat(receivedReply).hasSubject("Re: hey");
		EmailAssert.assertThat(receivedReply).hasOnlyRecipients(new Recipient("lollypop-replyto", "lo.pop.replyto@somemail.com", TO));
		assertThat(receivedReply.getHeaders()).contains(entry("In-Reply-To", receivedEmailPopulatingBuilder.getId()));
		assertThat(receivedReply.getHeaders()).contains(entry("References", receivedEmailPopulatingBuilder.getId()));

		EmailPopulatingBuilder receivedEmailReplyPopulatingBuilder = mimeMessageToEmailBuilder(receivedMimeMessageReply);

		Email replyToReply = EmailBuilder
				.replyingTo(assertSendingEmail(receivedEmailReplyPopulatingBuilder, false, false, false, false, false))
				.from("Pappa Moo", "dummy@domain.com")
				.withPlainText("This is the reply to the reply")
				.buildEmail();

		// test received reply to initial mail
		mailer.sendMail(replyToReply);
		MimeMessage receivedMimeMessageReplyToReply = smtpServerRule.getOnlyMessage("dummy@domain.com");
		Email receivedReplyToReply = mimeMessageToEmail(receivedMimeMessageReplyToReply);

		EmailAssert.assertThat(receivedReplyToReply).hasSubject("Re: hey");
		EmailAssert.assertThat(receivedReplyToReply).hasOnlyRecipients(new Recipient("Moo Shmoo", "dummy@domain.com", TO));
		assertThat(receivedReplyToReply.getHeaders()).contains(entry("In-Reply-To", receivedEmailReplyPopulatingBuilder.getId()));

		assertThat(receivedReplyToReply.getHeaders()).contains(entry("References",
				MimeUtility.fold("References: ".length(), format("%s\n%s",
						receivedEmailPopulatingBuilder.getId(),
						receivedEmailReplyPopulatingBuilder.getId()))
		));
	}
	
	private void assertAttachmentMetadata(AttachmentResource embeddedImg, String mimeType, String filename) {
		assertThat(embeddedImg.getDataSource().getContentType()).isEqualTo(mimeType);
		assertThat(embeddedImg.getName()).isEqualTo(filename);
	}
}