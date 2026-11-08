package org.simplejavamail.internal.smimesupport;

import org.simplejavamail.api.email.config.EmailTemplateOptions;
import lombok.val;
import org.junit.jupiter.api.Test;
import jakarta.mail.internet.ContentType;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.email.internal.InternalEmailPopulatingBuilder;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;

import java.io.File;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.AbstractMap.SimpleEntry;
import java.util.List;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static jakarta.mail.Message.RecipientType.TO;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.internal.smimesupport.SmimeRecognitionUtil.SMIME_ATTACHMENT_MESSAGE_ID;
import static org.simplejavamail.internal.util.MiscUtil.normalizeNewlines;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnullOrEmpty;

public class ReadSmimeAttachmentsTest {

	private static final String RESOURCE_FOLDER = determineResourceFolder("simple-java-mail") + "/test/resources/test-messages";

	@Test
	public void testSMIMEMessageFromOutlookMsgWithDefaultMergeBehavior() throws Exception {
		Email emailParsedFromMsg = EmailConverter.outlookMsgToEmail(new File(RESOURCE_FOLDER + "/SMIME (signed and clear text).msg"));

		assertThat(emailParsedFromMsg.getFromRecipient()).isNull();
		EmailAssert.assertThat(emailParsedFromMsg).hasSubject("Invio messaggio SMIME (signed and clear text)");
		EmailAssert.assertThat(emailParsedFromMsg).hasOnlyRecipients(new Recipient("a.gasparini@logicaldoc.com", "a.gasparini@logicaldoc.com", TO, null));

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
		assertThat(emailParsedFromMsg.getHeaders()).contains(new SimpleEntry<>("Message-ID", singletonList(SMIME_ATTACHMENT_MESSAGE_ID)));
		assertThat(emailParsedFromMsg.getHTMLText()).contains("<p class=MsoNormal><span lang=EN-US>Invio messaggio SMIME (signed and clear text)<o:p>");
		assertThat(emailParsedFromMsg.getEmbeddedImages()).isEmpty();

		assertThat(emailParsedFromMsg.getAttachments()).hasSize(2);
		assertThat(emailParsedFromMsg.getAttachments()).extracting("name").containsExactlyInAnyOrder("smime.p7m", "3 crucial benefits of Cloud computing.docx");
		assertThat(emailParsedFromMsg.getDecryptedAttachments()).hasSize(2);
		assertThat(emailParsedFromMsg.getDecryptedAttachments()).extracting("name").containsExactlyInAnyOrder("signed-email.eml", "3 crucial benefits of Cloud computing.docx");

		EmailAssert.assertThat(emailParsedFromMsg).hasOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder()
				.smimeMode(SmimeMode.SIGNED)
				.smimeMime("application/pkcs7-mime")
				.smimeType("signed-data")
				.smimeName("smime.p7m")
				.smimeSignedBy("shatzing5@outlook.com")
				.smimeSignatureValid(true)
				.build());

		// verify msg that was sent with Outlook against eml that was received in Thunderbird
		EmailPopulatingBuilder fromEmlBuilder = EmailConverter.emlToEmailBuilder(new File(RESOURCE_FOLDER + "/SMIME (signed and clear text).eml"));
		Email emailExpectedFromEml = fromEmlBuilder
				.clearHeaders() // set by Outlook when sending, so is missing in the saved .msg from before sending
				.clearReplyTo() // same
				.clearBounceTo() // same
				// Outlook doesn't provide Content-TransferEncoding header on attachment level:
				.withAttachments(removeContentTransferEncodingFromAttachments(fromEmlBuilder))
				.clearFromRecipient() // saved MSG has no sender address; retain the EML's other content for comparison
				.buildEmail();

		// EML conversion can retain the exact protected outer entity. Outlook MSG conversion starts from an attachment,
		// so compare the common message content without requiring identical protection provenance.
		assertSameMessageWithSourceSpecificOpaqueMetadata(emailParsedFromMsg, emailExpectedFromEml);
	}

	@Test
	public void testEmlSmimeHeaderRecognition() {
		Email emailFromSignedEml = EmailConverter.emlToEmail(new File(RESOURCE_FOLDER + "/SMIME (signed and clear text).eml"));
		EmailAssert.assertThat(emailFromSignedEml).hasOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder()
				.smimeMode(SmimeMode.SIGNED)
				.smimeMime("application/pkcs7-mime")
				.smimeType("signed-data")
				.smimeName("smime.p7m")
				.smimeSignedBy("shatzing5@outlook.com")
				.smimeSignatureValid(true)
				.build());
	}

	@Test
	public void testSMIMEMessageFromOutlookMsgWithNonMergingBehavior() throws Exception {
		Email emailParsedFromMsg = EmailConverter.outlookMsgToEmailBuilder(new File(RESOURCE_FOLDER + "/SMIME (signed and clear text).msg"))
				.notMergingSingleSMIMESignedAttachment()
				.buildEmail();

		assertThat(emailParsedFromMsg.getFromRecipient()).isNull();
		EmailAssert.assertThat(emailParsedFromMsg).hasSubject("Invio messaggio SMIME (signed and clear text)");
		EmailAssert.assertThat(emailParsedFromMsg).hasOnlyRecipients(new Recipient("a.gasparini@logicaldoc.com", "a.gasparini@logicaldoc.com", TO, null));

		assertThat(emailParsedFromMsg.getHeaders()).isEmpty();
		assertThat(normalizeNewlines(emailParsedFromMsg.getPlainText())).isNullOrEmpty();
		assertThat(emailParsedFromMsg.getHTMLText()).isNullOrEmpty();
		assertThat(emailParsedFromMsg.getEmbeddedImages()).isEmpty();

		assertThat(emailParsedFromMsg.getAttachments()).hasSize(1);
		assertThat(emailParsedFromMsg.getAttachments()).extracting("name").containsExactly("smime.p7m");
		assertThat(emailParsedFromMsg.getDecryptedAttachments()).hasSize(1);
		assertThat(emailParsedFromMsg.getDecryptedAttachments()).extracting("name").containsExactly("signed-email.eml");

		final Email smimeSignedEmail = verifyNonnullOrEmpty(emailParsedFromMsg.getSmimeSignedEmail());

		assertThat(smimeSignedEmail.getHeaders()).contains(new SimpleEntry<>("Message-ID", singletonList(SMIME_ATTACHMENT_MESSAGE_ID)));
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

		EmailAssert.assertThat(emailParsedFromMsg).hasOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder()
				.smimeMode(SmimeMode.SIGNED)
				.smimeMime("application/pkcs7-mime")
				.smimeType("signed-data")
				.smimeName("smime.p7m")
				.smimeSignedBy("shatzing5@outlook.com")
				.smimeSignatureValid(true)
				.build());

		// verify msg that was sent with Outlook against eml that was received in Thunderbird
		EmailPopulatingBuilder fromEmlBuilder = EmailConverter.emlToEmailBuilder(new File(RESOURCE_FOLDER + "/SMIME (signed and clear text).eml"));
		Email emailExpectedFromEml = fromEmlBuilder
				.clearHeaders() // set by Outlook when sending, so is missing in the saved .msg from before sending
				.clearReplyTo() // same
				.clearBounceTo() // same
				// Outlook doesn't provide Content-TransferEncoding header on attachment level:
				.withAttachments(removeContentTransferEncodingFromAttachments(fromEmlBuilder))
				.clearFromRecipient() // saved MSG has no sender address; retain the EML's other content for comparison
				.buildEmail();

		Email emailWithCopiedMerginBehavior = SimpleJavaMail.fromDefaults().emailBuilder()
				.copying(emailParsedFromMsg)
				.withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().build())
				.buildEmail();
		EmailAssert.assertThat(emailWithCopiedMerginBehavior).isEqualTo(emailParsedFromMsg);

		Email emailWithDefaultMerginBehavior = SimpleJavaMail.fromDefaults().emailBuilder()
				.copying(emailParsedFromMsg)
				.withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().build())
				.clearSMIMESignedAttachmentMergingBehavior()
				.buildEmail();
		assertSameMessageWithSourceSpecificOpaqueMetadata(emailWithDefaultMerginBehavior, emailExpectedFromEml);
	}

	private void assertSameMessageWithSourceSpecificOpaqueMetadata(final Email fromMsg, final Email fromEml) throws Exception {
		final AttachmentResource msgArtifact = opaqueArtifact(fromMsg);
		final AttachmentResource emlArtifact = opaqueArtifact(fromEml);
		assertThat(msgArtifact.getDataSource().getContentType()).isEqualTo("application/pkcs7-mime");
		final ContentType emlType = new ContentType(emlArtifact.getDataSource().getContentType());
		assertThat(emlType.getBaseType()).isEqualTo("application/pkcs7-mime");
		assertThat(emlType.getParameter("smime-type")).isEqualTo("signed-data");
		assertThat(emlType.getParameter("name")).isEqualTo("smime.p7m");
		// Both independent fixtures contain the same CMS bytes; only the retained source MIME parameters differ.
		for (AttachmentResource artifact : List.of(msgArtifact, emlArtifact)) {
			final byte[] bytes = artifact.readAllBytes();
			assertThat(bytes).hasSize(28116);
			assertThat(String.format("%064x", new BigInteger(1, MessageDigest.getInstance("SHA-256").digest(bytes))))
					.isEqualTo("e764a65910965b7035561d4a9d67eccec92c325d7acce364a9db171f2858fb08");
		}
		EmailAssert.assertThat(withoutOpaqueArtifact(fromMsg)).isEqualTo(withSmimeProvenanceFrom(withoutOpaqueArtifact(fromEml), fromMsg));
	}

	private AttachmentResource opaqueArtifact(final Email email) {
		final List<AttachmentResource> artifacts = email.getAttachments().stream()
				.filter(resource -> "smime.p7m".equals(resource.getName())).collect(toList());
		assertThat(artifacts).hasSize(1);
		return artifacts.get(0);
	}

	private Email withoutOpaqueArtifact(final Email email) {
		return SimpleJavaMail.fromDefaults().emailBuilder().copying(email).clearAttachments()
				.withAttachments(email.getAttachments().stream().filter(resource -> !"smime.p7m".equals(resource.getName())).collect(toList()))
				.buildEmail();
	}

	private Email withSmimeProvenanceFrom(final Email email, final Email provenanceSource) {
		final InternalEmailPopulatingBuilder builder = (InternalEmailPopulatingBuilder) SimpleJavaMail.fromDefaults()
				.emailBuilder()
				.copying(email);
		builder.withTemplateOptions(builder.getTemplateOptions().toBuilder().ignoringDefaults().build());
		builder.withOriginalSmimeDetails(provenanceSource.getOriginalSmimeDetails());
		return builder.buildEmail();
	}

	private List<AttachmentResource> removeContentTransferEncodingFromAttachments(final EmailPopulatingBuilder builder) {
		val attachments = builder.getAttachments();
		builder.clearAttachments();
		return attachments.stream()
				.map(att -> AttachmentResource.builder().withName(att.getName()).withDataSource(att.getDataSource()).withDescription(att.getDescription()).build())
				.collect(toList());
	}
}
