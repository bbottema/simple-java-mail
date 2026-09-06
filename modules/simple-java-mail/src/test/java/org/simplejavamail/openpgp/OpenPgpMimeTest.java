package org.simplejavamail.openpgp;

import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPSignatureSubpacketVector;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.api.OpenPGPKey;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.pgpainless.PGPainless;
import org.pgpainless.algorithm.KeyFlag;
import org.pgpainless.key.generation.KeySpecBuilder;
import org.pgpainless.key.generation.type.rsa.RSA;
import org.pgpainless.key.generation.type.rsa.RsaLength;
import org.pgpainless.util.Passphrase;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.DecryptionStatus;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.OpenPgpMode;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.SignatureStatus;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.openpgpsupport.OpenPgpSupport;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.mailer.internal.SessionBasedEmailToMimeMessageConverter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Provider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.HEADERS_ONLY;
import static org.simplejavamail.internal.util.MiscUtil.readInputStreamToBytes;

class OpenPgpMimeTest {

    private static final Provider BC = new BouncyCastleProvider();
    private static final char[] PASSPHRASE = "test-passphrase".toCharArray();
    private static final char[] OPENPGP_JS_PASSPHRASE = "openpgpjs-fixture-passphrase".toCharArray();
    private static final int SIGNING_AND_ENCRYPTION_KEY_FLAGS = KeyFlags.CERTIFY_OTHER | KeyFlags.SIGN_DATA
            | KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE;
    private static TestKey firstKey;
    private static TestKey secondKey;
    private static TestKey keyWithSigningSubkey;
    private static TestKey expiredKey;
    private static TestKey encryptionOnlyKey;

    @BeforeAll
    static void generateKeys() throws Exception {
        firstKey = generateKey("First Recipient <first@example.com>");
        secondKey = generateKey("Second Recipient <second@example.com>");
        keyWithSigningSubkey = generateKeyWithSigningSubkey("Signing Subkey <subkey@example.com>");
        expiredKey = generateKey("Expired Recipient <expired@example.com>", SIGNING_AND_ENCRYPTION_KEY_FLAGS,
                new Date(System.currentTimeMillis() - 60_000L), 1);
        encryptionOnlyKey = generateKey("Encryption Recipient <encryption@example.com>",
                KeyFlags.CERTIFY_OTHER | KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE, new Date(), 0);
    }

    @Test
    void signsAndVerifiesExactPlainMimeEntity() throws Exception {
        final Email source = basicEmail("Signed body")
                .signWithOpenPgp(signing(firstKey))
                .buildEmail();

        final MimeMessage protectedMessage = EmailConverter.emailToMimeMessage(source);
        final byte[] firstWrite = EmailConverter.mimeMessageToEMLByteArray(protectedMessage);
        final byte[] secondWrite = EmailConverter.mimeMessageToEMLByteArray(protectedMessage);
        final Email parsed = EmailConverter.mimeMessageToEmail(protectedMessage, null, receiving(firstKey));

        assertThat(protectedMessage).isInstanceOf(FinalizedMimeMessage.class);
        assertThat(protectedMessage.isMimeType("multipart/signed")).isTrue();
        assertThat(new ContentType(protectedMessage.getContentType()).getParameter("protocol"))
                .isEqualToIgnoringCase("application/pgp-signature");
        assertThat(firstWrite).containsExactly(secondWrite);
        assertThat(parsed.getPlainText()).isEqualTo("Signed body");
        assertThat(parsed.getOriginalOpenPgpDetails().getOpenPgpMode()).isEqualTo(OpenPgpMode.SIGNED);
        assertThat(parsed.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.VALID);
        assertThat(parsed.getOriginalOpenPgpDetails().getSignerKeyId()).isNotBlank();
        assertThat(parsed.getOriginalOpenPgpDetails().getSignerFingerprint()).hasSize(40);
        assertThat(parsed.getOriginalOpenPgpDetails().getHashAlgorithm()).isEqualTo("SHA256");
        assertThat(parsed.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(firstWrite);
    }

	@Test
	void signsAndVerifiesComplexNestedMimeEntity() throws Exception {
		final byte[] attachmentBytes = new byte[]{0, 1, 2, 3, (byte) 0xff};
		final byte[] embeddedBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
		final String calendar = "BEGIN:VCALENDAR\r\nMETHOD:REQUEST\r\nEND:VCALENDAR\r\n";
		final Email source = basicEmail("Héllo with trailing space ")
				.withPlainTextContentTransferEncoding(ContentTransferEncoding.QUOTED_PRINTABLE)
				.withHTMLText("<p>Héllo <img src=\"cid:logo\"></p>")
				.withHTMLTextContentTransferEncoding(ContentTransferEncoding.BASE_64)
				.withCalendarText(CalendarMethod.REQUEST, calendar)
				.withCalendarTextContentTransferEncoding(ContentTransferEncoding.QUOTED_PRINTABLE)
				.withEmbeddedImage("logo", embeddedBytes, "image/png")
				.withAttachment("proof.bin", attachmentBytes, "application/octet-stream", null,
						ContentTransferEncoding.BASE_64)
				.signWithOpenPgp(signing(firstKey))
				.buildEmail();

		final Email parsed = EmailConverter.mimeMessageToEmail(
				EmailConverter.emailToMimeMessage(source), null, receiving(firstKey));

		assertThat(parsed.getPlainText()).isEqualTo("Héllo with trailing space ");
		assertThat(parsed.getHTMLText()).isEqualTo("<p>Héllo <img src=\"cid:logo\"></p>");
		assertThat(parsed.getCalendarMethod()).isEqualTo(CalendarMethod.REQUEST);
		assertThat(parsed.getCalendarText()).isEqualTo(calendar);
		assertThat(parsed.getAttachments()).extracting(AttachmentResource::getName).containsExactly("proof.bin");
		assertThat(readInputStreamToBytes(parsed.getAttachments().get(0).getDataSourceInputStream()))
				.containsExactly(attachmentBytes);
		assertThat(parsed.getEmbeddedImages()).extracting(AttachmentResource::getName).containsExactly("logo");
		assertThat(readInputStreamToBytes(parsed.getEmbeddedImages().get(0).getDataSourceInputStream()))
				.containsExactly(embeddedBytes);
		assertThat(parsed.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.VALID);
	}

    @Test
    void tamperingIsInvalidButClearContentRemainsReadable() throws Exception {
        final MimeMessage protectedMessage = EmailConverter.emailToMimeMessage(
				basicEmail("original body")
						.withPlainTextContentTransferEncoding(org.simplejavamail.api.email.ContentTransferEncoding.BIT7)
						.signWithOpenPgp(signing(firstKey))
						.buildEmail());
        final byte[] original = EmailConverter.mimeMessageToEMLByteArray(protectedMessage);
        final byte[] tampered = replaceAscii(original, "original body", "tampered body");

        final Email parsed = EmailConverter.emlToEmailWithOpenPgp(
                new ByteArrayInputStream(tampered), receiving(firstKey));

        assertThat(parsed.getPlainText()).isEqualTo("tampered body");
        assertThat(parsed.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.INVALID);
    }

    @Test
    void missingVerificationKeyIsDistinctFromInvalidSignature() throws Exception {
        final MimeMessage protectedMessage = EmailConverter.emailToMimeMessage(
                basicEmail("readable").signWithOpenPgp(signing(firstKey)).buildEmail());

        final Email parsed = EmailConverter.mimeMessageToEmail(protectedMessage, null,
                OpenPgpReceiveConfig.builder().build());

        assertThat(parsed.getPlainText()).isEqualTo("readable");
        assertThat(parsed.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.KEY_MISSING);
    }

    @Test
    void encryptsForMultipleRecipientsAndSecondRecipientDecrypts() throws Exception {
        final OpenPgpEncryptionConfig encryption = OpenPgpEncryptionConfig.builder()
                .addRecipientPublicKeyRing(firstKey.publicRing)
                .addRecipientPublicKeyRing(secondKey.publicRing)
                .build();
        final Email source = basicEmail("Héllo encrypted")
                .withAttachment("notes.txt", new ByteArrayDataSource("attachment".getBytes(StandardCharsets.UTF_8), "text/plain"))
                .encryptWithOpenPgp(encryption)
                .buildEmail();

        final MimeMessage protectedMessage = EmailConverter.emailToMimeMessage(source);
        final Email parsed = EmailConverter.mimeMessageToEmail(protectedMessage, null, receiving(secondKey));

        assertThat(protectedMessage.isMimeType("multipart/encrypted")).isTrue();
        assertThat(parsed.getPlainText()).isEqualTo("Héllo encrypted");
        assertThat(parsed.getAttachments()).extracting(AttachmentResource::getName).contains("notes.txt");
        assertThat(readInputStreamToBytes(parsed.getAttachments().get(0).getDataSource().getInputStream()))
                .containsExactly("attachment".getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.getOriginalOpenPgpDetails().getOpenPgpMode()).isEqualTo(OpenPgpMode.ENCRYPTED);
        assertThat(parsed.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
        assertThat(parsed.getOriginalOpenPgpDetails().getRecipientKeyIds()).hasSize(2);
        assertThat(parsed.getOriginalOpenPgpDetails().getEncryptionAlgorithm()).isEqualTo("AES256");
    }

    @Test
    void signThenEncryptRoundTripsNestedMultipart() throws Exception {
        final Email source = basicEmail("signed and encrypted")
                .withHTMLText("<strong>signed and encrypted</strong>")
                .signWithOpenPgp(signing(firstKey))
                .encryptWithOpenPgp(encryption(firstKey))
                .buildEmail();

        final Email parsed = EmailConverter.mimeMessageToEmail(
                EmailConverter.emailToMimeMessage(source), null, receiving(firstKey));

        assertThat(parsed.getPlainText()).isEqualTo("signed and encrypted");
        assertThat(parsed.getHTMLText()).isEqualTo("<strong>signed and encrypted</strong>");
        assertThat(parsed.getOriginalOpenPgpDetails().getOpenPgpMode()).isEqualTo(OpenPgpMode.SIGNED_ENCRYPTED);
        assertThat(parsed.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.VALID);
        assertThat(parsed.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
    }

	@Test
	void configuredAlgorithmsAreApplied() throws Exception {
		final OpenPgpSigningConfig signingConfig = OpenPgpSigningConfig.builder()
				.secretKeyRing(firstKey.secretRing)
				.passphrase(PASSPHRASE)
				.hashAlgorithm(OpenPgpSigningConfig.HashAlgorithm.SHA512)
				.build();
		final OpenPgpEncryptionConfig encryptionConfig = OpenPgpEncryptionConfig.builder()
				.addRecipientPublicKeyRing(firstKey.publicRing)
				.symmetricAlgorithm(OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_128)
				.build();

		final MimeMessage protectedMessage = EmailConverter.emailToMimeMessage(basicEmail("configured algorithms")
				.signWithOpenPgp(signingConfig)
				.encryptWithOpenPgp(encryptionConfig)
				.buildEmail());
		final Email parsed = EmailConverter.mimeMessageToEmail(protectedMessage, null, receiving(firstKey));

		assertThat(parsed.getOriginalOpenPgpDetails().getHashAlgorithm()).isEqualTo("SHA512");
		assertThat(parsed.getOriginalOpenPgpDetails().getEncryptionAlgorithm()).isEqualTo("AES128");
	}

    @Test
    void stopsBeforeProcessingAThirdOpenPgpProtectionLayer() throws Exception {
        final Email source = basicEmail("bounded nesting")
                .signWithOpenPgp(signing(firstKey))
                .buildEmail();
        final MimeMessage signed = EmailConverter.emailToMimeMessage(source);
        final OpenPgpSupport support = new OpenPgpSupport();
        final MimeMessage encryptedOnce = support.encryptMessage(
                signed.getSession(), source, signed, encryption(firstKey));
        final MimeMessage encryptedTwice = support.encryptMessage(
                signed.getSession(), source, encryptedOnce, encryption(firstKey));
        final byte[] outerProtectedBytes = EmailConverter.mimeMessageToEMLByteArray(encryptedTwice);

        final Email parsed = EmailConverter.mimeMessageToEmail(encryptedTwice, null, receiving(firstKey));

        assertThat(parsed.getOriginalOpenPgpDetails().getOpenPgpMode()).isEqualTo(OpenPgpMode.SIGNED_ENCRYPTED);
        assertThat(parsed.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.ERROR);
        assertThat(parsed.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
        assertThat(parsed.getOriginalOpenPgpDetails().getFailureReason())
                .isEqualTo("OpenPGP/MIME nesting limit exceeded");
        assertThat(parsed.getOriginalOpenPgpDetails().getOriginalProtectedMessage())
                .containsExactly(outerProtectedBytes);
    }

    @Test
    void missingDecryptionKeyPreservesOriginalProtectedBytes() throws Exception {
        final MimeMessage protectedMessage = EmailConverter.emailToMimeMessage(
                basicEmail("secret").encryptWithOpenPgp(encryption(firstKey)).buildEmail());
        final byte[] protectedBytes = EmailConverter.mimeMessageToEMLByteArray(protectedMessage);

        final Email parsed = EmailConverter.mimeMessageToEmail(protectedMessage, null,
                OpenPgpReceiveConfig.builder().build());

        assertThat(parsed.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.KEY_MISSING);
        assertThat(parsed.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(protectedBytes);
    }

	@Test
	void tamperedCiphertextIsRejectedAndProtectedBytesRemainAvailable() throws Exception {
		final MimeMessage protectedMessage = EmailConverter.emailToMimeMessage(
				basicEmail("untampered secret").encryptWithOpenPgp(encryption(firstKey)).buildEmail());
		final byte[] tamperedBytes = tamperArmoredPayload(
				EmailConverter.mimeMessageToEMLByteArray(protectedMessage));

		final Email parsed = EmailConverter.emlToEmailWithOpenPgp(
				new ByteArrayInputStream(tamperedBytes), receiving(firstKey));

		assertThat(parsed.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.FAILED);
		assertThat(parsed.getPlainText()).isNotEqualTo("untampered secret");
		assertThat(parsed.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(tamperedBytes);
    }

	@Test
	void matchingSecretKeyWithWrongPassphraseIsADecryptionFailure() throws Exception {
		final MimeMessage protectedMessage = EmailConverter.emailToMimeMessage(
				basicEmail("secret").encryptWithOpenPgp(encryption(firstKey)).buildEmail());
		final byte[] protectedBytes = EmailConverter.mimeMessageToEMLByteArray(protectedMessage);
		final OpenPgpReceiveConfig wrongPassphrase = OpenPgpReceiveConfig.builder()
				.addDecryptionKeyRing(firstKey.secretRing, "wrong-passphrase")
				.build();

		final Email parsed = EmailConverter.mimeMessageToEmail(protectedMessage, null, wrongPassphrase);

		assertThat(parsed.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.FAILED);
		assertThat(parsed.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(protectedBytes);
	}

	@Test
	void passphraseProviderOverridesTheKeyRingFallbackWithoutConsumingItsArray() throws Exception {
		final MimeMessage protectedMessage = EmailConverter.emailToMimeMessage(
				basicEmail("provider secret").encryptWithOpenPgp(encryption(firstKey)).buildEmail());
		final AtomicLong requestedKeyId = new AtomicLong();
		final char[] providedPassphrase = PASSPHRASE.clone();
		final OpenPgpReceiveConfig receiveConfig = OpenPgpReceiveConfig.builder()
				.addDecryptionKeyRing(firstKey.secretRing, "wrong-passphrase")
				.passphraseProvider(keyId -> {
					requestedKeyId.set(keyId);
					return providedPassphrase;
				})
				.build();

		final Email parsed = EmailConverter.mimeMessageToEmail(protectedMessage, null, receiveConfig);

		assertThat(parsed.getPlainText()).isEqualTo("provider secret");
		assertThat(parsed.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
		assertThat(requestedKeyId).hasValue(firstKey.keyId);
		assertThat(providedPassphrase).containsExactly(PASSPHRASE);
	}

	@Test
	void configuredSigningSubkeyCanBeSelectedFromOneSecretKeyRing() throws Exception {
		final OpenPgpSigningConfig signingConfig = OpenPgpSigningConfig.builder()
				.secretKeyRing(keyWithSigningSubkey.secretRing)
				.passphrase(PASSPHRASE)
				.signingKeyId(keyWithSigningSubkey.keyId)
				.build();

		final MimeMessage protectedMessage = EmailConverter.emailToMimeMessage(
				basicEmail("selected signer").signWithOpenPgp(signingConfig).buildEmail());
		final Email parsed = EmailConverter.mimeMessageToEmail(
				protectedMessage, null, receiving(keyWithSigningSubkey));

		assertThat(parsed.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.VALID);
		assertThat(parsed.getOriginalOpenPgpDetails().getSignerKeyId())
				.isEqualTo(String.format(Locale.ROOT, "%016X", keyWithSigningSubkey.keyId));
	}

	@Test
	void expiredKeysAreRejectedWhenApplyingNewProtection() {
		assertThatThrownBy(() -> EmailConverter.emailToMimeMessage(
				basicEmail("expired signer").signWithOpenPgp(signing(expiredKey)).buildEmail()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Unable to sign OpenPGP/MIME message");

		final OpenPgpSigningConfig explicitlySelectedExpiredKey = OpenPgpSigningConfig.builder()
				.secretKeyRing(expiredKey.secretRing)
				.passphrase(PASSPHRASE)
				.signingKeyId(expiredKey.keyId)
				.build();
		assertThatThrownBy(() -> EmailConverter.emailToMimeMessage(
				basicEmail("explicit expired signer")
						.signWithOpenPgp(explicitlySelectedExpiredKey)
						.buildEmail()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Unable to sign OpenPGP/MIME message");

		assertThatThrownBy(() -> EmailConverter.emailToMimeMessage(
				basicEmail("expired recipient").encryptWithOpenPgp(encryption(expiredKey)).buildEmail()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Unable to encrypt OpenPGP/MIME message");
	}

	@Test
	void configuredEncryptionOnlyKeyCannotCreateSignatures() {
		final OpenPgpSigningConfig signingConfig = OpenPgpSigningConfig.builder()
				.secretKeyRing(encryptionOnlyKey.secretRing)
				.passphrase(PASSPHRASE)
				.signingKeyId(encryptionOnlyKey.keyId)
				.build();

		assertThatThrownBy(() -> EmailConverter.emailToMimeMessage(
				basicEmail("not signable").signWithOpenPgp(signingConfig).buildEmail()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Unable to sign OpenPGP/MIME message");
	}

	@Test
	void signsEncryptsAndReadsMessagesConcurrently() throws Exception {
		final OpenPgpSigningConfig signingConfig = signing(firstKey);
		final OpenPgpEncryptionConfig encryptionConfig = encryption(secondKey);
		final OpenPgpReceiveConfig receiveConfig = OpenPgpReceiveConfig.builder()
				.addVerificationKeyRing(firstKey.publicRing)
				.addDecryptionKeyRing(secondKey.secretRing, PASSPHRASE)
				.build();
		final ExecutorService executor = Executors.newFixedThreadPool(4);
		final List<Future<Email>> receivedMessages = new ArrayList<>();
		try {
			for (int messageIndex = 0; messageIndex < 8; messageIndex++) {
				final String body = "concurrent message " + messageIndex;
				receivedMessages.add(executor.submit(() -> {
					final MimeMessage protectedMessage = EmailConverter.emailToMimeMessage(basicEmail(body)
							.signWithOpenPgp(signingConfig)
							.encryptWithOpenPgp(encryptionConfig)
							.buildEmail());
					return EmailConverter.mimeMessageToEmail(protectedMessage, null, receiveConfig);
				}));
			}

			for (int messageIndex = 0; messageIndex < receivedMessages.size(); messageIndex++) {
				final Email received = receivedMessages.get(messageIndex).get(30, TimeUnit.SECONDS);
				assertThat(received.getPlainText()).isEqualTo("concurrent message " + messageIndex);
				assertThat(received.getOriginalOpenPgpDetails().getSignatureStatus())
						.isEqualTo(SignatureStatus.VALID);
				assertThat(received.getOriginalOpenPgpDetails().getDecryptionStatus())
						.isEqualTo(DecryptionStatus.DECRYPTED);
			}
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void signedMessageRemainsReadableWhenOpenPgpModuleIsAbsent() throws Exception {
		final MimeMessage protectedMessage = EmailConverter.emailToMimeMessage(
				basicEmail("readable without module").signWithOpenPgp(signing(firstKey)).buildEmail());
		final byte[] protectedBytes = EmailConverter.mimeMessageToEMLByteArray(protectedMessage);

		try (MockedStatic<ModuleLoader> moduleLoader = org.mockito.Mockito.mockStatic(
				ModuleLoader.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
			moduleLoader.when(ModuleLoader::openPgpModuleAvailable).thenReturn(false);

			final Email parsed = EmailConverter.mimeMessageToEmail(
					protectedMessage, null, (OpenPgpReceiveConfig) null);

			assertThat(parsed.getPlainText()).isEqualTo("readable without module");
			assertThat(parsed.getOriginalOpenPgpDetails().getOpenPgpMode()).isEqualTo(OpenPgpMode.SIGNED);
			assertThat(parsed.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.ERROR);
			assertThat(parsed.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(protectedBytes);
		}
	}

	@Test
	void encryptedMessageRemainsIntactWhenOpenPgpModuleIsAbsent() throws Exception {
		final MimeMessage protectedMessage = EmailConverter.emailToMimeMessage(
				basicEmail("encrypted without module").encryptWithOpenPgp(encryption(firstKey)).buildEmail());
		final byte[] protectedBytes = EmailConverter.mimeMessageToEMLByteArray(protectedMessage);

		try (MockedStatic<ModuleLoader> moduleLoader = org.mockito.Mockito.mockStatic(
				ModuleLoader.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
			moduleLoader.when(ModuleLoader::openPgpModuleAvailable).thenReturn(false);

			final Email parsed = EmailConverter.mimeMessageToEmail(
					protectedMessage, null, (OpenPgpReceiveConfig) null);

			assertThat(parsed.getOriginalOpenPgpDetails().getOpenPgpMode()).isEqualTo(OpenPgpMode.ENCRYPTED);
			assertThat(parsed.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.FAILED);
			assertThat(parsed.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(protectedBytes);
			assertThat(parsed.getAttachments()).extracting(AttachmentResource::getName).contains("encrypted.asc");
		}
	}

    @Test
    void dkimOpenPgpAndDeliveryMetadataComposeWithoutSubtypeMatrix() throws Exception {
        final KeyPairGenerator dkimKeyGenerator = KeyPairGenerator.getInstance("RSA");
        dkimKeyGenerator.initialize(1024);
        final byte[] dkimKey = dkimKeyGenerator.generateKeyPair().getPrivate().getEncoded();
        final Email email = basicEmail("composed")
                .from("sender@supersecret-testing-domain.com")
                .withBounceTo("bounce@example.com")
                .withDeliveryStatusNotification(HEADERS_ONLY, FAILURE)
                .signWithOpenPgp(signing(firstKey))
                .signWithDomainKey(DkimConfig.builder()
                        .dkimPrivateKeyData(dkimKey)
                        .dkimSigningDomain("supersecret-testing-domain.com")
                        .dkimSelector("selector")
                        .build())
                .buildEmailCompletedWithDefaultsAndOverrides();
        final Mailer mailer = SimpleJavaMail.fromDefaults().mailerBuilder().withSMTPServer("localhost", 25).buildMailer();

        final PreparedMail prepared = SessionBasedEmailToMimeMessageConverter.convertAndLogPreparedMail(
                mailer.getSession(), email);
        final Email parsed = EmailConverter.mimeMessageToEmail(prepared.getMimeMessage(), null, receiving(firstKey));

        assertThat(prepared.getMimeMessage().getHeader("DKIM-Signature", null)).isNotBlank();
        assertThat(prepared.getDeliveryEnvelope().getEnvelopeFrom()).isEqualTo("bounce@example.com");
        assertThat(prepared.getDeliveryEnvelope().getDeliveryStatusNotification().getReturnOption())
                .isEqualTo(HEADERS_ONLY);
        assertThat(parsed.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.VALID);
    }

    @Test
    void rejectsMixingSmimeAndOpenPgp() {
        assertThatThrownBy(() -> basicEmail("body")
                .signWithSmime(org.mockito.Mockito.mock(SmimeSigningConfig.class))
                .signWithOpenPgp(OpenPgpSigningConfig.builder()
                        .secretKeyRing(new byte[]{1}).passphrase(new char[0]).build())
                .buildEmail())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("S/MIME and OpenPGP/MIME");
    }

    @Test
    void secretConfigurationIsRedactedAndNotSerializedWithEmail() throws Exception {
        final Email email = basicEmail("body").signWithOpenPgp(signing(firstKey)).buildEmail();

        assertThat(email.toString()).doesNotContain("test-passphrase");
        assertThat(email.getOpenPgpSigningConfig().toString()).doesNotContain("test-passphrase");

        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ObjectOutputStream objectOutput = new ObjectOutputStream(output)) {
            objectOutput.writeObject(email);
        }
        final Email restored;
        try (ObjectInputStream objectInput = new ObjectInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            restored = (Email) objectInput.readObject();
        }
        assertThat(restored.getOpenPgpSigningConfig()).isNull();
    }

	@Test
	void openPgpJsFixturesWorkInBothDirections() throws Exception {
		final byte[] publicKey = resource("openpgpjs/public-key.asc");
		final byte[] privateKey = resource("openpgpjs/private-key.asc");
		final OpenPgpReceiveConfig receiveConfig = OpenPgpReceiveConfig.builder()
				.addVerificationKeyRing(publicKey)
				.addDecryptionKeyRing(privateKey, OPENPGP_JS_PASSPHRASE)
				.build();

		final Email signedFixture = EmailConverter.emlToEmailWithOpenPgp(
				new ByteArrayInputStream(resource("openpgpjs/signed-mixed.eml")), receiveConfig);
		final Email encryptedFixture = EmailConverter.emlToEmailWithOpenPgp(
				new ByteArrayInputStream(resource("openpgpjs/encrypted-mixed.eml")), receiveConfig);

		assertThat(signedFixture.getPlainText()).isEqualTo("Héllo from OpenPGP.js");
		assertThat(signedFixture.getAttachments()).extracting(AttachmentResource::getName).containsExactly("proof.txt");
		assertThat(signedFixture.getAttachments().get(0).readAllData()).isEqualTo("independent fixture");
		assertThat(signedFixture.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.VALID);
		assertThat(signedFixture.getOriginalOpenPgpDetails().getHashAlgorithm()).isEqualTo("SHA256");
		assertThat(encryptedFixture.getPlainText()).isEqualTo("Héllo from OpenPGP.js");
		assertThat(encryptedFixture.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.DECRYPTED);

		final OpenPgpSigningConfig signingConfig = OpenPgpSigningConfig.builder()
				.secretKeyRing(privateKey)
				.passphrase(OPENPGP_JS_PASSPHRASE)
				.build();
		final OpenPgpEncryptionConfig encryptionConfig = OpenPgpEncryptionConfig.builder()
				.addRecipientPublicKeyRing(publicKey)
				.build();
		final Path outputDirectory = Paths.get("target", "openpgpjs-interop");
		Files.createDirectories(outputDirectory);
		Files.write(outputDirectory.resolve("sjm-signed.eml"), EmailConverter.mimeMessageToEMLByteArray(
				EmailConverter.emailToMimeMessage(basicEmail("Simple Java Mail outbound interoperability")
						.withHTMLText("<p>independent verification</p>")
						.withAttachment("outbound.txt", "fixture".getBytes(StandardCharsets.UTF_8), "text/plain")
						.signWithOpenPgp(signingConfig)
						.buildEmail())));
		Files.write(outputDirectory.resolve("sjm-encrypted.eml"), EmailConverter.mimeMessageToEMLByteArray(
				EmailConverter.emailToMimeMessage(basicEmail("Simple Java Mail outbound interoperability")
						.withHTMLText("<p>independent verification</p>")
						.withAttachment("outbound.txt", "fixture".getBytes(StandardCharsets.UTF_8), "text/plain")
						.encryptWithOpenPgp(encryptionConfig)
						.buildEmail())));
	}

	private static byte[] resource(final String name) throws Exception {
		try (InputStream input = OpenPgpMimeTest.class.getClassLoader().getResourceAsStream(name)) {
			if (input == null) throw new AssertionError("Missing test resource: " + name);
			return readInputStreamToBytes(input);
		}
	}

    private static org.simplejavamail.api.email.EmailPopulatingBuilder basicEmail(final String body) {
        return SimpleJavaMail.fromDefaults().emailBuilder().startingBlank()
				.ignoringDefaults(true)
                .from("sender@example.com")
                .withRecipients(new Recipient(null, "receiver@example.com", RecipientType.TO, null))
                .withSubject("OpenPGP test")
                .withPlainText(body);
    }

    private static OpenPgpSigningConfig signing(final TestKey key) {
        return OpenPgpSigningConfig.builder()
                .secretKeyRing(key.secretRing)
                .passphrase(PASSPHRASE)
                .build();
    }

    private static OpenPgpEncryptionConfig encryption(final TestKey key) {
        return OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(key.publicRing).build();
    }

    private static OpenPgpReceiveConfig receiving(final TestKey key) {
        return OpenPgpReceiveConfig.builder()
                .addVerificationKeyRing(key.publicRing)
                .addDecryptionKeyRing(key.secretRing, PASSPHRASE)
                .build();
    }

    private static TestKey generateKey(final String userId) throws Exception {
		return generateKey(userId, SIGNING_AND_ENCRYPTION_KEY_FLAGS, new Date(), 0);
	}

	private static TestKey generateKeyWithSigningSubkey(final String userId) throws Exception {
		final PGPainless pgpainless = new PGPainless();
		final Passphrase passphrase = new Passphrase(PASSPHRASE.clone());
		try {
			final OpenPGPKey keyRing = pgpainless.buildKey()
					.setPrimaryKey(new KeySpecBuilder(RSA.withLength(RsaLength._2048),
							KeyFlag.CERTIFY_OTHER, KeyFlag.SIGN_DATA).build())
					.addSubkey(new KeySpecBuilder(RSA.withLength(RsaLength._2048), KeyFlag.SIGN_DATA).build())
					.addUserId(userId)
					.setPassphrase(passphrase)
					.build();
			final long signingSubkeyId = pgpainless.inspect(keyRing).getSigningSubkeys().stream()
					.filter(signingKey -> !signingKey.isPrimaryKey())
					.findFirst()
					.orElseThrow(() -> new AssertionError("Expected a signing subkey"))
					.getKeyIdentifier().getKeyId();
			return new TestKey(keyRing.getEncoded(), keyRing.toCertificate().getEncoded(), signingSubkeyId);
		} finally {
			passphrase.clear();
		}
	}

	private static TestKey generateKey(final String userId, final int keyFlags,
			final Date creationDate, final long validitySeconds) throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final KeyPair keyPair = generator.generateKeyPair();
		final JcaPGPKeyPair pgpKeyPair = new JcaPGPKeyPair(
				PublicKeyAlgorithmTags.RSA_GENERAL, keyPair, creationDate);
        final PGPDigestCalculator sha1 = new JcaPGPDigestCalculatorProviderBuilder().setProvider(BC)
                .build().get(HashAlgorithmTags.SHA1);
		final PGPSignatureSubpacketVector keyCapabilities = keyCapabilities(keyFlags, validitySeconds);
        final PGPKeyRingGenerator keyRingGenerator = new PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                pgpKeyPair,
                userId,
                sha1,
				keyCapabilities,
                null,
                new JcaPGPContentSignerBuilder(PublicKeyAlgorithmTags.RSA_GENERAL, HashAlgorithmTags.SHA256)
						.setProvider(BC),
                new JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1)
                        .setProvider(BC).build(PASSPHRASE));
        final PGPSecretKeyRing secretRing = keyRingGenerator.generateSecretKeyRing();
        final PGPPublicKeyRing publicRing = keyRingGenerator.generatePublicKeyRing();
		return new TestKey(encode(secretRing), encode(publicRing), publicRing.getPublicKey().getKeyID());
    }

	private static PGPSignatureSubpacketVector keyCapabilities(final int keyFlags,
			final long validitySeconds) {
		if (keyFlags == 0 && validitySeconds == 0) {
			return null;
		}
		final PGPSignatureSubpacketGenerator keyCapabilities = new PGPSignatureSubpacketGenerator();
		if (keyFlags != 0) {
			keyCapabilities.setKeyFlags(false, keyFlags);
		}
		if (validitySeconds > 0) {
			keyCapabilities.setKeyExpirationTime(false, validitySeconds);
		}
		return keyCapabilities.generate();
	}

    private static byte[] encode(final PGPSecretKeyRing keyRing) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        keyRing.encode(output);
        return output.toByteArray();
    }

    private static byte[] encode(final PGPPublicKeyRing keyRing) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        keyRing.encode(output);
        return output.toByteArray();
    }

    private static byte[] replaceAscii(final byte[] bytes, final String expected, final String replacement) {
        final byte[] from = expected.getBytes(StandardCharsets.US_ASCII);
        final byte[] to = replacement.getBytes(StandardCharsets.US_ASCII);
        assertThat(to).hasSameSizeAs(from);
        final byte[] result = bytes.clone();
        for (int i = 0; i <= result.length - from.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(result, i, i + from.length), from)) {
                System.arraycopy(to, 0, result, i, to.length);
                return result;
            }
        }
        throw new AssertionError("Expected MIME text was not found");
    }

    private static byte[] tamperArmoredPayload(final byte[] messageBytes) throws IOException {
        final String serializedMessage = new String(messageBytes, StandardCharsets.US_ASCII);
        final String armorBeginMarker = "-----BEGIN PGP MESSAGE-----";
        final String armorEndMarker = "-----END PGP MESSAGE-----";
        final int armorStart = serializedMessage.indexOf(armorBeginMarker);
        final int armorEndMarkerStart = serializedMessage.indexOf(armorEndMarker, armorStart);
        if (armorStart < 0 || armorEndMarkerStart < 0) {
            throw new AssertionError("Expected armored OpenPGP payload was not found");
        }
        final int armorEnd = armorEndMarkerStart + armorEndMarker.length();
        final byte[] armoredPayload = serializedMessage.substring(armorStart, armorEnd)
                .getBytes(StandardCharsets.US_ASCII);
        final byte[] binaryPayload;
        try (InputStream decodedPayload = PGPUtil.getDecoderStream(new ByteArrayInputStream(armoredPayload))) {
            binaryPayload = readInputStreamToBytes(decodedPayload);
        }
        if (binaryPayload.length < 32) {
            throw new AssertionError("Expected a complete integrity-protected OpenPGP payload");
        }
        binaryPayload[binaryPayload.length - 10] ^= 0x01;

        final ByteArrayOutputStream rearmoredPayload = new ByteArrayOutputStream();
        try (ArmoredOutputStream armor = ArmoredOutputStream.builder()
                .setVersion("Simple Java Mail")
                .build(rearmoredPayload)) {
            armor.write(binaryPayload);
        }
        final String tamperedPayload = new String(rearmoredPayload.toByteArray(), StandardCharsets.US_ASCII);
        return (serializedMessage.substring(0, armorStart)
                + tamperedPayload
                + serializedMessage.substring(armorEnd)).getBytes(StandardCharsets.US_ASCII);
    }

    private static final class TestKey {
        final byte[] secretRing;
        final byte[] publicRing;
		final long keyId;

		private TestKey(final byte[] secretRing, final byte[] publicRing, final long keyId) {
            this.secretRing = secretRing;
            this.publicRing = publicRing;
			this.keyId = keyId;
        }
    }
}
