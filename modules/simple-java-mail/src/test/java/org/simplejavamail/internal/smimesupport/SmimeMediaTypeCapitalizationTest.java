package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.mail.smime.SMIMESignedGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.OriginalSmimeDetails.DecryptionStatus.DECRYPTED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.DecryptionStatus.FAILED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.DecryptionStatus.KEY_MISSING;
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.ENCRYPTED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.PLAIN;
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.SIGNED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.INVALID;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.UNKNOWN;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.VALID;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class SmimeMediaTypeCapitalizationTest {

	@ParameterizedTest
	@CsvSource({
			"multipart/signed, application/x-pkcs7-signature",
			"Multipart/Signed, application/x-pkcs7-signature",
			"MULTIPART/SIGNED, APPLICATION/X-PKCS7-SIGNATURE",
			"multipart/signed, Application/PKCS7-Signature"
	})
	void detachedSignatureRetainsItsResultAndSourceBytes(final String mediaType, final String protocol) throws Exception {
		final byte[] source = detachedMessage(mediaType, protocol);
		final Email email = SimpleJavaMail.fromDefaults().converter().fromEml(new ByteArrayInputStream(source)).toEmail();

		assertThat(email.getPlainText()).contains("OpenSSL independent body");
		assertThat(email.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(SIGNED);
		assertThat(email.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(VALID);
		assertThat(email.getOriginalSmimeDetails().getSmimeMime()).isEqualTo(mediaType);
		assertThat(email.getOriginalSmimeDetails().getSmimeProtocol()).isEqualTo(protocol);
		assertThat(email.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(source);
	}

	@ParameterizedTest
	@ValueSource(strings = {"application/pkcs7-mime", "Application/PKCS7-Mime", "APPLICATION/X-PKCS7-MIME"})
	void opaqueSignedContentRetainsVerification(final String mediaType) throws Exception {
		final byte[] source = opaqueSignedMessage(mediaType);
		final Email email = EmailConverter.emlToEmail(new ByteArrayInputStream(source));

		assertThat(email.getPlainText()).contains("Opaque signed content");
		assertThat(email.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(SIGNED);
		assertThat(email.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(VALID);
		assertThat(email.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(source);
	}

	@ParameterizedTest
	@ValueSource(strings = {"application/pkcs7-mime", "Application/PKCS7-Mime", "APPLICATION/X-PKCS7-MIME"})
	void encryptedContentRetainsMissingKeyAndSuccessfulDecryptionResults(final String mediaType) throws Exception {
		final byte[] original = Files.readAllBytes(resource("pkcs12/test messages/S_MIME test message encrypted.eml"));
		final byte[] source = replaceMediaType(original, "application/pkcs7-mime", mediaType);
		final Email unopened = EmailConverter.emlToEmail(new ByteArrayInputStream(source));
		final Email opened = SimpleJavaMail.fromDefaults().converter().fromEml(new ByteArrayInputStream(source))
				.withSmimeDecryption(loadPkcs12KeyStore()).toEmail();

		assertThat(unopened.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(ENCRYPTED);
		assertThat(unopened.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(KEY_MISSING);
		assertThat(unopened.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(UNKNOWN);
		assertThat(unopened.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(source);
		assertThat(opened.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(ENCRYPTED);
		assertThat(opened.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(DECRYPTED);
		assertThat(opened.getPlainText()).contains("This is an encrypted message");
		assertThat(opened.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(source);
	}

	@Test
	void capitalizationDoesNotConcealInvalidSignaturesOrBrokenCiphertext() throws Exception {
		final byte[] signed = detachedMessage("Multipart/Signed", "Application/X-PKCS7-Signature");
		final byte[] tampered = new String(signed, ISO_8859_1)
				.replace("OpenSSL independent body", "OpenSSL compromised body").getBytes(ISO_8859_1);
		assertThat(EmailConverter.emlToEmail(new ByteArrayInputStream(tampered)).getOriginalSmimeDetails().getVerificationStatus())
				.isEqualTo(INVALID);

		final String broken = "From: sender@example.org\r\n"
				+ "Content-Type: Application/PKCS7-Mime; smime-type=enveloped-data\r\n"
				+ "Content-Transfer-Encoding: base64\r\n\r\nAQID\r\n";
		final Email email = SimpleJavaMail.fromDefaults().converter().fromEml(broken)
				.withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
		assertThat(email.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(ENCRYPTED);
		assertThat(email.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(FAILED);
		assertThat(email.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(UNKNOWN);
	}

	@ParameterizedTest
	@CsvSource({"Application/PKCS7-Mime, enveloped-data, ENCRYPTED", "APPLICATION/X-PKCS7-MIME, signed-data, SIGNED"})
	void metadataAndAttachmentRecognitionUseTheSameMediaTypeMeaning(final String mediaType, final String smimeType,
			final SmimeMode mode) {
		assertThat(OriginalSmimeDetailsImpl.builder().smimeMode(PLAIN).smimeMime(mediaType).smimeType(smimeType).build().getSmimeMode())
				.isEqualTo(mode);
		assertThat(new SMIMESupport().isSmimeAttachment(AttachmentResource.builder()
				.withDataSource(new ByteArrayDataSource(new byte[] {1}, mediaType)).build())).isTrue();
	}

	@Test
	void unrelatedSignedProtocolsDoNotBecomeSmime() throws Exception {
		assertThat(SmimeRecognitionUtil.isSmimeContentType(new ContentType(
				"Multipart/Signed; protocol=\"application/pgp-signature\""))).isFalse();
		assertThat(SmimeRecognitionUtil.isSmimeContentType(new ContentType("Application/Octet-Stream"))).isFalse();
	}

	private static byte[] detachedMessage(final String mediaType, final String protocol) throws Exception {
		final byte[] original = Base64.getMimeDecoder().decode(Files.readAllBytes(resource("smime/openssl-detached-signed.eml.b64")));
		return new String(replaceMediaType(original, "multipart/signed", mediaType), ISO_8859_1)
				.replaceFirst("protocol=\"application/x-pkcs7-signature\"", "protocol=\"" + protocol + "\"").getBytes(ISO_8859_1);
	}

	private static byte[] opaqueSignedMessage(final String mediaType) throws Exception {
		final Pkcs12Config keys = loadPkcs12KeyStore();
		final KeyStore store = KeyStore.getInstance("PKCS12");
		store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
		final X509Certificate certificate = (X509Certificate) store.getCertificate(keys.getKeyAlias());
		final SMIMESignedGenerator generator = new SMIMESignedGenerator();
		generator.addCertificates(new JcaCertStore(List.of(certificate)));
		generator.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder().setProvider(SmimeCryptoSupport.BOUNCY_CASTLE)
				.build("SHA256withRSA", (PrivateKey) store.getKey(keys.getKeyAlias(), keys.getKeyPassword()), certificate));
		final MimeBodyPart plain = new MimeBodyPart();
		plain.setText("Opaque signed content", "UTF-8");
		final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		message.setFrom("sender@example.org");
		message.setDataHandler(generator.generateEncapsulated(plain).getDataHandler());
		message.saveChanges();
		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		message.writeTo(output);
		return replaceMediaType(output.toByteArray(), "application/pkcs7-mime", mediaType);
	}

	private static byte[] replaceMediaType(final byte[] source, final String originalType, final String replacement) {
		return new String(source, ISO_8859_1).replaceFirst("(?i)(Content-Type: )" + originalType, "$1" + replacement)
				.getBytes(ISO_8859_1);
	}

	private static Path resource(final String relative) {
		return Path.of(determineResourceFolder("simple-java-mail"), "test/resources", relative);
	}
}
