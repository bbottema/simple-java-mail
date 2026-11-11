package org.simplejavamail.internal.smimesupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.converter.EmailConverter;
import testutil.SecureTestDataHelper;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.ParseException;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static jakarta.mail.Message.RecipientType.TO;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.simplejavamail.internal.util.MiscUtil.normalizeNewlines;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

public class ReadSmimeSelfSignedTest {

	private static final String RESOURCES = determineResourceFolder("simple-java-mail") + "/test/resources";
	private static final String RESOURCES_PKCS = RESOURCES + "/pkcs12";
	private static final String RESOURCES_MESSAGES = RESOURCES_PKCS + "/test messages";

	@Test
	public void testSignedMessageMsg() {
		Email emailParsedFromMsg = EmailConverter.outlookMsgToEmail(new File(RESOURCES_MESSAGES + "/S_MIME test message signed.msg"));

		EmailAssert.assertThat(emailParsedFromMsg).hasFromRecipient(new Recipient("Benny Bottema", "benny@bennybottema.com", null, null));
		EmailAssert.assertThat(emailParsedFromMsg).hasSubject("S/MIME test message signed");
		EmailAssert.assertThat(emailParsedFromMsg).hasOnlyRecipients(new Recipient("Benny Bottema", "benny@bennybottema.com", TO, null));

		assertThat(normalizeNewlines(emailParsedFromMsg.getPlainText())).isEqualTo("This is an encrypted message, with one embedded image and one dummy \n"
				+ "attachment.\n"
				+ "\n"
				+ "For testing purposes in the Simple Java Mail project.\n"
				+ "\n");

		assertThat(emailParsedFromMsg.getEmbeddedImages()).hasSize(1);
		AttachmentResource embeddedImg = emailParsedFromMsg.getEmbeddedImages().get(0);
		assertEmbeddedModuleArchitectureImage(embeddedImg, "part1.6C435E18.EC121991@bennybottema.com");
		assertThat(emailParsedFromMsg.getHTMLText()).contains(format("<img src=\"cid:%s\"", embeddedImg.getContentId()));

		assertThat(emailParsedFromMsg.getAttachments()).hasSize(1);
		assertThat(emailParsedFromMsg.getAttachments()).extracting("name").containsExactlyInAnyOrder("03-07-2005 errata SharnErrata.pdf");

		assertProtectedBody(emailParsedFromMsg, SignatureStatus.VERIFIED, EncryptionStatus.NOT_ENCRYPTED);
        assertThat(emailParsedFromMsg.getInspection().getSmime().getSignatures()).singleElement()
                .satisfies(signature -> assertThat(signature.getSignerName()).contains("Benny Bottema"));
	}

	@Test
	public void testSignedMessageEml() {
		final File source = new File(RESOURCES_MESSAGES + "/S_MIME test message signed.eml");
		Email emailParsedFromEml = EmailConverter.emlToEmail(source);

		EmailAssert.assertThat(emailParsedFromEml).hasFromRecipient(new Recipient("Benny Bottema", "benny@bennybottema.com", null, null));
		EmailAssert.assertThat(emailParsedFromEml).hasSubject("S/MIME test message signed");
		EmailAssert.assertThat(emailParsedFromEml).hasOnlyRecipients(new Recipient("Benny Bottema", "benny@bennybottema.com", TO, null));

		assertThat(normalizeNewlines(emailParsedFromEml.getPlainText())).isEqualTo("This is an encrypted message, with one embedded image and one dummy \n"
				+ "attachment.\n"
				+ "\n"
				+ "For testing purposes in the Simple Java Mail project.\n"
				+ "\n");

		assertThat(emailParsedFromEml.getEmbeddedImages()).hasSize(1);
		AttachmentResource embeddedImg = emailParsedFromEml.getEmbeddedImages().get(0);
		assertEmbeddedModuleArchitectureImage(embeddedImg, "part1.6C435E18.EC121991@bennybottema.com");
		assertThat(emailParsedFromEml.getHTMLText()).contains(format("<img src=\"cid:%s\"", embeddedImg.getContentId()));

		assertThat(emailParsedFromEml.getAttachments()).hasSize(1);
		assertThat(emailParsedFromEml.getAttachments()).extracting("name").containsExactlyInAnyOrder("03-07-2005 errata SharnErrata.pdf");

		assertProtectedBody(emailParsedFromEml, SignatureStatus.VERIFIED, EncryptionStatus.NOT_ENCRYPTED);
        assertThat(emailParsedFromEml.getInspection().getSmime().getSignatures()).singleElement()
                .satisfies(signature -> assertThat(signature.getSignerName()).contains("Benny Bottema"));
		assertThat(emailParsedFromEml.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
		assertThat(emailParsedFromEml.getInspection().getOriginalMessage().readAllBytes())
				.containsExactly(readFile(source));
	}

	@Test
	public void testSignedMessageEmlWithInvalidSignatureStillParses()
			throws Exception {
		Email emailParsedFromEml = EmailConverter.emlToEmail(createInvalidSignedEml());

		EmailAssert.assertThat(emailParsedFromEml).hasFromRecipient(new Recipient("Benny Bottema", "benny@bennybottema.com", null, null));
		EmailAssert.assertThat(emailParsedFromEml).hasSubject("S/MIME test message signed");
		EmailAssert.assertThat(emailParsedFromEml).hasOnlyRecipients(new Recipient("Benny Bottema", "benny@bennybottema.com", TO, null));

		assertThat(normalizeNewlines(emailParsedFromEml.getPlainText())).isEqualTo("This is an encrypted message, with one embedded image and one dummy \n"
				+ "BROKEN attachment.\n"
				+ "\n"
				+ "For testing purposes in the Simple Java Mail project.\n"
				+ "\n");

		assertProtectedBody(emailParsedFromEml, SignatureStatus.INVALID, EncryptionStatus.NOT_ENCRYPTED);
        assertThat(emailParsedFromEml.getInspection().getSmime().getSignatures()).singleElement()
                .satisfies(signature -> assertThat(signature.getSignerName()).contains("Benny Bottema"));
		assertThat(emailParsedFromEml.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.INVALID);
		assertThat(emailParsedFromEml.getInspection().getSmime().getSignatures()).singleElement()
                .satisfies(signature -> assertThat(signature.getFailureReason()).contains("does not match"));
	}

	@Test
	public void testEncryptedMessageWithoutKeyPreservesProtectedEntityAndReportsKeyMissing() {
		final File source = new File(RESOURCES_MESSAGES + "/S_MIME test message encrypted.eml");

		final Email email = EmailConverter.emlToEmail(source);

        assertThat(email.getInspection().getSmime().getDecryptions()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.UNAVAILABLE);
            assertThat(attempt.getFailureReason()).contains("decryption key");
        });
        assertThat(email.getInspection().getSmime().getSignatures()).isEmpty();
        assertThat(email.getInspection().getOriginalMessage().readAllBytes()).containsExactly(readFile(source));
        assertThat(email.getPlainText()).isNull();

	}

	@Test
	public void testSignedMessageWithoutProtocolDoesNotSkipInvalidSignatureCheck()
			throws Exception {
		final String invalidSignedEmlWithoutProtocol = createInvalidSignedEml()
				.replace("; protocol=\"application/pkcs7-signature\"", "");

		final Email email = EmailConverter.emlToEmail(invalidSignedEmlWithoutProtocol);

		assertThat(email.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.INVALID);
	}

	@Test
	public void testUnsignedMessageCannotReportAValidSignature()
			throws Exception {
		final MimeMessage unsignedMessage = new MimeMessage(Session.getInstance(new Properties()));
		unsignedMessage.setText("Not signed");
		unsignedMessage.saveChanges();

        final var inspected = new SMIMESupport().openIncomingLayer(unsignedMessage.getSession(), unsignedMessage, null);
        assertThat(inspected.isRecognized()).isFalse();
        assertThat(inspected.getSignatures()).isEmpty();

	}

	@Test
	public void testSignedAttachmentWithInvalidSignatureStillUnwrapsContent()
			throws Exception {
        final String invalidSignedEml = createInvalidSignedEml();
        final Email parent = SimpleJavaMail.fromDefaults().emailBuilder().startingBlank()
                .from("sender@example.test").to("reader@example.test").withPlainText("Independent parent body")
                .withAttachment("signed-message.eml", new ByteArrayDataSource(invalidSignedEml.getBytes(UTF_8), "message/rfc822"))
                .buildEmail();
        final Email parsed = SimpleJavaMail.fromDefaults().converter().fromEml(EmailConverter.emailToEMLByteArray(parent)).toEmail();
        assertThat(parsed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNSIGNED);
        assertThat(parsed.getAttachments()).singleElement();
        final Email unwrappedEmail = parsed.getAttachments().get(0).getEmail();
        assertThat(unwrappedEmail).isNotNull();
        assertThat(unwrappedEmail.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.INVALID);
        assertThat(normalizeNewlines(unwrappedEmail.getPlainText())).contains("BROKEN attachment.");
    }

	@Test
	public void testEncryptedMessageMsg() {
		Email emailParsedFromMsg = EmailConverter.outlookMsgToEmail(new File(RESOURCES_MESSAGES + "/S_MIME test message encrypted.msg"), loadPkcs12KeyStore());

		EmailAssert.assertThat(emailParsedFromMsg).hasFromRecipient(new Recipient("Benny Bottema", "benny@bennybottema.com", null, null));
		EmailAssert.assertThat(emailParsedFromMsg).hasSubject("S/MIME test message encrypted");
		EmailAssert.assertThat(emailParsedFromMsg).hasOnlyRecipients(new Recipient("Benny Bottema", "benny@bennybottema.com", TO, null));

		assertThat(normalizeNewlines(emailParsedFromMsg.getPlainText())).isEqualTo("This is an encrypted message, with one embedded image and one dummy \n"
				+ "attachment.\n"
				+ "\n"
				+ "For testing purposes in the Simple Java Mail project.\n"
				+ "\n");

		assertThat(emailParsedFromMsg.getEmbeddedImages()).hasSize(1);
		AttachmentResource embeddedImg = emailParsedFromMsg.getEmbeddedImages().get(0);
		assertEmbeddedModuleArchitectureImage(embeddedImg, "part1.EDA02623.55A510EE@bennybottema.com");
		assertThat(emailParsedFromMsg.getHTMLText()).contains(format("<img src=\"cid:%s\"", embeddedImg.getContentId()));

		assertThat(emailParsedFromMsg.getAttachments()).hasSize(1);
		assertThat(emailParsedFromMsg.getAttachments()).extracting("name").containsExactlyInAnyOrder("03-07-2005 errata SharnErrata.pdf");

		assertProtectedBody(emailParsedFromMsg, SignatureStatus.UNSIGNED, EncryptionStatus.DECRYPTED);
	}

	@Test
	public void testEncryptedMessageEml() {
		Email emailParsedFromEml = EmailConverter.emlToEmail(new File(RESOURCES_MESSAGES + "/S_MIME test message encrypted.eml"), loadPkcs12KeyStore());

		EmailAssert.assertThat(emailParsedFromEml).hasFromRecipient(new Recipient("Benny Bottema", "benny@bennybottema.com", null, null));
		EmailAssert.assertThat(emailParsedFromEml).hasSubject("S/MIME test message encrypted");
		EmailAssert.assertThat(emailParsedFromEml).hasOnlyRecipients(new Recipient("Benny Bottema", "benny@bennybottema.com", TO, null));

		assertThat(normalizeNewlines(emailParsedFromEml.getPlainText())).isEqualTo("This is an encrypted message, with one embedded image and one dummy \n"
				+ "attachment.\n"
				+ "\n"
				+ "For testing purposes in the Simple Java Mail project.\n"
				+ "\n");

		assertThat(emailParsedFromEml.getEmbeddedImages()).hasSize(1);
		AttachmentResource embeddedImg = emailParsedFromEml.getEmbeddedImages().get(0);
		assertEmbeddedModuleArchitectureImage(embeddedImg, "part1.EDA02623.55A510EE@bennybottema.com");
		assertThat(emailParsedFromEml.getHTMLText()).contains(format("<img src=\"cid:%s\"", embeddedImg.getContentId()));

		assertThat(emailParsedFromEml.getAttachments()).hasSize(1);
		assertThat(emailParsedFromEml.getAttachments()).extracting("name").containsExactlyInAnyOrder("03-07-2005 errata SharnErrata.pdf");

		assertProtectedBody(emailParsedFromEml, SignatureStatus.UNSIGNED, EncryptionStatus.DECRYPTED);
		assertThat(emailParsedFromEml.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
	}

	@Test
	public void testEncryptedMessageEml_LegacySignedEnvelopedAttachment()
			throws Exception {
		SecureTestDataHelper.runTestWithSecureTestData(passwords -> {
			final Pkcs12Config pkcs12Config = Pkcs12Config.builder()
					.pkcs12Store(RESOURCES + "/secure-testdata/secure-testdata/legacy-signed-enveloped-email/7acc30df-26dd-40b3-9d45-e31f681e755b.p12")
					.storePassword(passwords.getProperty("legacy-signed-enveloped-email-zip-keystore-password"))
					.keyAlias("sectigo limited id von ")
					.keyPassword(passwords.getProperty("legacy-signed-enveloped-email-zip-key-password"))
					.build();
			Email emailParsedFromEml = EmailConverter.emlToEmail(new File(RESOURCES + "/secure-testdata/secure-testdata/legacy-signed-enveloped-email/email.eml"), pkcs12Config);

			EmailAssert.assertThat(emailParsedFromEml).hasSubject("Ausarbeitung einer Schutzrechtsanmeldung : R.389390, Hr/Pv");

			assertThat(normalizeNewlines(emailParsedFromEml.getPlainText())).doesNotStartWith("This is a multipart message in MIME format.");
			assertThat(normalizeNewlines(emailParsedFromEml.getPlainText())).contains("Sehr geehrte Damen und Herren,");

			assertThat(emailParsedFromEml.getEmbeddedImages()).isEmpty();

			assertThat(emailParsedFromEml.getAttachments()).extracting("name")
					.containsExactlyInAnyOrder(
							"PDmembran_m_Stuelement_pdf_3373833.pdf",
							"IN_COVER_SHEET_3374652_pdf_3374715.pdf",
							"389390_Pruefung_des_Entwurfs_3493097.doc"
					);

			assertProtectedBody(emailParsedFromEml, SignatureStatus.VERIFIED, EncryptionStatus.DECRYPTED);
		});
	}

	@Test
	public void testNPE_InSmimeUtilFixed()
			throws Exception {
		// for some reason running this test from CLI fails without this line
		// I think a previous cleanup failed or couldn't be executed successfully
		SecureTestDataHelper.runTestWithSecureTestData(passwords -> {
			String fileNameMsg = RESOURCES + "/secure-testdata/secure-testdata/npe-SmimeUtilFixed-test-email/NPE_SmimeUtilFixed Test Mail.msg";
			FileInputStream fileInputStream = new FileInputStream(fileNameMsg);
			EmailConverter.outlookMsgToEmail(fileInputStream);
			// ok, no NPE
		});
	}

	@Test
	public void testSignedAndEncryptedMessageMsg() {
		Email emailParsedFromMsg = EmailConverter.outlookMsgToEmail(new File(RESOURCES_MESSAGES + "/S_MIME test message signed & encrypted.msg"), loadPkcs12KeyStore());

		EmailAssert.assertThat(emailParsedFromMsg).hasFromRecipient(new Recipient("Benny Bottema", "benny@bennybottema.com", null, null));
		EmailAssert.assertThat(emailParsedFromMsg).hasSubject("S/MIME test message signed & encrypted");
		EmailAssert.assertThat(emailParsedFromMsg).hasOnlyRecipients(new Recipient("Benny Bottema", "benny@bennybottema.com", TO, null));

		assertThat(normalizeNewlines(emailParsedFromMsg.getPlainText())).isEqualTo("This is an encrypted message, with one embedded image and one dummy \n"
				+ "attachment.\n"
				+ "\n"
				+ "For testing purposes in the Simple Java Mail project.\n"
				+ "\n");

		assertThat(emailParsedFromMsg.getEmbeddedImages()).hasSize(1);
		AttachmentResource embeddedImg = emailParsedFromMsg.getEmbeddedImages().get(0);
		assertEmbeddedModuleArchitectureImage(embeddedImg, "part1.0B245DA7.F5872CD5@bennybottema.com");
		assertThat(emailParsedFromMsg.getHTMLText()).contains(format("<img src=\"cid:%s\"", embeddedImg.getContentId()));

		assertThat(emailParsedFromMsg.getAttachments()).hasSize(1);
		assertThat(emailParsedFromMsg.getAttachments()).extracting("name")
				.containsExactlyInAnyOrder("03-07-2005 errata SharnErrata.pdf");

		assertProtectedBody(emailParsedFromMsg, SignatureStatus.VERIFIED, EncryptionStatus.DECRYPTED);
        assertThat(emailParsedFromMsg.getInspection().getSmime().getSignatures()).singleElement()
                .satisfies(signature -> assertThat(signature.getSignerName()).contains("Benny Bottema"));
	}

    private static void assertProtectedBody(final Email email, final SignatureStatus signature, final EncryptionStatus encryption) {
        assertThat(email.getInspection().getPlainText().getSignatureStatus()).isEqualTo(signature);
        assertThat(email.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(encryption);
    }

	private static String assumeSystemVariablePresent(String name) {
		final String systemVariable = System.getenv(name);
		assumeThat(systemVariable).as("system variable " + name).isNotNull();
		return systemVariable;
	}

	private static String createInvalidSignedEml()
			throws Exception {
		final String signedEml = new String(Files.readAllBytes(Paths.get(RESOURCES_MESSAGES + "/S_MIME test message signed.eml")), UTF_8);
		final String invalidSignedEml = signedEml.replaceFirst("dummy=20(\\r?\\n)attachment\\.", "dummy=20$1BROKEN attachment.");
		assertThat(invalidSignedEml).as("test fixture corruption should be applied").isNotEqualTo(signedEml);
		return invalidSignedEml;
	}

	private static String extractMessageBody(final String eml) {
		final int crlfBodyStart = eml.indexOf("\r\n\r\n");
		if (crlfBodyStart >= 0) {
			return eml.substring(crlfBodyStart + 4);
		}
		final int lfBodyStart = eml.indexOf("\n\n");
		if (lfBodyStart >= 0) {
			return eml.substring(lfBodyStart + 2);
		}
		throw new IllegalArgumentException("Could not find message body in test fixture");
	}

	private static byte[] readFile(final File file) {
		try {
			return Files.readAllBytes(file.toPath());
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void assertEmbeddedModuleArchitectureImage(final AttachmentResource embeddedImg, final String contentId) {
		assertThat(embeddedImg.getName()).isEqualTo("module_architecture.png");
		assertThat(embeddedImg.getContentId()).isEqualTo(contentId);
		assertThat(embeddedImg.getDataSource().getName()).isEqualTo("module_architecture.png");
		try {
			final ContentType contentType = new ContentType(embeddedImg.getDataSource().getContentType());
			assertThat(contentType.getBaseType()).isEqualTo("image/png");
			assertThat(contentType.getParameter("name")).isEqualTo("module_architecture.png");
			assertThat(contentType.getParameterList().size()).isEqualTo(1);
		} catch (final ParseException invalidContentType) {
			throw new AssertionError("Invalid embedded image Content-Type", invalidContentType);
		}
	}
}
