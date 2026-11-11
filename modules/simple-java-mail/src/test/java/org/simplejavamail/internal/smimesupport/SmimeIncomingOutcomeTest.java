package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.mail.smime.SMIMESignedGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.SignatureVerification;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.converter.EmailConverter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Properties;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;
import static org.simplejavamail.api.email.DecryptionStatus.UNAVAILABLE;
import static org.simplejavamail.api.email.DecryptionStatus.FAILED;
import static org.simplejavamail.internal.smimesupport.SmimeInspectionAssertions.*;

class SmimeIncomingOutcomeTest {

	@Test
	void unopenedSignedCiphertextKeepsUnknownSignatureAcrossImportCopyAndSerialization(@TempDir final Path directory) throws Exception {
		final byte[] source = encrypted(signedFixture());
		final Path file = Files.write(directory.resolve("signed-and-encrypted.eml"), source);
		final Email email = SimpleJavaMail.fromDefaults().converter().fromEml(file).toEmail();

		assertUnopened(email, UNAVAILABLE, source);
		assertThat(email.getInspection().getSmime().getDecryptions()).singleElement().satisfies(attempt -> assertThat(attempt.getFailureReason()).isNotEmpty());
		final byte[] borrowed = email.getInspection().getOriginalMessage().readAllBytes();
		borrowed[0] ^= 1;
		assertUnopened(email, UNAVAILABLE, source);
		final Email copied = SimpleJavaMail.fromDefaults().emailBuilder().copying(email).buildEmail();
		assertUnopened(copied, UNAVAILABLE, source);
		assertUnopened(roundTrip(copied), UNAVAILABLE, source);
	}

	@Test
	void unopenedMimeInputReportsTheSameFactsThroughStaticAndConfiguredImports() throws Exception {
		final byte[] source = encrypted(signedFixture());
		final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(source));

		assertUnopened(EmailConverter.mimeMessageToEmail(message), UNAVAILABLE, null);
		assertUnopened(SimpleJavaMail.fromDefaults().converter().fromMimeMessage(message).toEmail(), UNAVAILABLE, null);
	}

	@Test
	void actualDecryptionFailureKeepsUnknownSignatureAndProtectedBytes() throws Exception {
		final byte[] source = ("From: sender@example.org\r\nSubject: unopened\r\n"
				+ "Content-Type: application/pkcs7-mime; smime-type=enveloped-data; name=smime.p7m\r\n"
				+ "Content-Transfer-Encoding: base64\r\n\r\nAQID\r\n").getBytes(UTF_8);
		final Email email = importWithKeys(source);

		assertUnopened(email, FAILED, source);
		assertThat(email.getInspection().getSmime().getDecryptions()).singleElement().satisfies(attempt -> assertThat(attempt.getFailureReason()).isNotEmpty());
		assertUnopened(roundTrip(email), FAILED, source);
	}

	@Test
	void successfullyInspectedUnsignedContentIsNotSigned() throws Exception {
		final MimeBodyPart plain = new MimeBodyPart();
		plain.setText("Readable unsigned content", "UTF-8");
		final byte[] source = encrypted(plain);
		final Email email = importWithKeys(source);

		assertThat(email.getPlainText()).contains("Readable unsigned content");
		assertDecryptionObservation(email, DecryptionStatus.DECRYPTED);
		assertThat(email.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
		assertThat(email.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNSIGNED);
		assertThat(email.getInspection().getSmime().getSignatures()).allSatisfy(signature -> assertThat(signature.getFailureReason()).isNull());
		assertThat(email.getInspection().getOriginalMessage().readAllBytes()).containsExactly(source);
	}

	@Test
	void successfulDecryptionAndVerificationEstablishBothFacts() throws Exception {
		final byte[] source = encrypted(signedFixture());
		final Email email = importWithKeys(source);

		assertThat(email.getPlainText()).contains("For testing purposes");
		assertDecryptionObservation(email, DecryptionStatus.DECRYPTED);
		assertThat(email.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
		assertThat(email.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
		assertSignatureObservation(email, SignatureVerification.VERIFIED);
		assertThat(email.getInspection().getOriginalMessage().readAllBytes()).containsExactly(source);
	}

	@Test
	void tamperedSignedContentStillDecryptsButRetainsInvalidSignatureAndProtectedBytes() throws Exception {
		final ByteArrayOutputStream signedBytes = new ByteArrayOutputStream();
		signedFixture().writeTo(signedBytes);
		final String original = signedBytes.toString(UTF_8);
		final String tampered = original.replaceFirst("dummy=20(\\r?\\n)attachment\\.", "dummy=20$1BROKEN attachment.");
		assertThat(tampered).isNotEqualTo(original);
		final byte[] source = encrypted(new MimeBodyPart(new ByteArrayInputStream(tampered.getBytes(UTF_8))));
		final Email email = importWithKeys(source);

		assertDecryptionObservation(email, DecryptionStatus.DECRYPTED);
		assertThat(email.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
		assertThat(email.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.INVALID);
		assertSignatureObservation(email, SignatureVerification.INVALID);
		assertThat(email.getInspection().getSmime().getSignatures()).singleElement().satisfies(signature -> assertThat(signature.getFailureReason()).isNotEmpty());
		assertThat(email.getPlainText()).contains("BROKEN attachment");
		assertThat(email.getInspection().getOriginalMessage().readAllBytes()).containsExactly(source);
	}

	@Test
	void verificationErrorAfterDecryptionRetainsReadableContentAndEstablishedDecryption() throws Exception {
		final KeyStore store = keyStore();
		final Pkcs12Config keys = loadPkcs12KeyStore();
		final SMIMESignedGenerator generator = new SMIMESignedGenerator();
		generator.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder()
				.setProvider(SmimeCryptoSupport.BOUNCY_CASTLE)
				.build("SHA256withRSA", (PrivateKey) store.getKey(keys.getKeyAlias(), keys.getKeyPassword()),
						(X509Certificate) store.getCertificate(keys.getKeyAlias())));
		// No certificate is embedded: the signature has a signer, but cannot be checked with the supplied message facts.
		final MimeBodyPart plain = new MimeBodyPart();
		plain.setText("Readable content despite missing signer certificate", "UTF-8");
		final MimeBodyPart signed = new MimeBodyPart();
		signed.setContent(generator.generate(plain));
		final byte[] source = encrypted(signed);
		final Email email = importWithKeys(source);

		assertVerificationError(email, source, "Readable content despite missing signer certificate", SignatureStatus.UNVERIFIED);
		assertVerificationError(roundTrip(SimpleJavaMail.fromDefaults().emailBuilder().copying(email).buildEmail()),
				source, "Readable content despite missing signer certificate", SignatureStatus.UNVERIFIED);
	}

	@Test
	void unreadableInnerSignatureDoesNotTurnSuccessfulDecryptionIntoFailure() throws Exception {
		final MimeBodyPart signed = signedFixture();
		final MimeMultipart multipart = (MimeMultipart) signed.getContent();
		final MimeBodyPart signature = (MimeBodyPart) multipart.getBodyPart(1);
		signature.setDataHandler(new jakarta.activation.DataHandler(
				new ByteArrayDataSource(new byte[] {1, 2, 3}, "application/pkcs7-signature")));
		signature.setHeader("Content-Transfer-Encoding", "base64");
		signed.setContent(multipart);
		final byte[] source = encrypted(signed);
		final Email email = importWithKeys(source);

		assertVerificationError(email, source, "For testing purposes", SignatureStatus.UNKNOWN);
		assertThat(email.getAttachments()).extracting("name").doesNotContain("smime.p7m", "smime.p7s");
	}

	@Test
	void ordinaryPlainInputRemainsInspectedUnsignedContent() {
		final Email email = EmailConverter.emlToEmail("From: sender@example.org\r\n\r\nPlain message");

		assertThat(email.getPlainText()).isEqualTo("Plain message");
		assertThat(email.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNSIGNED);
		assertThat(email.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.NOT_ENCRYPTED);
	}

	private static void assertUnopened(final Email email, final DecryptionStatus decryption, final byte[] source) {
        assertUnopenedFamily(email, decryption);
        assertThat(email.getInspection().getSmime().getDecryptions()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getFailureReason()).isNotEmpty());
        if (source == null) assertThat(email.getInspection().getOriginalMessage()).isNull();
        else assertOriginal(email, source);
        assertThat(email.getAttachments()).isEmpty();
    }

    private static void assertVerificationError(final Email email, final byte[] source, final String content,
            final SignatureStatus expected) {
        assertDecryptionObservation(email, DecryptionStatus.DECRYPTED);
        assertPlainProtection(email, expected, EncryptionStatus.DECRYPTED);
        if (expected == SignatureStatus.UNVERIFIED) {
            assertSignatureObservation(email, SignatureVerification.UNVERIFIED);
            assertThat(email.getInspection().getSmime().getSignatures()).singleElement()
                    .satisfies(signature -> assertThat(signature.getFailureReason()).isNotEmpty());
        } else {
            assertThat(email.getInspection().getSmime().getSignatures()).isEmpty();
            assertThat(email.getInspection().getSmime().isInspectionComplete()).isFalse();
        }
        assertOriginal(email, source);
        assertThat(email.getPlainText()).contains(content);
    }

	private static Email importWithKeys(final byte[] source) {
		return SimpleJavaMail.fromDefaults().converter().fromEml(new ByteArrayInputStream(source))
				.withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
	}

	private static MimeBodyPart signedFixture() throws Exception {
		final Path path = Path.of(determineResourceFolder("simple-java-mail"),
				"test/resources/pkcs12/test messages/S_MIME test message signed.eml");
		return new MimeBodyPart(new ByteArrayInputStream(Files.readAllBytes(path)));
	}

	private static byte[] encrypted(final MimeBodyPart content) throws Exception {
		final Pkcs12Config keys = loadPkcs12KeyStore();
		final X509Certificate certificate = (X509Certificate) keyStore().getCertificate(keys.getKeyAlias());
		final MimeBodyPart encrypted = SmimeCryptoSupport.encrypt(content, certificate,
				SmimeCryptoSupport.DEFAULT_KEY_ENCAPSULATION_ALGORITHM, SmimeCryptoSupport.DEFAULT_CIPHER);
		final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		message.setFrom("sender@example.org");
		message.setSubject("Incoming protected content");
		message.setDataHandler(encrypted.getDataHandler());
		message.saveChanges();
		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		message.writeTo(output);
		return output.toByteArray();
	}

	private static KeyStore keyStore() throws Exception {
		final Pkcs12Config keys = loadPkcs12KeyStore();
		final KeyStore store = KeyStore.getInstance("PKCS12");
		store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
		return store;
	}

	private static Email roundTrip(final Email email) throws Exception {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
			output.writeObject(email);
		}
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			return (Email) input.readObject();
		}
	}
}
