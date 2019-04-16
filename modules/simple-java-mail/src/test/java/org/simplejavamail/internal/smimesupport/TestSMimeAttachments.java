package org.simplejavamail.internal.smimesupport;

import org.junit.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.OriginalSMimeDetails;
import org.simplejavamail.converter.EmailConverter;

import java.io.File;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static javax.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.internal.util.Preconditions.assumeNonNull;

public class TestSMimeAttachments {

	private static final String RESOURCE_FOLDER = determineResourceFolder("simple-java-mail") + "/test/resources/test-messages";

	@Test
	public void testSMIMEMessage() {
		Email emailParsedFromMsg = EmailConverter.outlookMsgToEmail(new File(RESOURCE_FOLDER + "/SMIME (signed and clear text).msg"));

		EmailAssert.assertThat(emailParsedFromMsg).hasFromRecipient(new Recipient("Alessandro Gasparini", "donotreply@unknown-from-address.net", null));
		EmailAssert.assertThat(emailParsedFromMsg).hasSubject("Invio messaggio SMIME (signed and clear text)");
		EmailAssert.assertThat(emailParsedFromMsg).hasOnlyRecipients(new Recipient("a.gasparini@logicaldoc.com", "a.gasparini@logicaldoc.com", TO));

		assertThat(emailParsedFromMsg.getPlainText()).isNullOrEmpty();
		assertThat(emailParsedFromMsg.getHTMLText()).isNullOrEmpty();
		assertThat(emailParsedFromMsg.getEmbeddedImages()).isEmpty();

		assertThat(emailParsedFromMsg.getDecryptedAttachments()).hasSize(1);
		assertThat(emailParsedFromMsg.getAttachments()).hasSize(1);

		// verify msg that was sent with Outlook against eml that was received in Thunderbird
		EmailPopulatingBuilder fromEmlBuilder = EmailConverter.emlToEmailBuilder(new File(RESOURCE_FOLDER + "/SMIME (signed and clear text).eml"));
		Email emailExpectedFromEml = fromEmlBuilder
				.clearId() // set by Outlook when sending, so is missing in the saved .msg before sending
				.clearHeaders() // same
				.clearReplyTo() // same
				.clearBounceTo() // same
				.from(assumeNonNull(fromEmlBuilder.getFromRecipient()).getName(), "donotreply@unknown-from-address.net")
				.buildEmail();
		EmailAssert.assertThat(emailParsedFromMsg).isEqualTo(emailExpectedFromEml);
		EmailAssert.assertThat(emailParsedFromMsg).hasOriginalSMimeDetails(new OriginalSMimeDetails(
				"application/pkcs7-mime",
				"signed-data",
				"smime.p7m",
				"shatzing5@outlook.com"
		));
	}
}