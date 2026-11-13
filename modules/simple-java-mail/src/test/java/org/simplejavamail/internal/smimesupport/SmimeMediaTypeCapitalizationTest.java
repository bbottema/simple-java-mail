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
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.converter.EmailConverter;
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
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.SignatureStatus;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
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
        final Email email = SimpleJavaMail.fromDefaults().converter().fromEml(source).toEmail();
        assertThat(email.getPlainText()).contains("OpenSSL independent body");
        assertThat(email.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        assertSourceDeclaration(email, mediaType, protocol);
        assertOriginal(email, source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/pkcs7-mime", "Application/PKCS7-Mime", "APPLICATION/X-PKCS7-MIME"})
    void opaqueSignedContentRetainsVerification(final String mediaType) throws Exception {
        final byte[] source = opaqueSignedMessage(mediaType);
        final Email email = EmailConverter.emlToEmail(new ByteArrayInputStream(source));
        assertThat(email.getPlainText()).contains("Opaque signed content");
        assertThat(email.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        assertOriginal(email, source);
    }

    @ParameterizedTest
    // The lowercase encrypted declaration is covered with the same input and assertions by SmimeParameterCapitalizationTest.
    @ValueSource(strings = {"Application/PKCS7-Mime", "APPLICATION/X-PKCS7-MIME"})
    void encryptedContentRetainsMissingKeyAndSuccessfulDecryptionResults(final String mediaType) throws Exception {
        final byte[] original = Files.readAllBytes(resource("pkcs12/test messages/S_MIME test message encrypted.eml"));
        final byte[] source = replaceMediaType(original, "application/pkcs7-mime", mediaType);
        final Email unopened = EmailConverter.emlToEmail(new ByteArrayInputStream(source));
        final Email opened = SimpleJavaMail.fromDefaults().converter().fromEml(source).withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
        assertThat(unopened.getInspection().getSmime().getDecryptions()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.UNAVAILABLE));
        assertThat(opened.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        assertThat(opened.getPlainText()).contains("This is an encrypted message");
        assertOriginal(unopened, source);
        assertOriginal(opened, source);
    }

    @Test
    void capitalizationDoesNotConcealInvalidSignaturesOrBrokenCiphertext() throws Exception {
        final byte[] signed = detachedMessage("Multipart/Signed", "Application/X-PKCS7-Signature");
        final byte[] tampered = new String(signed, ISO_8859_1)
                .replace("OpenSSL independent body", "OpenSSL compromised body").getBytes(ISO_8859_1);
        assertThat(EmailConverter.emlToEmail(new ByteArrayInputStream(tampered)).getInspection().getPlainText().getSignatureStatus())
                .isEqualTo(SignatureStatus.INVALID);
        final String broken = "From: sender@example.org\r\nContent-Type: Application/PKCS7-Mime; smime-type=enveloped-data\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\nAQID\r\n";
        final Email email = SimpleJavaMail.fromDefaults().converter().fromEml(broken).withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
        assertThat(email.getInspection().getSmime().getDecryptions()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.FAILED));
        assertThat(email.getProblems()).isNotEmpty();
    }

    @ParameterizedTest
    @CsvSource({"Application/PKCS7-Mime, enveloped-data", "APPLICATION/X-PKCS7-MIME, signed-data"})
    void recognitionUsesTheSameMediaTypeMeaning(final String mediaType, final String smimeType) throws Exception {
        assertThat(SmimeRecognitionUtil.isSmimeContentType(new ContentType(mediaType + "; smime-type=" + smimeType))).isTrue();
        final byte[] source = ("Content-Type: " + mediaType + "; smime-type=" + smimeType + "\r\n\r\nAQID")
                .getBytes(ISO_8859_1);
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(source));
        assertThat(new SMIMESupport().openIncomingLayer(message.getSession(), message, null).isRecognized()).isTrue();
    }

    @Test
    void unrelatedSignedProtocolsDoNotBecomeSmime() throws Exception {
        assertThat(SmimeRecognitionUtil.isSmimeContentType(new ContentType("Multipart/Signed; protocol=\"application/pgp-signature\""))).isFalse();
        assertThat(SmimeRecognitionUtil.isSmimeContentType(new ContentType("Application/Octet-Stream"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"multipart/signed", "Multipart/Signed", "MULTIPART/SIGNED"})
    void brokenSignedInputRetainsItsActualDeclarationAndProblems(final String mediaType) throws Exception {
        final byte[] source = ("Content-Type: " + mediaType + "; protocol=\"application/pkcs7-signature\"; micalg=sha-256; boundary=missing\r\n\r\nbroken")
                .getBytes(ISO_8859_1);
        final Email email = SimpleJavaMail.fromDefaults().converter().fromEml(source).toEmail();
        assertSourceDeclaration(email, mediaType, "application/pkcs7-signature");
        assertOriginal(email, source);
        assertThat(email.getProblems()).isNotEmpty();
    }

    private static void assertSourceDeclaration(final Email email, final String mediaType, final String parameter) {
        assertThat(email.getSourceData().getHeaders()).filteredOn(header -> header.getName().equalsIgnoreCase("Content-Type"))
                .singleElement().satisfies(header -> assertThat(header.getRawValue()).contains(mediaType).contains(parameter));
    }

    private static void assertOriginal(final Email email, final byte[] source) throws Exception {
        assertThat(email.getInspection().getOriginalMessage()).isNotNull();
        assertThat(email.getInspection().getOriginalMessage().readAllBytes()).containsExactly(source);
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
