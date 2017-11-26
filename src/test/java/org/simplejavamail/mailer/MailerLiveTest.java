package org.simplejavamail.mailer;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.simplejavamail.email.AttachmentResource;
import org.simplejavamail.email.Email;
import org.simplejavamail.email.EmailAssert;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.email.Recipient;
import org.simplejavamail.mailer.config.ServerConfig;
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

	private static final ServerConfig SERVER_CONFIG = new ServerConfig("localhost", 251);

	@Rule
	public final SmtpServerRule smtpServerRule = new SmtpServerRule(new TestSmtpServer(SERVER_CONFIG));

	private Mailer mailer;

	@Before
	public void setup() {
		ConfigLoader.loadProperties(new Properties(), false); // clear out defaults
		mailer = new Mailer(SERVER_CONFIG);
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
		Email email = assertSendingEmail(readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg"));

		// Google SMTP overrode this, Outlook recognized it as: Benny Bottema <b.bottema@gmail.com>; on behalf of; lollypop <b.bottema@projectnibble.org>
		EmailAssert.assertThat(email).hasFromRecipient(new Recipient("lollypop", "b.bottema@projectnibble.org", null));
		EmailAssert.assertThat(email).hasSubject("hey");
		// Outlook overrode this when saving the .email to match the mail account
		EmailAssert.assertThat(email).hasRecipients(new Recipient("Bottema, Benny", "benny.bottema@aegon.nl", TO));
		EmailAssert.assertThat(email).hasReplyToRecipient(new Recipient("lollypop-replyto", "lo.pop.replyto@somemail.com", null));
		assertThat(normalizeText(email.getText())).isEqualTo("We should meet up!\n");
		// Outlook overrode this value too OR converted the original HTML to RTF, from which OutlookMessageParser derived this HTML
		assertThat(normalizeText(email.getTextHTML())).contains(
				"<html><body style=\"font-family:'Courier',monospace;font-size:10pt;\">   <br/>      <br/> <b>   We should meet up! <br/>  </b>   <br/>  <img src=\"cid:thumbsup\"> <br/> ");
		// the RTF was probably created by Outlook based on the HTML when the message was saved
		assertThat(email.getAttachments()).hasSize(2);
		assertThat(email.getEmbeddedImages()).hasSize(1);
		AttachmentResource attachment1 = email.getAttachments().get(0);
		AttachmentResource attachment2 = email.getAttachments().get(1);
		AttachmentResource embeddedImg = email.getEmbeddedImages().get(0);
		// Outlook overrode dresscode.txt, presumably because it was more than 8 character long??
		assertAttachmentMetadata(attachment1, "text/plain", "dresscode.txt");
		assertAttachmentMetadata(attachment2, "text/plain", "location.txt");
		assertAttachmentMetadata(embeddedImg, "image/png", "thumbsup");

		assertThat(normalizeText(attachment1.readAllData())).isEqualTo("Black Tie Optional");
		assertThat(normalizeText(attachment2.readAllData())).isEqualTo("On the moon!");
	}

	private Email assertSendingEmail(final EmailBuilder originalEmailBuilder)
			throws MessagingException {
		Email originalEmail = originalEmailBuilder.build();
		mailer.sendMail(originalEmail);
		MimeMessage receivedMimeMessage = smtpServerRule.getOnlyMessage();
		assertThat(receivedMimeMessage.getMessageID()).isEqualTo(originalEmail.getId());
		
		Email receivedEmail = mimeMessageToEmail(receivedMimeMessage);
		// hack: it seems Wiser automatically defaults replyTo address to the From address if left empty
		if (originalEmailBuilder.getReplyToRecipient() == null) {
			originalEmailBuilder.replyTo(originalEmailBuilder.getFromRecipient());
		}
		// received email will always have an id, so let's make sure we're able to compare to the original email object
		if (originalEmailBuilder.getHeaders().get("Message-ID") == null) {
			originalEmailBuilder.addHeader("Message-ID", originalEmail.getId());
		}
		// bounce recipient is not part of the Mimemessage, but the Envelope and is configured on the Session, so just ignore this
		if (originalEmailBuilder.getBounceToRecipient() != null) {
			receivedEmail.setBounceToRecipient(originalEmailBuilder.getBounceToRecipient());
		}
		assertThat(receivedEmail).isEqualTo(originalEmailBuilder.build());
		return receivedEmail;
	}
	
	@Test
	public void createMailSession_ReplyToMessage()
			throws IOException, MessagingException {
		// send initial mail
		mailer.sendMail(readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg").build());
		MimeMessage receivedMimeMessage = smtpServerRule.getOnlyMessage();
		EmailBuilder receivedEmailBuilder = mimeMessageToEmailBuilder(receivedMimeMessage);
		
		// send reply to initial mail
		Email reply = EmailBuilder.builder()
				.asReplyToAll(assertSendingEmail(receivedEmailBuilder))
				.from("dummy@domain.com")
				.text("This is the reply")
				.build();
		
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
		assertThat(receivedReply1.getHeaders()).contains(entry("In-Reply-To", receivedEmailBuilder.getId()));
		assertThat(receivedReply1.getHeaders()).contains(entry("References", receivedEmailBuilder.getId()));
	}
	
	@Test
	public void createMailSession_ReplyToMessage_NotAll_AndCustomReferences()
			throws IOException, MessagingException {
		// send initial mail
		mailer.sendMail(readOutlookMessage("test-messages/HTML mail with replyto and attachment and embedded image.msg").build());
		MimeMessage receivedMimeMessage = smtpServerRule.getOnlyMessage();
		EmailBuilder receivedEmailBuilder = mimeMessageToEmailBuilder(receivedMimeMessage);
		
		// send reply to initial mail
		Email reply = EmailBuilder.builder()
				.asReplyTo(assertSendingEmail(receivedEmailBuilder))
				.addHeader("References", "dummy-references")
				.from("dummy@domain.com")
				.text("This is the reply")
				.build();
		
		// test received reply to initial mail
		mailer.sendMail(reply);
		MimeMessage receivedMimeMessageReply1 = smtpServerRule.getOnlyMessage("lo.pop.replyto@somemail.com");
		Email receivedReply = mimeMessageToEmail(receivedMimeMessageReply1);
		
		EmailAssert.assertThat(receivedReply).hasSubject("Re: hey");
		EmailAssert.assertThat(receivedReply).hasOnlyRecipients(new Recipient("lollypop-replyto", "lo.pop.replyto@somemail.com", TO));
		assertThat(receivedReply.getHeaders()).contains(entry("In-Reply-To", receivedEmailBuilder.getId()));
		assertThat(receivedReply.getHeaders()).contains(entry("References", "dummy-references"));
	}
	
	private void assertAttachmentMetadata(AttachmentResource embeddedImg, String mimeType, String filename) {
		assertThat(embeddedImg.getDataSource().getContentType()).isEqualTo(mimeType);
		assertThat(embeddedImg.getName()).isEqualTo(filename);
	}
}