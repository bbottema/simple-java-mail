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
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.converter.EmailConverter;
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
import jakarta.activation.DataHandler;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.SignatureVerification;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class SmimeParameterCapitalizationTest {
    @ParameterizedTest
    @CsvSource({"application/pkcs7-mime, signed-data", "application/pkcs7-mime, SIGNED-DATA",
            "application/pkcs7-mime, Signed-Data", "application/x-pkcs7-mime, SIGNED-DATA"})
    void opaqueSignedMessagesVerifyWithoutRequiringADecryptionKey(final String mediaType, final String smimeType) throws Exception {
        final byte[] source = opaqueSignedMessage(mediaType, smimeType);
        final Email email = SimpleJavaMail.fromDefaults().converter().fromEml(source).toEmail();
        assertThat(email.getPlainText()).contains("Opaque signed content");
        assertThat(email.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        assertThat(email.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.NOT_ENCRYPTED);
        assertThat(email.getInspection().getSmime().getDecryptions()).isEmpty();
        assertDeclaration(email, smimeType);
        assertOriginal(email, source);
    }

    @ParameterizedTest
    @CsvSource({"application/pkcs7-mime, enveloped-data", "application/pkcs7-mime, ENVELOPED-DATA",
            "application/pkcs7-mime, Enveloped-Data", "application/x-pkcs7-mime, ENVELOPED-DATA"})
    void encryptedMessagesRetainTheirProcessingWithAndWithoutAKey(final String mediaType, final String smimeType) throws Exception {
        final byte[] original = Files.readAllBytes(Path.of(determineResourceFolder("simple-java-mail"),
                "test/resources/pkcs12/test messages/S_MIME test message encrypted.eml"));
        final byte[] source = replaceDeclaration(original, mediaType, "enveloped-data", smimeType);
        final Email unopened = EmailConverter.emlToEmail(new ByteArrayInputStream(source));
        final Email opened = SimpleJavaMail.fromDefaults().converter().fromEml(source).withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
        assertThat(unopened.getInspection().getSmime().getDecryptions()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.UNAVAILABLE));
        assertThat(opened.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        assertThat(opened.getPlainText()).contains("This is an encrypted message");
        assertDeclaration(unopened, smimeType);
        assertDeclaration(opened, smimeType);
        assertOriginal(unopened, source);
        assertOriginal(opened, source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"signed-data", "SIGNED-DATA", "Signed-Data"})
    void nativeAttachmentPayloadUsesItsDeclaredOperation(final String smimeType) throws Exception {
        final MimeMessage protectedSource = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(opaqueSignedMessage("application/pkcs7-mime", smimeType)));
        final byte[] payload;
        try (InputStream input = protectedSource.getInputStream()) { payload = input.readAllBytes(); }
        final MimeMessage wrapper = new MimeMessage(protectedSource.getSession());
        wrapper.setDataHandler(new DataHandler(new ByteArrayDataSource(payload, "application/pkcs7-mime")));
        wrapper.setHeader("Content-Type", "application/pkcs7-mime; smime-type=" + smimeType);
        wrapper.saveChanges();
        final var opened = new SMIMESupport().openIncomingLayer(wrapper.getSession(), wrapper, null);
        assertThat(opened.isOpened()).isTrue();
        assertThat(opened.isAuthoredHeaders()).isFalse();
        assertThat(opened.getSignatures()).singleElement()
                .satisfies(signature -> assertThat(signature.getStatus()).isEqualTo(SignatureVerification.VERIFIED));
        assertThat(EmailConverter.mimeMessageToEmail(opened.getEffectiveMimeMessage()).getPlainText()).contains("Opaque signed content");
    }

    @Test
    void brokenOpaqueSignedDataIsNotReportedAsMissingDecryptionMaterial() throws Exception {
        final String source = "From: sender@example.org\r\nContent-Type: application/pkcs7-mime; smime-type=SIGNED-DATA\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\nAQID\r\n";
        final Email email = EmailConverter.emlToEmail(source);
        assertThat(email.getInspection().getSmime().getDecryptions()).isEmpty();
        assertThat(email.getInspection().getSmime().getSignatures()).isEmpty();
        assertThat(email.getInspection().getSmime().isInspectionComplete()).isFalse();
        assertThat(email.getProblems()).isNotEmpty();
        assertOriginal(email, source.getBytes(ISO_8859_1));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unrecognized", "SIGNED-DATA-EXTRA"})
    void absentOrUnknownValuesDoNotFabricateAnEncryptionObservation(final String smimeType) throws Exception {
        final String parameter = smimeType == null ? "" : "; smime-type=\"" + smimeType + "\"";
        final String source = "From: sender@example.org\r\nContent-Type: application/pkcs7-mime" + parameter + "\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\nAQID\r\n";
        final Email email = EmailConverter.emlToEmail(source);
        assertThat(email.getInspection().getSmime().isInspectionComplete()).isFalse();
        assertThat(email.getInspection().getSmime().getDecryptions()).isEmpty();
        assertOriginal(email, source.getBytes(ISO_8859_1));
    }

    private static void assertDeclaration(final Email email, final String smimeType) {
        assertThat(email.getSourceData().getHeaders()).filteredOn(header -> header.getName().equalsIgnoreCase("Content-Type"))
                .singleElement().satisfies(header -> assertThat(header.getRawValue()).contains(smimeType));
    }

    private static void assertOriginal(final Email email, final byte[] source) throws Exception {
        assertThat(email.getInspection().getOriginalMessage()).isNotNull();
        assertThat(email.getInspection().getOriginalMessage().readAllBytes()).containsExactly(source);
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
