package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.SignatureVerification;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.converter.EmailConverter;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Properties;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.internal.smimesupport.SmimeInspectionAssertions.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class OutlookSmimeDecryptionStatusTest {

	@Test
	void outlookMessageEnvelopeReportsSuccessfulDecryptionLikeItsEmlCounterpart() {
		final Pkcs12Config keys = loadPkcs12KeyStore();
		final Email outlook = SimpleJavaMail.fromDefaults().converter().fromOutlookMsg(fixture("encrypted.msg"))
				.withSmimeDecryption(keys).toEmail();
		final Email eml = EmailConverter.emlToEmail(fixture("encrypted.eml").toFile(), keys);

		assertThat(outlook.getPlainText()).contains("For testing purposes");
		assertThat(outlook.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
		assertThat(eml.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
		assertDecryptionObservation(outlook, DecryptionStatus.DECRYPTED);
		assertDecryptionObservation(eml, DecryptionStatus.DECRYPTED);
	}

	@Test
	void unavailableKeysAreDifferentFromAnUnencryptedOutlookMessage() {
		final Email outlook = SimpleJavaMail.fromDefaults().converter().fromOutlookMsg(fixture("encrypted.msg")).toEmail();
		assertUnopenedFamily(outlook, DecryptionStatus.UNAVAILABLE);
		assertThat(outlook.getInspection().getSmime().getDecryptions()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getFailureReason()).isNotEmpty());
		assertThat(outlook.getInspection().getOriginalMessage()).isNotNull();
		assertThat(outlook.getInspection().getOriginalMessage().getName()).endsWith(".msg");
	}

	@Test
	void invalidStorePasswordsStillFailInsteadOfInventingUsableDecryptedContent() {
		final Pkcs12Config usable = loadPkcs12KeyStore();
		final Pkcs12Config wrongPassword = Pkcs12Config.builder().pkcs12Store(usable.getPkcs12StoreData())
				.storePassword("wrong password").keyAlias(usable.getKeyAlias()).keyPassword(usable.getKeyPassword()).build();

		assertThatThrownBy(() -> EmailConverter.outlookMsgToEmail(fixture("encrypted.msg").toFile(), wrongPassword))
				.isInstanceOf(SmimeException.class).hasCauseInstanceOf(Exception.class);
	}

	@Test
	void decryptingAnIndependentAttachmentDoesNotMakeItsPlainParentAnEncryptedMessage() throws Exception {
		final Session session = Session.getInstance(new Properties());
		final byte[] ciphertext;
		try (InputStream input = Files.newInputStream(fixture("encrypted.eml"))) {
			try (InputStream decoded = new MimeMessage(session, input).getInputStream()) {
				ciphertext = decoded.readAllBytes();
			}
		}
		final MimeBodyPart encryptedFile = new MimeBodyPart();
		encryptedFile.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(ciphertext, "application/pkcs7-mime")));
		encryptedFile.setFileName("independent.p7m");
		final MimeBodyPart plainFile = new MimeBodyPart();
		plainFile.setText("Other attachment");
		plainFile.setFileName("note.txt");
		final MimeBodyPart body = new MimeBodyPart();
		body.setText("The parent message is plain");
		final MimeMultipart content = new MimeMultipart();
		content.addBodyPart(body);
		content.addBodyPart(encryptedFile);
		content.addBodyPart(plainFile);
		final MimeMessage parent = new MimeMessage(session);
		parent.setContent(content);
		parent.saveChanges();

		final Email converted = EmailConverter.mimeMessageToEmail(parent, loadPkcs12KeyStore());

		assertPlainProtection(converted, SignatureStatus.UNSIGNED, EncryptionStatus.NOT_ENCRYPTED);
		assertThat(converted.getAttachments()).hasSize(2);
		assertThat(converted.getAttachments().get(0).getEmail()).isNull();
		assertThat(converted.getInspection().getAttachment(0).getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
		assertThat(converted.getInspection().getOriginalAttachment(0).readAllBytes()).containsExactly(ciphertext);
		assertThat(converted.getAttachments().get(1).readAllData()).isEqualTo("Other attachment");
	}

	@Test
	void successfulDecryptionDoesNotMakeAnInvalidSignatureValid() throws Exception {
		final Pkcs12Config keys = loadPkcs12KeyStore();
		final String signedSource = Files.readString(fixture("signed.eml"), UTF_8);
		final String invalidSource = signedSource.replaceFirst("dummy=20(\\r?\\n)attachment\\.", "dummy=20$1BROKEN attachment.");
		assertThat(invalidSource).isNotEqualTo(signedSource);
		final MimeBodyPart signedPart = new MimeBodyPart(new ByteArrayInputStream(invalidSource.getBytes(UTF_8)));
		final KeyStore store = KeyStore.getInstance("PKCS12");
		store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
		final X509Certificate certificate = (X509Certificate) store.getCertificate(keys.getKeyAlias());
		final MimeBodyPart encrypted = SmimeCryptoSupport.encrypt(signedPart, certificate,
				SmimeCryptoSupport.DEFAULT_KEY_ENCAPSULATION_ALGORITHM, SmimeCryptoSupport.DEFAULT_CIPHER);
		final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		message.setDataHandler(encrypted.getDataHandler());
		message.saveChanges();

		final Email converted = EmailConverter.mimeMessageToEmail(message, keys);

		assertPlainProtection(converted, SignatureStatus.INVALID, EncryptionStatus.DECRYPTED);
		assertSignatureObservation(converted, SignatureVerification.INVALID);
		assertThat(converted.getPlainText()).contains("BROKEN attachment");
	}

	private static Path fixture(final String suffix) {
		return Path.of(determineResourceFolder("simple-java-mail"), "test/resources/pkcs12/test messages/S_MIME test message " + suffix);
	}
}
