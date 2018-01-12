package org.simplejavamail.mailer;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.simplejavamail.email.AttachmentResource;
import org.simplejavamail.email.Email;
import org.simplejavamail.email.EmailAssert;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.email.EmailPopulatingBuilder;
import org.simplejavamail.email.Recipient;
import org.simplejavamail.util.ConfigLoader;
import testutil.EmailHelper;
import testutil.testrules.SmtpServerRule;
import testutil.testrules.TestSmtpServer;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.util.Properties;

import static javax.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.MapEntry.entry;
import static org.simplejavamail.converter.EmailConverter.mimeMessageToEmail;
import static org.simplejavamail.converter.EmailConverter.mimeMessageToEmailBuilder;
import static testutil.EmailHelper.normalizeText;
import static testutil.EmailHelper.readOutlookMessage;

@SuppressWarnings("unused")
public class MailerLiveTest {

	private static final String SERVER_HOST = "localhost";
	private static final Integer SERVER_PORT = 251;

	@Rule
	public final SmtpServerRule smtpServerRule = new SmtpServerRule(new TestSmtpServer(SERVER_HOST, SERVER_PORT));

	private Mailer mailer;

	@Before
	public void setup() {
		ConfigLoader.loadProperties(new Properties(), false); // clear out defaults
		mailer = MailerBuilder.withSMTPServer(SERVER_HOST, SERVER_PORT).buildMailer();
	}
	
	@Test
	public void createMailSession_EmptySubjectAndBody()
			throws IOException, MessagingException {
		assertSendingEmail(EmailHelper.createDummyEmailBuilder(true, true, false));
	}
	
	@Test
	public void createMailSession_StandardDummyMailBasicFields()
			throws IOException, MessagingException {
		assertSendingEmail(EmailHelper.createDummyEmailBuilder(true, true, false));
	}
	
	@Test
	public void createMailSession_StandardDummyMail_AllFields()
			throws IOException, MessagingException {
		assertSendingEmail(EmailHelper.createDummyEmailBuilder(true, false, false));
	}
	
	@Test
	public void createMailSession_StandardDummyMail_IncludingCustomHeaders()
			throws IOException, MessagingException {
		assertSendingEmail(EmailHelper.createDummyEmailBuilder(true, false, true));
	}

	@Test
	public void createMailSession_StandardDummyMailWithId()
			throws IOException, MessagingException {
		assertSendingEmail(EmailHelper.createDummyEmailBuilder("<123@456>", true, false, false));
	}
	
	@Test
	public void createMailSession_OutlookMessageTest()
			throws IOException, MessagingException {
		final Email email = assertSendingEmail(readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg"));

		// Google SMTP overrode this, Outlook recognized it as: Benny Bottema <b.bottema@gmail.com>; on behalf of; lollypop <b.bottema@projectnibble.org>
		EmailAssert.assertThat(email).hasFromRecipient(new Recipient("lollypop", "b.bottema@projectnibble.org", null));
		EmailAssert.assertThat(email).hasSubject("hey");
		// Outlook overrode this when saving the .email to match the mail account
		EmailAssert.assertThat(email).hasRecipients(new Recipient("Bottema, Benny", "benny.bottema@aegon.nl", TO));
		EmailAssert.assertThat(email).hasReplyToRecipient(new Recipient("lollypop-replyto", "lo.pop.replyto@somemail.com", null));
		assertThat(normalizeText(email.getPlainText())).isEqualTo("We should meet up!\n");
		// Outlook overrode this value too OR converted the original HTML to RTF, from which OutlookMessageParser derived this HTML
		assertThat(normalizeText(email.getHTMLText())).contains(
				"<html><body style=\"font-family:'Courier',monospace;font-size:10pt;\">   <br/>      <br/> <b>   We should meet up! <br/>  </b>   <br/>  <img src=\"cid:thumbsup\"> <br/> ");
		// the RTF was probably created by Outlook based on the HTML when the message was saved
		assertThat(email.getAttachments()).hasSize(2);
		assertThat(email.getEmbeddedImages()).hasSize(1);
		final AttachmentResource attachment1 = email.getAttachments().get(0);
		final AttachmentResource attachment2 = email.getAttachments().get(1);
		final AttachmentResource embeddedImg = email.getEmbeddedImages().get(0);
		// Outlook overrode dresscode.txt, presumably because it was more than 8 character long??
		assertAttachmentMetadata(attachment1, "text/plain", "dresscode.txt");
		assertAttachmentMetadata(attachment2, "text/plain", "location.txt");
		assertAttachmentMetadata(embeddedImg, "image/png", "thumbsup");

		assertThat(normalizeText(attachment1.readAllData())).isEqualTo("Black Tie Optional");
		assertThat(normalizeText(attachment2.readAllData())).isEqualTo("On the moon!");
	}

	private Email assertSendingEmail(final EmailPopulatingBuilder originalEmailPopulatingBuilder)
			throws MessagingException {
		final Email originalEmail = originalEmailPopulatingBuilder.buildEmail();
		mailer.sendMail(originalEmail);
		final MimeMessage receivedMimeMessage = smtpServerRule.getOnlyMessage();
		assertThat(receivedMimeMessage.getMessageID()).isEqualTo(originalEmail.getId());
		
		final Email receivedEmail = mimeMessageToEmail(receivedMimeMessage);
		// hack: it seems Wiser automatically defaults replyTo address to the From address if left empty
		if (originalEmailPopulatingBuilder.getReplyToRecipient() == null) {
			originalEmailPopulatingBuilder.withReplyTo(originalEmailPopulatingBuilder.getFromRecipient());
		}
		// received email will always have an id, so let's make sure we're able to compare to the original email object
		if (originalEmailPopulatingBuilder.getHeaders().get("Message-ID") == null) {
			originalEmailPopulatingBuilder.withHeader("Message-ID", originalEmail.getId());
		}
		// bounce recipient is not part of the Mimemessage, but the Envelope and is configured on the Session, so just ignore this
		if (originalEmailPopulatingBuilder.getBounceToRecipient() != null) {
			originalEmailPopulatingBuilder.clearBounceTo();
		}
		assertThat(receivedEmail).isEqualTo(originalEmailPopulatingBuilder.buildEmail());
		return receivedEmail;
	}
	
	@Test
	public void createMailSession_ReplyToMessage()
			throws MessagingException {
		// send initial mail
		mailer.sendMail(readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg").buildEmail());
		final MimeMessage receivedMimeMessage = smtpServerRule.getOnlyMessage();
		final EmailPopulatingBuilder receivedEmailPopulatingBuilder = mimeMessageToEmailBuilder(receivedMimeMessage);
		
		// send reply to initial mail
		final Email reply = EmailBuilder
				.replyingToAll(assertSendingEmail(receivedEmailPopulatingBuilder))
				.from("dummy@domain.com")
				.withPlainText("This is the reply")
				.buildEmail();
		
		// test received reply to initial mail
		mailer.sendMail(reply);
		final MimeMessage receivedMimeMessageReply1 = smtpServerRule.getMessage("lo.pop.replyto@somemail.com");
		final MimeMessage receivedMimeMessageReply2 = smtpServerRule.getMessage("benny.bottema@aegon.nl");
		final Email receivedReply1 = mimeMessageToEmail(receivedMimeMessageReply1);
		final Email receivedReply2 = mimeMessageToEmail(receivedMimeMessageReply2);
		
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
			throws MessagingException {
		// send initial mail
		mailer.sendMail(readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg").buildEmail());
		final MimeMessage receivedMimeMessage = smtpServerRule.getOnlyMessage();
		final EmailPopulatingBuilder receivedEmailPopulatingBuilder = mimeMessageToEmailBuilder(receivedMimeMessage);
		
		// send reply to initial mail
		final Email reply = EmailBuilder
				.replyingTo(assertSendingEmail(receivedEmailPopulatingBuilder))
				.withHeader("References", "dummy-references")
				.from("dummy@domain.com")
				.withPlainText("This is the reply")
				.buildEmail();
		
		// test received reply to initial mail
		mailer.sendMail(reply);
		final MimeMessage receivedMimeMessageReply1 = smtpServerRule.getOnlyMessage("lo.pop.replyto@somemail.com");
		final Email receivedReply = mimeMessageToEmail(receivedMimeMessageReply1);
		
		EmailAssert.assertThat(receivedReply).hasSubject("Re: hey");
		EmailAssert.assertThat(receivedReply).hasOnlyRecipients(new Recipient("lollypop-replyto", "lo.pop.replyto@somemail.com", TO));
		assertThat(receivedReply.getHeaders()).contains(entry("In-Reply-To", receivedEmailPopulatingBuilder.getId()));
		assertThat(receivedReply.getHeaders()).contains(entry("References", "dummy-references"));
	}
	
	private void assertAttachmentMetadata(final AttachmentResource embeddedImg, final String mimeType, final String filename) {
		assertThat(embeddedImg.getDataSource().getContentType()).isEqualTo(mimeType);
		assertThat(embeddedImg.getName()).isEqualTo(filename);
	}
}