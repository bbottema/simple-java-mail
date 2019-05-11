package org.simplejavamail.internal.smimesupport;

import org.junit.Test;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.lang.String.format;
import static javax.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.internal.util.MiscUtil.normalizeNewlines;

public class TestSmimeSelfSigned {

	private static final String RESOURCES_PKCS = determineResourceFolder("simple-java-mail") + "/test/resources/pkcs12";
	private static final String RESOURCES_MESSAGES = RESOURCES_PKCS + "/test messages";

	@Test
	public void testSignedMessageMsg() {
		Email emailParsedFromMsg = EmailConverter.outlookMsgToEmail(new File(RESOURCES_MESSAGES + "/S_MIME test message signed.msg"));

		EmailAssert.assertThat(emailParsedFromMsg).hasFromRecipient(new Recipient("Benny Bottema", "benny@bennybottema.com", null));
		EmailAssert.assertThat(emailParsedFromMsg).hasSubject("S/MIME test message signed");
		EmailAssert.assertThat(emailParsedFromMsg).hasOnlyRecipients(new Recipient("Benny Bottema", "benny@bennybottema.com", TO));

		assertThat(normalizeNewlines(emailParsedFromMsg.getPlainText())).isEqualTo("This is an encrypted message, with one embedded image and one dummy \n"
				+ "attachment.\n"
				+ "\n"
				+ "For testing purposes in the Simple Java Mail project.\n"
				+ "\n");

		assertThat(emailParsedFromMsg.getEmbeddedImages()).hasSize(1);
		AttachmentResource embeddedImg = emailParsedFromMsg.getEmbeddedImages().get(0);
		assertThat(embeddedImg.getName()).isEqualTo("part1.6C435E18.EC121991@bennybottema.com");
		assertThat(embeddedImg.getDataSource().getName()).isEqualTo("module_architecture.png");
		assertThat(embeddedImg.getDataSource().getContentType()).isEqualTo("image/png");
		assertThat(emailParsedFromMsg.getHTMLText()).contains(format("<img src=\"cid:%s\"", embeddedImg.getName()));

		assertThat(emailParsedFromMsg.getAttachments()).hasSize(2);
		assertThat(emailParsedFromMsg.getAttachments()).extracting("name").containsExactlyInAnyOrder("smime.p7s", "03-07-2005 errata SharnErrata.pdf");
		assertThat(emailParsedFromMsg.getDecryptedAttachments()).hasSize(2);
		assertThat(emailParsedFromMsg.getDecryptedAttachments()).extracting("name").containsExactlyInAnyOrder("signed-email.eml", "03-07-2005 errata SharnErrata.pdf");

		EmailAssert.assertThat(emailParsedFromMsg).hasOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder()
				.smimeMode(SmimeMode.SIGNED)
				.smimeMime("multipart/signed")
				.smimeProtocol("application/pkcs7-signature")
				.smimeMicalg("sha-512")
				.smimeSignedBy("Benny Bottema")
				.smimeSignatureValid(true)
				.build());
	}

	@Test
	public void testSignedMessageEml() {
		Email emailParsedFromEml = EmailConverter.emlToEmail(new File(RESOURCES_MESSAGES + "/S_MIME test message signed.eml"));

		EmailAssert.assertThat(emailParsedFromEml).hasFromRecipient(new Recipient("Benny Bottema", "benny@bennybottema.com", null));
		EmailAssert.assertThat(emailParsedFromEml).hasSubject("S/MIME test message signed");
		EmailAssert.assertThat(emailParsedFromEml).hasOnlyRecipients(new Recipient("Benny Bottema", "benny@bennybottema.com", TO));

		assertThat(normalizeNewlines(emailParsedFromEml.getPlainText())).isEqualTo("This is an encrypted message, with one embedded image and one dummy \n"
				+ "attachment.\n"
				+ "\n"
				+ "For testing purposes in the Simple Java Mail project.\n"
				+ "\n");

		assertThat(emailParsedFromEml.getEmbeddedImages()).hasSize(1);
		AttachmentResource embeddedImg = emailParsedFromEml.getEmbeddedImages().get(0);
		assertThat(embeddedImg.getName()).isEqualTo("part1.6C435E18.EC121991@bennybottema.com");
		assertThat(embeddedImg.getDataSource().getName()).isEqualTo("module_architecture.png");
		assertThat(embeddedImg.getDataSource().getContentType()).isEqualTo("image/png");
		assertThat(emailParsedFromEml.getHTMLText()).contains(format("<img src=\"cid:%s\"", embeddedImg.getName()));

		assertThat(emailParsedFromEml.getAttachments()).hasSize(2);
		assertThat(emailParsedFromEml.getAttachments()).extracting("name").containsExactlyInAnyOrder("smime.p7s", "03-07-2005 errata SharnErrata.pdf");
		assertThat(emailParsedFromEml.getDecryptedAttachments()).hasSize(2);
		assertThat(emailParsedFromEml.getDecryptedAttachments()).extracting("name").containsExactlyInAnyOrder("smime.p7s", "03-07-2005 errata SharnErrata.pdf");

		EmailAssert.assertThat(emailParsedFromEml).hasOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder()
				.smimeMode(SmimeMode.SIGNED)
				.smimeMime("multipart/signed")
				.smimeProtocol("application/pkcs7-signature")
				.smimeMicalg("sha-512")
				.smimeSignatureValid(true)
				.build());
	}

	@Test
	public void testEncryptedMessageMsg()
			throws FileNotFoundException {
		Email emailParsedFromMsg = EmailConverter.outlookMsgToEmail(new File(RESOURCES_MESSAGES + "/S_MIME test message encrypted.msg"), loadPkcs12KeyStore());

		EmailAssert.assertThat(emailParsedFromMsg).hasFromRecipient(new Recipient("Benny Bottema", "benny@bennybottema.com", null));
		EmailAssert.assertThat(emailParsedFromMsg).hasSubject("S/MIME test message encrypted");
		EmailAssert.assertThat(emailParsedFromMsg).hasOnlyRecipients(new Recipient("Benny Bottema", "benny@bennybottema.com", TO));

		assertThat(normalizeNewlines(emailParsedFromMsg.getPlainText())).isEqualTo("This is an encrypted message, with one embedded image and one dummy \n"
				+ "attachment.\n"
				+ "\n"
				+ "For testing purposes in the Simple Java Mail project.\n"
				+ "\n");

		assertThat(emailParsedFromMsg.getEmbeddedImages()).hasSize(1);
		AttachmentResource embeddedImg = emailParsedFromMsg.getEmbeddedImages().get(0);
		assertThat(embeddedImg.getName()).isEqualTo("part1.EDA02623.55A510EE@bennybottema.com");
		assertThat(embeddedImg.getDataSource().getName()).isEqualTo("module_architecture.png");
		assertThat(embeddedImg.getDataSource().getContentType()).isEqualTo("image/png");
		assertThat(emailParsedFromMsg.getHTMLText()).contains(format("<img src=\"cid:%s\"", embeddedImg.getName()));

		assertThat(emailParsedFromMsg.getAttachments()).hasSize(2);
		assertThat(emailParsedFromMsg.getAttachments()).extracting("name").containsExactlyInAnyOrder("smime.p7m", "03-07-2005 errata SharnErrata.pdf");
		assertThat(emailParsedFromMsg.getDecryptedAttachments()).hasSize(2);
		assertThat(emailParsedFromMsg.getDecryptedAttachments()).extracting("name").containsExactlyInAnyOrder("signed-email.eml", "03-07-2005 errata SharnErrata.pdf");

		EmailAssert.assertThat(emailParsedFromMsg).hasOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder()
				.smimeMode(SmimeMode.ENCRYPTED)
				.smimeMime("application/pkcs7-mime")
				.smimeType("enveloped-data")
				.smimeName("smime.p7m")
				.build());
	}

	@Test
	public void testEncryptedMessageEml()
			throws FileNotFoundException {
		Email emailParsedFromEml = EmailConverter.emlToEmail(new File(RESOURCES_MESSAGES + "/S_MIME test message encrypted.eml"), loadPkcs12KeyStore());

		EmailAssert.assertThat(emailParsedFromEml).hasFromRecipient(new Recipient("Benny Bottema", "benny@bennybottema.com", null));
		EmailAssert.assertThat(emailParsedFromEml).hasSubject("S/MIME test message encrypted");
		EmailAssert.assertThat(emailParsedFromEml).hasOnlyRecipients(new Recipient("Benny Bottema", "benny@bennybottema.com", TO));

		assertThat(normalizeNewlines(emailParsedFromEml.getPlainText())).isEqualTo("This is an encrypted message, with one embedded image and one dummy \n"
				+ "attachment.\n"
				+ "\n"
				+ "For testing purposes in the Simple Java Mail project.\n"
				+ "\n");

		assertThat(emailParsedFromEml.getEmbeddedImages()).hasSize(1);
		AttachmentResource embeddedImg = emailParsedFromEml.getEmbeddedImages().get(0);
		assertThat(embeddedImg.getName()).isEqualTo("part1.EDA02623.55A510EE@bennybottema.com");
		assertThat(embeddedImg.getDataSource().getName()).isEqualTo("module_architecture.png");
		assertThat(embeddedImg.getDataSource().getContentType()).isEqualTo("image/png");
		assertThat(emailParsedFromEml.getHTMLText()).contains(format("<img src=\"cid:%s\"", embeddedImg.getName()));

		assertThat(emailParsedFromEml.getAttachments()).hasSize(2);
		assertThat(emailParsedFromEml.getAttachments()).extracting("name").containsExactlyInAnyOrder("smime.p7m", "03-07-2005 errata SharnErrata.pdf");
		assertThat(emailParsedFromEml.getDecryptedAttachments()).hasSize(2);
		assertThat(emailParsedFromEml.getDecryptedAttachments()).extracting("name").containsExactlyInAnyOrder("signed-email.eml", "03-07-2005 errata SharnErrata.pdf");

		EmailAssert.assertThat(emailParsedFromEml).hasOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder()
				.smimeMode(SmimeMode.ENCRYPTED)
				.smimeMime("application/pkcs7-mime")
				.smimeType("enveloped-data")
				.smimeName("smime.p7m")
				.build());
	}

	@Test
	public void testSignedAndEncryptedMessageMsg()
			throws FileNotFoundException {
		Email emailParsedFromMsg = EmailConverter.outlookMsgToEmail(new File(RESOURCES_MESSAGES + "/S_MIME test message signed & encrypted.msg"), loadPkcs12KeyStore());

		EmailAssert.assertThat(emailParsedFromMsg).hasFromRecipient(new Recipient("Benny Bottema", "benny@bennybottema.com", null));
		EmailAssert.assertThat(emailParsedFromMsg).hasSubject("S/MIME test message signed & encrypted");
		EmailAssert.assertThat(emailParsedFromMsg).hasOnlyRecipients(new Recipient("Benny Bottema", "benny@bennybottema.com", TO));

		assertThat(normalizeNewlines(emailParsedFromMsg.getPlainText())).isEqualTo("This is an encrypted message, with one embedded image and one dummy \n"
				+ "attachment.\n"
				+ "\n"
				+ "For testing purposes in the Simple Java Mail project.\n"
				+ "\n");

		assertThat(emailParsedFromMsg.getEmbeddedImages()).hasSize(1);
		AttachmentResource embeddedImg = emailParsedFromMsg.getEmbeddedImages().get(0);
		assertThat(embeddedImg.getName()).isEqualTo("part1.0B245DA7.F5872CD5@bennybottema.com");
		assertThat(embeddedImg.getDataSource().getName()).isEqualTo("module_architecture.png");
		assertThat(embeddedImg.getDataSource().getContentType()).isEqualTo("image/png");
		assertThat(emailParsedFromMsg.getHTMLText()).contains(format("<img src=\"cid:%s\"", embeddedImg.getName()));

		assertThat(emailParsedFromMsg.getAttachments()).hasSize(3);
		assertThat(emailParsedFromMsg.getAttachments()).extracting("name").containsExactlyInAnyOrder("smime.p7m", "smime.p7s", "03-07-2005 errata SharnErrata.pdf");
		assertThat(emailParsedFromMsg.getDecryptedAttachments()).hasSize(3);
		assertThat(emailParsedFromMsg.getDecryptedAttachments()).extracting("name").containsExactlyInAnyOrder("smime.p7s", "signed-email.eml", "03-07-2005 errata SharnErrata.pdf");

		EmailAssert.assertThat(emailParsedFromMsg).hasOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder()
				.smimeMode(SmimeMode.SIGNED_ENCRYPTED)
				.smimeMime("application/pkcs7-mime")
				.smimeType("enveloped-data")
				.smimeName("smime.p7m")
				.build());
		EmailAssert.assertThat(emailParsedFromMsg.getSmimeSignedEmail()).hasOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder()
				.smimeMode(SmimeMode.SIGNED)
				.smimeMime("multipart/signed")
				.smimeProtocol("application/pkcs7-signature")
				.smimeMicalg("sha-512")
				.smimeSignatureValid(true)
				.smimeSignedBy("Benny Bottema")
				.build());
	}

	@Nonnull
	private Pkcs12Config loadPkcs12KeyStore()
			throws FileNotFoundException {
		return new Pkcs12Config(
				new FileInputStream(RESOURCES_PKCS + "/smime_keystore.pkcs12"),
				"letmein".toCharArray(),
				"smime_test_user_alias",
				"letmein".toCharArray()
		);
	}
}