package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.mail.smime.SMIMESignedGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookMessage;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookSmime.OutlookSmimeApplicationSmime;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.smimesupport.builder.SmimeParseResultBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Properties;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.simplejavamail.api.email.OriginalSmimeDetails.DecryptionStatus.DECRYPTED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.DecryptionStatus.KEY_MISSING;
import static org.simplejavamail.api.email.OriginalSmimeDetails.DecryptionStatus.NOT_ENCRYPTED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.ENCRYPTED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.SIGNED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.ERROR;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.UNKNOWN;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.VALID;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class SmimeParameterCapitalizationTest {

	@ParameterizedTest
	@CsvSource({
			"application/pkcs7-mime, signed-data",
			"application/pkcs7-mime, SIGNED-DATA",
			"application/pkcs7-mime, Signed-Data",
			"application/x-pkcs7-mime, SIGNED-DATA"
	})
	void opaqueSignedMessagesVerifyWithoutRequiringADecryptionKey(final String mediaType, final String smimeType) throws Exception {
		final byte[] source = opaqueSignedMessage(mediaType, smimeType);
		final Email email = SimpleJavaMail.fromDefaults().converter().fromEml(new ByteArrayInputStream(source)).toEmail();

		assertThat(email.getPlainText()).contains("Opaque signed content");
		assertThat(email.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(SIGNED);
		assertThat(email.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(VALID);
		assertThat(email.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(NOT_ENCRYPTED);
		assertThat(email.getOriginalSmimeDetails().getSmimeType()).isEqualTo(smimeType);
		assertThat(email.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(source);
	}

	@ParameterizedTest
	@CsvSource({
			"application/pkcs7-mime, enveloped-data",
			"application/pkcs7-mime, ENVELOPED-DATA",
			"application/pkcs7-mime, Enveloped-Data",
			"application/x-pkcs7-mime, ENVELOPED-DATA"
	})
	void encryptedMessagesRetainTheirModeWithAndWithoutAKey(final String mediaType, final String smimeType) throws Exception {
		final byte[] original = Files.readAllBytes(Path.of(determineResourceFolder("simple-java-mail"),
				"test/resources/pkcs12/test messages/S_MIME test message encrypted.eml"));
		final byte[] source = replaceDeclaration(original, mediaType, "enveloped-data", smimeType);
		final Email unopened = EmailConverter.emlToEmail(new ByteArrayInputStream(source));
		final Email opened = SimpleJavaMail.fromDefaults().converter().fromEml(new ByteArrayInputStream(source))
				.withSmimeDecryption(loadPkcs12KeyStore()).toEmail();

		assertThat(unopened.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(ENCRYPTED);
		assertThat(unopened.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(KEY_MISSING);
		assertThat(unopened.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(UNKNOWN);
		assertThat(unopened.getOriginalSmimeDetails().getSmimeType()).isEqualTo(smimeType);
		assertThat(unopened.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(source);
		assertThat(opened.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(ENCRYPTED);
		assertThat(opened.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(DECRYPTED);
		assertThat(opened.getPlainText()).contains("This is an encrypted message");
		assertThat(opened.getOriginalSmimeDetails().getSmimeType()).isEqualTo(smimeType);
		assertThat(opened.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(source);
	}

	@ParameterizedTest
	@ValueSource(strings = {"signed-data", "SIGNED-DATA", "Signed-Data"})
	void outlookOpaqueSignedAttachmentsUseTheDeclaredOperation(final String smimeType) throws Exception {
		final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()),
				new ByteArrayInputStream(opaqueSignedMessage("application/pkcs7-mime", smimeType)));
		final AttachmentResource attachment;
		try (InputStream payload = message.getInputStream()) {
			attachment = AttachmentResource.builder().withName("smime.p7m")
					.withDataSource(new ByteArrayDataSource(payload.readAllBytes(), "application/pkcs7-mime")).build();
		}
		final OutlookSmimeApplicationSmime declaration = mock(OutlookSmimeApplicationSmime.class);
		when(declaration.getSmimeMime()).thenReturn("application/pkcs7-mime");
		when(declaration.getSmimeType()).thenReturn(smimeType);
		final OutlookMessage outlook = mock(OutlookMessage.class);
		when(outlook.getSmimeMime()).thenReturn(declaration);

		final SmimeParseResultBuilder parsed = new SMIMESupport().decryptAttachments(List.of(attachment), outlook, null);

		assertThat(parsed.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(SIGNED);
		assertThat(parsed.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(VALID);
		assertThat(parsed.getOriginalSmimeDetails().getSmimeType()).isEqualTo(smimeType);
		assertThat(parsed.getSmimeSignedOrEncryptedEmail()).isNotNull();
		assertThat(EmailConverter.emlToEmail(parsed.getSmimeSignedOrEncryptedEmail().readAllData(ISO_8859_1)).getPlainText())
				.contains("Opaque signed content");
	}

	@Test
	void brokenOpaqueSignedDataIsNotReportedAsMissingDecryptionMaterial() {
		final String source = "From: sender@example.org\r\nContent-Type: application/pkcs7-mime; smime-type=SIGNED-DATA\r\n"
				+ "Content-Transfer-Encoding: base64\r\n\r\nAQID\r\n";
		final Email email = EmailConverter.emlToEmail(source);

		assertThat(email.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(SIGNED);
		assertThat(email.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(ERROR);
		assertThat(email.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(NOT_ENCRYPTED);
		assertThat(email.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(source.getBytes(ISO_8859_1));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"unrecognized", "SIGNED-DATA-EXTRA"})
	void absentOrUnknownValuesKeepTheirExistingUnopenedOperation(final String smimeType) {
		final String parameter = smimeType == null ? "" : "; smime-type=\"" + smimeType + "\"";
		final String source = "From: sender@example.org\r\nContent-Type: application/pkcs7-mime" + parameter + "\r\n"
				+ "Content-Transfer-Encoding: base64\r\n\r\nAQID\r\n";
		final Email email = EmailConverter.emlToEmail(source);

		assertThat(email.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(KEY_MISSING);
		assertThat(email.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(UNKNOWN);
		assertThat(email.getOriginalSmimeDetails().getSmimeType()).isEqualTo(smimeType);
		assertThat(email.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(source.getBytes(ISO_8859_1));
	}

	private static byte[] opaqueSignedMessage(final String mediaType, final String smimeType) throws Exception {
		final Pkcs12Config keys = loadPkcs12KeyStore();
		final KeyStore store = KeyStore.getInstance("PKCS12");
		store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
		final X509Certificate certificate = (X509Certificate) store.getCertificate(keys.getKeyAlias());
		final SMIMESignedGenerator generator = new SMIMESignedGenerator();
		generator.addCertificates(new JcaCertStore(List.of(certificate)));
		generator.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder().setProvider(SmimeCryptoSupport.BOUNCY_CASTLE)
				.build("SHA256withRSA", (PrivateKey) store.getKey(keys.getKeyAlias(), keys.getKeyPassword()), certificate));
		final MimeBodyPart text = new MimeBodyPart();
		text.setText("Opaque signed content", "UTF-8");
		final MimeMultipart multipart = new MimeMultipart();
		multipart.addBodyPart(text);
		final MimeBodyPart content = new MimeBodyPart();
		content.setContent(multipart);
		final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		message.setFrom("sender@example.org");
		message.setDataHandler(generator.generateEncapsulated(content).getDataHandler());
		message.saveChanges();
		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		message.writeTo(output);
		return replaceDeclaration(output.toByteArray(), mediaType, "signed-data", smimeType);
	}

	private static byte[] replaceDeclaration(final byte[] source, final String mediaType, final String originalSmimeType,
			final String smimeType) {
		return new String(source, ISO_8859_1)
				.replaceFirst("(?i)(Content-Type: )application/pkcs7-mime", "$1" + mediaType)
				.replaceFirst("smime-type=" + originalSmimeType, "smime-type=" + smimeType).getBytes(ISO_8859_1);
	}
}
