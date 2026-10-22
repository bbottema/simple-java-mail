package org.simplejavamail.mailer;

import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cms.CMSAlgorithm;
import org.bouncycastle.cms.CMSEnvelopedData;
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.converter.internal.EmailConverterException;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.SMIMEModule;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.mailer.internal.MailerException;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class MailerHelperSmimeTest {

	private static final String RAW_BODY = "Caller changed the MIME body: Gr\u00fc\u00dfe";
	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
	@Test
	void noProtectionReturnsTheSameMessageWithoutReadingItOrLoadingTheModule() {
		final Session session = Session.getInstance(new Properties());
		final MimeMessage unreadable = new MimeMessage(session) {
			@Override public Object getContent() { throw new AssertionError("No-op must not read content"); }
		};
		final Email details = mail.emailBuilder().startingBlank().from("sender@example.org")
				.signWithDomainKey(DkimConfig.builder().dkimPrivateKeyData(new byte[] {1})
						.dkimSigningDomain("example.org").dkimSelector("unused").build()).buildEmail();
		try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class)) {
			assertThat(MailerHelper.signAndOrEncryptMessageWithSmime(session, unreadable, details)).isSameAs(unreadable);
			modules.verifyNoInteractions();
		}
	}

	@ParameterizedTest
	@EnumSource(value = Protection.class, names = {"SIGNING", "ENCRYPTION", "BOTH"})
	void protectsCallerModifiedMimeContentWithOnlyTheRequestedOperations(final Protection protection) throws Exception {
		final Email details = protection.configure(mail.emailBuilder().startingBlank()
				.from("sender@example.org").to("reader@example.org")
				.withSubject("Email model subject").withPlainText("Email model content")).buildEmail();
		final MimeMessage raw = rawMessage();
		final MimeMessage protectedMessage = MailerHelper.signAndOrEncryptMessageWithSmime(raw.getSession(), raw, details);

		assertProtectedContent(protectedMessage, protection, RAW_BODY);
		assertThat(protectedMessage.getSubject()).isEqualTo("Caller changed the subject");
		assertThat(protectedMessage.getHeader("X-Application-Trace")).containsExactly("first", "second");
		final Email restored = importProtected(protectedMessage, protection);
		assertThat(restored.getAttachments()).anySatisfy(attachment -> {
			assertThat(attachment.getName()).isEqualTo("caller-note.txt");
			assertThat(attachment.readAllData()).isEqualTo("Caller attachment");
		});
	}

	@ParameterizedTest
	@EnumSource(value = Protection.class, names = {"SIGNING", "ENCRYPTION", "BOTH"})
	void ordinaryConversionRetainsIndependentProtectionAndFinalization(final Protection protection) throws Exception {
		final Email details = protection.configure(mail.emailBuilder().startingBlank()
				.from("sender@example.org").to("reader@example.org")
				.withPlainText("Ordinary conversion")).buildEmail();
		assertProtectedContent(mail.converter().fromEmail(details).toMimeMessage(), protection, "Ordinary conversion");
	}

	@Test
	void bothEntryPointsApplyEachStageOnceAndFinalizeSigningBeforeEncryption() throws Exception {
		final Email details = Protection.BOTH.configure(mail.emailBuilder().startingBlank()
				.from("sender@example.org").to("reader@example.org").withPlainText("body")).buildEmail();
		final SMIMEModule smime = spy(ModuleLoader.loadSmimeModule());
		try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
			modules.when(ModuleLoader::loadSmimeModule).thenReturn(smime);
			for (boolean helper : new boolean[] {true, false}) {
				clearInvocations(smime);
				if (helper) {
					final MimeMessage raw = rawMessage();
					MailerHelper.signAndOrEncryptMessageWithSmime(raw.getSession(), raw, details);
				} else {
					mail.converter().fromEmail(details).toMimeMessage();
				}
				final InOrder stages = inOrder(smime);
				stages.verify(smime).signMessageWithSmime(any(), any(), any(), same(details.getSmimeSigningConfig()));
				final ArgumentCaptor<MimeMessage> encryptionInput = ArgumentCaptor.forClass(MimeMessage.class);
				stages.verify(smime).encryptMessageWithSmime(any(), any(), encryptionInput.capture(), same(details.getSmimeEncryptionConfig()));
				stages.verifyNoMoreInteractions();
				assertThat(encryptionInput.getValue()).isInstanceOf(FinalizedMimeMessage.class);
				assertThat(encryptionInput.getValue().isMimeType("multipart/signed")).isTrue();
			}
		}
	}

	@ParameterizedTest
	@MethodSource("recipientRoles")
	void recipientCertificatesWorkWithoutAnEmailCertificateForEveryRecipientRole(final RecipientType role) throws Exception {
		final Email details = mail.emailBuilder().startingBlank()
				.withRecipients(new Recipient(null, "reader@example.org", role, certificate("smime_test_user.pem.standard.crt")),
						new Recipient(null, "other@example.org", TO, null))
				.buildEmail();
		assertThat(details.getRecipients().get(0).getType()).isEqualTo(role == null ? TO : role);
		final MimeMessage raw = rawMessage();
		final MimeMessage encrypted = MailerHelper.signAndOrEncryptMessageWithSmime(raw.getSession(), raw, details);
		assertProtectedContent(encrypted, Protection.ENCRYPTION, RAW_BODY);
		assertThat(envelope(encrypted).getRecipientInfos().getRecipients()).hasSize(1);
	}

	@Test
	void recipientCertificateWinsAndEmailAlgorithmsStillApply() throws Exception {
		final X509Certificate reader = certificate("smime_test_user.pem.standard.crt");
		final X509Certificate fallback = certificate("ca.crt");
		final Email details = mail.emailBuilder().startingBlank()
				.withRecipients(new Recipient(null, "reader@example.org", TO, reader))
				.encryptWithSmime(SmimeEncryptionConfig.builder().x509Certificate(fallback)
						.keyEncapsulationAlgorithm("RSA_OAEP_SHA256").cipherAlgorithm("AES256_CBC").build())
				.buildEmail();
		final MimeMessage raw = rawMessage();
		final MimeMessage encrypted = MailerHelper.signAndOrEncryptMessageWithSmime(raw.getSession(), raw, details);
		assertProtectedContent(encrypted, Protection.ENCRYPTION, RAW_BODY);
		final CMSEnvelopedData envelope = envelope(encrypted);
		assertThat(envelope.getRecipientInfos().get(new JceKeyTransRecipientId(reader))).isNotNull();
		assertThat(envelope.getRecipientInfos().get(new JceKeyTransRecipientId(fallback))).isNull();
		assertThat(envelope.getEncryptionAlgOID()).isEqualTo(CMSAlgorithm.AES256_CBC.getId());
		assertThat(envelope.getRecipientInfos().getRecipients()).allSatisfy(info ->
				assertThat(info.getKeyEncryptionAlgOID()).isEqualTo(PKCSObjectIdentifiers.id_RSAES_OAEP.getId()));
	}

	@Test
	void anUncertifiedRecipientUsesTheEmailCertificateFallback() throws Exception {
		final X509Certificate explicit = certificate("ca.crt");
		final X509Certificate fallback = certificate("smime_test_user.pem.standard.crt");
		final Email details = mail.emailBuilder().startingBlank()
				.withRecipients(new Recipient(null, "explicit@example.org", TO, explicit),
						new Recipient(null, "fallback@example.org", CC, null))
				.encryptWithSmime(SmimeEncryptionConfig.builder().x509Certificate(fallback).build())
				.buildEmail();
		final MimeMessage raw = rawMessage();
		final MimeMessage encrypted = MailerHelper.signAndOrEncryptMessageWithSmime(raw.getSession(), raw, details);
		assertProtectedContent(encrypted, Protection.ENCRYPTION, RAW_BODY);
		final CMSEnvelopedData envelope = envelope(encrypted);
		assertThat(envelope.getRecipientInfos().getRecipients()).hasSize(2);
		assertThat(envelope.getRecipientInfos().get(new JceKeyTransRecipientId(explicit))).isNotNull();
		assertThat(envelope.getRecipientInfos().get(new JceKeyTransRecipientId(fallback))).isNotNull();
	}

	@Test
	void finalizationFailuresKeepTheirCauseAndEachEntryPointsFailureKind() throws Exception {
		final Email details = Protection.SIGNING.configure(mail.emailBuilder().startingBlank()
				.from("sender@example.org").to("reader@example.org").withPlainText("body")).buildEmail();
		final MessagingException failure = new MessagingException("Cannot finalize signed MIME");
		final MimeMessage unfinalizable = new MimeMessage(Session.getInstance(new Properties())) {
			@Override public void saveChanges() throws MessagingException { throw failure; }
		};
		final SMIMEModule smime = mock(SMIMEModule.class);
		when(smime.signMessageWithSmime(any(), any(Email.class), any(), same(details.getSmimeSigningConfig())))
				.thenReturn(unfinalizable);
		try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
			modules.when(ModuleLoader::loadSmimeModule).thenReturn(smime);
			assertThatThrownBy(() -> MailerHelper.signAndOrEncryptMessageWithSmime(unfinalizable.getSession(), rawMessage(), details))
					.isInstanceOf(MailerException.class).hasCause(failure)
					.satisfies(thrown -> assertThat(((MailerException) thrown).getKind()).isEqualTo(MailFailureKind.PREPARATION));
			assertThatThrownBy(() -> mail.converter().fromEmail(details).toMimeMessage())
					.isInstanceOf(EmailConverterException.class).hasCause(failure)
					.satisfies(thrown -> assertThat(((EmailConverterException) thrown).getKind()).isEqualTo(MailFailureKind.CONVERSION));
		}
	}

	@ParameterizedTest
	@EnumSource(Protection.class)
	void anAbsentModuleIsRequiredOnlyForRequestedProtection(final Protection protection) throws Exception {
		final String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		final URL[] urls = Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator))).map(entry -> {
			try { return new File(entry).toURI().toURL(); }
			catch (Exception failure) { throw new IllegalStateException(failure); }
		}).toArray(URL[]::new);
		try (URLClassLoader runtime = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()) {
			@Override protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
				if (name.startsWith("org.simplejavamail.internal.smimesupport.")) throw new ClassNotFoundException(name);
				return super.loadClass(name, resolve);
			}
		}) {
			final Thread caller = Thread.currentThread();
			final ClassLoader previous = caller.getContextClassLoader();
			try {
				caller.setContextClassLoader(runtime);
				final java.lang.reflect.Method probe = runtime.loadClass(WithoutSmimeProbe.class.getName()).getMethod("invoke", String.class);
				if (protection == Protection.NONE) {
					assertThat(probe.invoke(null, protection.name())).isEqualTo(true);
				} else {
					final Throwable failure = catchThrowable(() -> probe.invoke(null, protection.name()));
					assertThat(failure).isInstanceOf(InvocationTargetException.class);
					assertThat(failure.getCause()).hasMessageContaining("S/MIME module not found");
				}
			} finally {
				caller.setContextClassLoader(previous);
			}
		}
	}

	private void assertProtectedContent(final MimeMessage message, final Protection protection, final String expectedBody) throws Exception {
		assertThat(message).isInstanceOf(FinalizedMimeMessage.class);
		final byte[] first = serialize(message);
		message.saveChanges();
		assertThat(serialize(message)).isEqualTo(first);
		assertThat(message.isMimeType(protection.encrypt ? "application/pkcs7-mime" : "multipart/signed")).isTrue();
		final Email restored = importProtected(message, protection);
		assertThat(restored.getPlainText()).isEqualTo(expectedBody);
		assertThat(restored.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(protection.sign
				? (protection.encrypt ? OriginalSmimeDetails.SmimeMode.SIGNED_ENCRYPTED : OriginalSmimeDetails.SmimeMode.SIGNED)
				: OriginalSmimeDetails.SmimeMode.ENCRYPTED);
		assertThat(restored.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(protection.sign
				? OriginalSmimeDetails.VerificationStatus.VALID : OriginalSmimeDetails.VerificationStatus.NOT_SIGNED);
		assertThat(restored.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(protection.encrypt
				? OriginalSmimeDetails.DecryptionStatus.DECRYPTED : OriginalSmimeDetails.DecryptionStatus.NOT_ENCRYPTED);
	}

	private Email importProtected(final MimeMessage message, final Protection protection) throws Exception {
		final org.simplejavamail.api.converter.ConfiguredEmailConverter.MimeMessageConversion conversion = mail.converter()
				.fromMimeMessage(new MimeMessage(message.getSession(), new ByteArrayInputStream(serialize(message))));
		if (protection.encrypt) conversion.withSmimeDecryption(loadPkcs12KeyStore());
		return conversion.toEmail();
	}

	private static CMSEnvelopedData envelope(final MimeMessage message) throws Exception {
		return new CMSEnvelopedData(message.getInputStream());
	}

	private static byte[] serialize(final MimeMessage message) throws Exception {
		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		message.writeTo(output);
		return output.toByteArray();
	}

	private static MimeMessage rawMessage() throws Exception {
		final MimeMessage raw = new MimeMessage(Session.getInstance(new Properties()));
		raw.setFrom("sender@example.org");
		raw.setRecipients(TO, "reader@example.org");
		raw.setSubject("Caller changed the subject");
		raw.addHeader("X-Application-Trace", "first");
		raw.addHeader("X-Application-Trace", "second");
		final MimeBodyPart body = new MimeBodyPart();
		body.setText(RAW_BODY, "UTF-8");
		final MimeBodyPart attachment = new MimeBodyPart();
		attachment.setText("Caller attachment", "UTF-8");
		attachment.setFileName("caller-note.txt");
		final MimeMultipart content = new MimeMultipart("mixed");
		content.addBodyPart(body);
		content.addBodyPart(attachment);
		raw.setContent(content);
		raw.saveChanges();
		return raw;
	}

	private static X509Certificate certificate(final String filename) {
		return SmimeEncryptionConfig.builder().x509Certificate(new File(determineResourceFolder("simple-java-mail"),
				"test/resources/pkcs12/" + filename)).build().getX509Certificate();
	}

	private static Stream<RecipientType> recipientRoles() { return Stream.of(TO, CC, BCC, null); }

	private enum Protection {
		NONE(false, false), SIGNING(true, false), ENCRYPTION(false, true), BOTH(true, true), RECIPIENT(false, true);
		private final boolean sign;
		private final boolean encrypt;
		Protection(final boolean sign, final boolean encrypt) { this.sign = sign; this.encrypt = encrypt; }
		private EmailPopulatingBuilder configure(final EmailPopulatingBuilder builder) {
			if (sign) builder.signWithSmime(SmimeSigningConfig.builder().pkcs12Config(loadPkcs12KeyStore()).build());
			if (this == RECIPIENT) builder.withRecipients(new Recipient(null, "reader@example.org", TO, certificate("smime_test_user.pem.standard.crt")));
			else if (encrypt) builder.encryptWithSmime(SmimeEncryptionConfig.builder().x509Certificate(certificate("smime_test_user.pem.standard.crt")).build());
			return builder;
		}
	}

	public static final class WithoutSmimeProbe {
		public static boolean invoke(final String choice) throws Exception {
			final Email details = Protection.valueOf(choice).configure(SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig())
					.emailBuilder().startingBlank()).buildEmail();
			final MimeMessage raw = rawMessage();
			return MailerHelper.signAndOrEncryptMessageWithSmime(raw.getSession(), raw, details) == raw;
		}
	}
}
