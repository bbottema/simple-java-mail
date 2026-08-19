package org.simplejavamail.openpgp;

import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
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
import org.simplejavamail.internal.openpgpsupport.OpenPgpSupport;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.mailer.internal.SessionBasedEmailToMimeMessageConverter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.Arrays;
import java.util.Date;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.HEADERS_ONLY;
import static org.simplejavamail.internal.util.MiscUtil.readInputStreamToBytes;

class OpenPgpMimeTest {

    private static final Provider BC = new BouncyCastleProvider();
    private static final char[] PASSPHRASE = "test-passphrase".toCharArray();
    private static TestKey firstKey;
    private static TestKey secondKey;
	private static final char[] OPENPGP_JS_PASSPHRASE = "openpgpjs-fixture-passphrase".toCharArray();

    @BeforeAll
    static void generateKeys() throws Exception {
        firstKey = generateKey("First Recipient <first@example.com>");
        secondKey = generateKey("Second Recipient <second@example.com>");
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
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final KeyPair keyPair = generator.generateKeyPair();
        final JcaPGPKeyPair pgpKeyPair = new JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, keyPair, new Date());
        final PGPDigestCalculator sha1 = new JcaPGPDigestCalculatorProviderBuilder().setProvider(BC)
                .build().get(HashAlgorithmTags.SHA1);
        final PGPKeyRingGenerator keyRingGenerator = new PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                pgpKeyPair,
                userId,
                sha1,
                null,
                null,
                new JcaPGPContentSignerBuilder(PublicKeyAlgorithmTags.RSA_GENERAL, HashAlgorithmTags.SHA256)
                        .setProvider(BC),
                new JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1)
                        .setProvider(BC).build(PASSPHRASE));
        final PGPSecretKeyRing secretRing = keyRingGenerator.generateSecretKeyRing();
        final PGPPublicKeyRing publicRing = keyRingGenerator.generatePublicKeyRing();
        return new TestKey(encode(secretRing), encode(publicRing));
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

    private static final class TestKey {
        final byte[] secretRing;
        final byte[] publicRing;

        private TestKey(final byte[] secretRing, final byte[] publicRing) {
            this.secretRing = secretRing;
            this.publicRing = publicRing;
        }
    }
}
