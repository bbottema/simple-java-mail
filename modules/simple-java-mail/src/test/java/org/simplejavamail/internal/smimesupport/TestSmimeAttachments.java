package org.simplejavamail.internal.smimesupport;

import org.junit.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.email.internal.InternalEmailPopulatingBuilder;

import java.io.File;
import java.util.AbstractMap.SimpleEntry;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static javax.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.internal.util.MiscUtil.normalizeNewlines;
import static org.simplejavamail.internal.util.Preconditions.assumeNonNull;
import static org.simplejavamail.internal.util.SmimeRecognitionUtil.SMIME_ATTACHMENT_MESSAGE_ID;

public class TestSmimeAttachments {

	private static final String RESOURCE_FOLDER = determineResourceFolder("simple-java-mail") + "/test/resources/test-messages";

	@Test
	public void testSMIMEMessageFromOutlookMsgWithDefaultMergeBehavior() {
		Email emailParsedFromMsg = EmailConverter.outlookMsgToEmail(new File(RESOURCE_FOLDER + "/SMIME (signed and clear text).msg"));

		EmailAssert.assertThat(emailParsedFromMsg).hasFromRecipient(new Recipient("Alessandro Gasparini", "donotreply@unknown-from-address.net", null));
		EmailAssert.assertThat(emailParsedFromMsg).hasSubject("Invio messaggio SMIME (signed and clear text)");
		EmailAssert.assertThat(emailParsedFromMsg).hasOnlyRecipients(new Recipient("a.gasparini@logicaldoc.com", "a.gasparini@logicaldoc.com", TO));

		assertThat(normalizeNewlines(emailParsedFromMsg.getPlainText())).isEqualTo("Invio messaggio SMIME (signed and clear text)\n"
				+ "\n"
				+ "-- \n"
				+ "Alessandro Gasparini\n"
				+ "Chief Technology Officer\n"
				+ "\n"
				+ "\n"
				+ "\n"
				+ "\n"
				+ "LOGICALDOC Srl\n"
				+ "Via Aldo Moro interna 3\n"
				+ "41012 Carpi / Modena\n"
				+ "\n"
				+ "P: +39 059 5970906\n"
				+ " <https://www.logicaldoc.com> https://www.logicaldoc.com \n"
				+ "\n"
				+ " \n"
				+ "\n");
		assertThat(emailParsedFromMsg.getHeaders()).contains(new SimpleEntry<>("Message-ID", SMIME_ATTACHMENT_MESSAGE_ID));
		assertThat(emailParsedFromMsg.getHTMLText()).contains("<p class=MsoNormal><span lang=EN-US>Invio messaggio SMIME (signed and clear text)<o:p>");
		assertThat(emailParsedFromMsg.getEmbeddedImages()).isEmpty();

		assertThat(emailParsedFromMsg.getAttachments()).hasSize(2);
		assertThat(emailParsedFromMsg.getAttachments()).extracting("name").containsExactlyInAnyOrder("smime.p7m", "3 crucial benefits of Cloud computing.docx");
		assertThat(emailParsedFromMsg.getDecryptedAttachments()).hasSize(2);
		assertThat(emailParsedFromMsg.getDecryptedAttachments()).extracting("name").containsExactlyInAnyOrder("signed-email.eml", "3 crucial benefits of Cloud computing.docx");

		EmailAssert.assertThat(emailParsedFromMsg).hasOriginalSmimeDetails(new OriginalSmimeDetails(
				"application/pkcs7-mime",
				"signed-data",
				"smime.p7m",
				"shatzing5@outlook.com"
		));

		// verify msg that was sent with Outlook against eml that was received in Thunderbird
		EmailPopulatingBuilder fromEmlBuilder = EmailConverter.emlToEmailBuilder(new File(RESOURCE_FOLDER + "/SMIME (signed and clear text).eml"));
		Email emailExpectedFromEml = ((InternalEmailPopulatingBuilder) fromEmlBuilder)
				.clearId() // set by Outlook when sending, so is missing in the saved .msg from before sending
				.clearHeaders() // same
				.clearReplyTo() // same
				.clearBounceTo() // same
				.from(assumeNonNull(fromEmlBuilder.getFromRecipient()).getName(), "donotreply@unknown-from-address.net")
				.buildEmail();

		EmailAssert.assertThat(emailParsedFromMsg).isEqualTo(emailExpectedFromEml);
	}

	@Test
	@SuppressWarnings("deprecation")
	public void testEmlSmimeHeaderRecognition() {
		Email emailFromSignedEml = EmailConverter.emlToEmail(new File(RESOURCE_FOLDER + "/SMIME (signed and clear text).eml"));
		EmailAssert.assertThat(emailFromSignedEml).hasOriginalSmimeDetails(new OriginalSmimeDetails(
				"application/pkcs7-mime",
				"signed-data",
				"smime.p7m",
				"shatzing5@outlook.com"
		));
	}

	@Test
	public void testSMIMEMessageFromOutlookMsgWithNonMergingBehavior() {
		Email emailParsedFromMsg = EmailConverter.outlookMsgToEmailBuilder(new File(RESOURCE_FOLDER + "/SMIME (signed and clear text).msg"))
				.notMergingSingleSMIMESignedAttachment()
				.buildEmail();

		EmailAssert.assertThat(emailParsedFromMsg).hasFromRecipient(new Recipient("Alessandro Gasparini", "donotreply@unknown-from-address.net", null));
		EmailAssert.assertThat(emailParsedFromMsg).hasSubject("Invio messaggio SMIME (signed and clear text)");
		EmailAssert.assertThat(emailParsedFromMsg).hasOnlyRecipients(new Recipient("a.gasparini@logicaldoc.com", "a.gasparini@logicaldoc.com", TO));

		assertThat(emailParsedFromMsg.getHeaders()).isEmpty();
		assertThat(normalizeNewlines(emailParsedFromMsg.getPlainText())).isNullOrEmpty();
		assertThat(emailParsedFromMsg.getHTMLText()).isNullOrEmpty();
		assertThat(emailParsedFromMsg.getEmbeddedImages()).isEmpty();

		assertThat(emailParsedFromMsg.getAttachments()).hasSize(1);
		assertThat(emailParsedFromMsg.getAttachments()).extracting("name").containsExactly("smime.p7m");
		assertThat(emailParsedFromMsg.getDecryptedAttachments()).hasSize(1);
		assertThat(emailParsedFromMsg.getDecryptedAttachments()).extracting("name").containsExactly("signed-email.eml");

		final Email smimeSignedEmail = assumeNonNull(emailParsedFromMsg.getSmimeSignedEmail());

		assertThat(smimeSignedEmail.getHeaders()).contains(new SimpleEntry<>("Message-ID", SMIME_ATTACHMENT_MESSAGE_ID));
		assertThat(normalizeNewlines(smimeSignedEmail.getPlainText())).isEqualTo("Invio messaggio SMIME (signed and clear text)\n"
				+ "\n"
				+ "-- \n"
				+ "Alessandro Gasparini\n"
				+ "Chief Technology Officer\n"
				+ "\n"
				+ "\n"
				+ "\n"
				+ "\n"
				+ "LOGICALDOC Srl\n"
				+ "Via Aldo Moro interna 3\n"
				+ "41012 Carpi / Modena\n"
				+ "\n"
				+ "P: +39 059 5970906\n"
				+ " <https://www.logicaldoc.com> https://www.logicaldoc.com \n"
				+ "\n"
				+ " \n"
				+ "\n");
		assertThat(smimeSignedEmail.getHTMLText()).contains("<p class=MsoNormal><span lang=EN-US>Invio messaggio SMIME (signed and clear text)<o:p>");
		assertThat(smimeSignedEmail.getEmbeddedImages()).isEmpty();

		assertThat(smimeSignedEmail.getAttachments()).hasSize(1);
		assertThat(smimeSignedEmail.getAttachments()).extracting("name").containsExactly("3 crucial benefits of Cloud computing.docx");
		assertThat(smimeSignedEmail.getDecryptedAttachments()).hasSize(1);
		assertThat(smimeSignedEmail.getDecryptedAttachments()).extracting("name").containsExactly("3 crucial benefits of Cloud computing.docx");

		EmailAssert.assertThat(emailParsedFromMsg).hasOriginalSmimeDetails(new OriginalSmimeDetails(
				"application/pkcs7-mime",
				"signed-data",
				"smime.p7m",
				"shatzing5@outlook.com"
		));

		// verify msg that was sent with Outlook against eml that was received in Thunderbird
		EmailPopulatingBuilder fromEmlBuilder = EmailConverter.emlToEmailBuilder(new File(RESOURCE_FOLDER + "/SMIME (signed and clear text).eml"));
		Email emailExpectedFromEml = ((InternalEmailPopulatingBuilder) fromEmlBuilder)
				.clearId() // set by Outlook when sending, so is missing in the saved .msg from before sending
				.clearHeaders() // same
				.clearReplyTo() // same
				.clearBounceTo() // same
				.from(assumeNonNull(fromEmlBuilder.getFromRecipient()).getName(), "donotreply@unknown-from-address.net")
				.buildEmail();

		Email emailWithCopiedMerginBehavior = EmailBuilder
				.ignoringDefaults()
				.copying(emailParsedFromMsg)
				.buildEmail();
		EmailAssert.assertThat(emailWithCopiedMerginBehavior).isEqualTo(emailParsedFromMsg);

		Email emailWithDefaultMerginBehavior = EmailBuilder
				.ignoringDefaults()
				.copying(emailParsedFromMsg)
				.clearSMIMESignedAttachmentMergingBehavior()
				.buildEmail();
		EmailAssert.assertThat(emailWithDefaultMerginBehavior).isEqualTo(emailExpectedFromEml);
	}

}