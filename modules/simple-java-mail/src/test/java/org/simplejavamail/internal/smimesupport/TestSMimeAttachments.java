package org.simplejavamail.internal.smimesupport;

import org.junit.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
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

		EmailAssert.assertThat(emailParsedFromMsg).hasFromRecipient(new Recipient(null, "shatzing5@outlook.com", null));
		EmailAssert.assertThat(emailParsedFromMsg).hasSubject("Invio messaggio SMIME (signed and clear text)");
		EmailAssert.assertThat(emailParsedFromMsg).hasOnlyRecipients(new Recipient("a.gasparini@logicaldoc.com", "a.gasparini@logicaldoc.com", TO));

		assertThat(emailParsedFromMsg.getPlainText()).isNullOrEmpty();
		assertThat(emailParsedFromMsg.getHTMLText()).isNullOrEmpty();
		assertThat(emailParsedFromMsg.getEmbeddedImages()).isEmpty();

		assertThat(emailParsedFromMsg.getDecryptedAttachments()).hasSize(1);
		assertThat(emailParsedFromMsg.getAttachments()).hasSize(1);

		// verify msg against eml
		EmailPopulatingBuilder fromEmlBuilder = EmailConverter.emlToEmailBuilder(new File(RESOURCE_FOLDER + "/SMIME (signed and clear text).eml"));
		Email emailExpectedFromEml = fromEmlBuilder
				.clearId()
				.clearHeaders()
				.clearReplyTo()
				.clearBounceTo()
				.from(null, assumeNonNull(fromEmlBuilder.getFromRecipient()).getAddress())
				.buildEmail();
		EmailAssert.assertThat(emailParsedFromMsg).isEqualTo(emailExpectedFromEml);
	}
}