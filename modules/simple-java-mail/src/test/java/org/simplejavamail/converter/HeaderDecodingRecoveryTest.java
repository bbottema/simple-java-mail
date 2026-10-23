package org.simplejavamail.converter;

import jakarta.mail.Header;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.mailer.MailSuspiciousCRLFValueException;
import org.simplejavamail.mailer.MailerHelper;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.util.Enumeration;
import java.util.Properties;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class HeaderDecodingRecoveryTest {

	private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
	private static final String UNDECODABLE = "=?X-NO-SUCH-CHARSET?Q?hello?=";
	private static final String PARTLY_DECODABLE = "=?UTF-8?Q?Known?= " + UNDECODABLE;
	private static final String ROOT = "From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Readable message\r\n";

	@ParameterizedTest
	@EnumSource(ImportRoute.class)
	void retainsTheCompleteOriginalGenericValueAndReadableNeighbors(final ImportRoute route) throws Exception {
		final Email imported = convert(route, multipart("X-Legacy: " + PARTLY_DECODABLE + "\r\n"
				+ "X-Decoded: =?UTF-8?Q?caf=C3=A9?=\r\nX-Neighbor: unchanged\r\n"
				+ "Disposition-Notification-To: Receipts <receipts@example.org>\r\n"));

		assertThat(imported.getHeaders().get("X-Legacy")).containsExactly(PARTLY_DECODABLE);
		assertThat(imported.getHeaders().get("X-Decoded")).containsExactly("caf\u00e9");
		assertThat(imported.getHeaders().get("X-Neighbor")).containsExactly("unchanged");
		assertThat(imported.getSubject()).isEqualTo("Readable message");
		assertThat(imported.getPlainText()).isEqualTo("Readable body");
		assertThat(imported.getFromRecipient().getAddress()).isEqualTo("sender@example.org");
		assertThat(imported.getRecipients()).extracting("address").containsExactly("reader@example.org");
		assertThat(imported.getDispositionNotificationToRecipients()).extracting("address").containsExactly("receipts@example.org");
		assertThat(imported.getUseDispositionNotificationTo()).isTrue();
		assertThat(imported.getAttachments()).hasSize(1);
		assertThat(imported.getAttachments().get(0).readAllBytes()).isEqualTo("payload".getBytes(UTF_8));
	}

	@ParameterizedTest
	@ValueSource(strings = {"\r\n ", "\r\n\t"})
	void safelyUnfoldedRecoveryCanBeRenderedValidatedAndRehearsed(final String fold) throws Exception {
		final String sourceValue = UNDECODABLE + fold + "neighboring text";
		final String expected = MimeUtility.unfold(sourceValue);
		final Email imported = MAIL.converter().fromEml(multipart("X-Legacy: " + sourceValue + "\r\n")).toEmail();

		assertThat(imported.getHeaders().get("X-Legacy")).containsExactly(expected);
		assertThat(expected).contains(UNDECODABLE, "neighboring text").doesNotContain("\r", "\n");
		assertThatNoException().isThrownBy(() -> MailerHelper.validate(imported));
		assertThat(MAIL.converter().fromEmail(imported).toMimeMessage().getHeader("X-Legacy")).containsExactly(expected);
		try (Mailer mailer = MAIL.mailerBuilder().withSMTPServer("unused.example.org", 25).buildMailer()) {
			final byte[] rendered = mailer.rehearse(imported).getEmlBytes();
			assertThat(MAIL.converter().fromEml(rendered).toEmail().getHeaders().get("X-Legacy")).containsExactly(expected);
		}
	}

	@Test
	void exactInspectionRecoveryDoesNotChangeTheAuthoritativeFoldedBytes() throws Exception {
		final byte[] original = multipart("X-Legacy: " + UNDECODABLE + "\r\n\tunchanged source folding\r\n").getBytes(UTF_8);
		final Email exact = MAIL.emailBuilder().startingFromExactEml(original).withEnvelopeRecipients("reader@example.org").buildEmail();

		assertThat(exact.getHeaders().get("X-Legacy")).containsExactly(UNDECODABLE + "\tunchanged source folding");
		assertThat(MAIL.converter().fromEmail(exact).toEmlBytes()).isEqualTo(original);
		try (Mailer mailer = MAIL.mailerBuilder().withSMTPServer("unused.example.org", 25).buildMailer()) {
			assertThat(mailer.rehearse(exact).getEmlBytes()).isEqualTo(original);
		}
	}

	@Test
	void signedImportKeepsTheVerifiedProtectedRepresentation() throws Exception {
		final Session session = Session.getInstance(new Properties());
		final MimeMessage plain = mimeMessage(multipart("X-Legacy: " + UNDECODABLE + "\r\n"));
		final Email sender = MAIL.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org").buildEmail();
		final MimeMessage signed = ModuleLoader.loadSmimeModule().signMessageWithSmime(session, sender, plain,
				SmimeSigningConfig.builder().pkcs12Config(loadPkcs12KeyStore()).build());
		final byte[] original = EmailConverter.mimeMessageToEMLByteArray(signed);
		final Email imported = MAIL.converter().fromEml(original).toEmail();

		assertThat(imported.getHeaders().get("X-Legacy")).containsExactly(UNDECODABLE);
		assertThat(imported.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(VerificationStatus.VALID);
		assertThat(imported.getOriginalSmimeDetails().getOriginalProtectedMessage()).isEqualTo(original);
		assertThat(imported.getPlainText()).isEqualTo("Readable body");
	}

	@ParameterizedTest
	@ValueSource(strings = {"\rInjected", "\nInjected", "\r\nInjected", "%0AInjected"})
	void recoveryDoesNotMakeUnsafeHeaderValuesSendable(final String unsafe) throws Exception {
		final MimeMessage source = mimeMessage(multipart(""));
		source.setHeader("X-Legacy", UNDECODABLE + unsafe);
		final Email imported = MAIL.converter().fromMimeMessage(source).toEmail();

		assertThat(imported.getHeaders().get("X-Legacy")).containsExactly(UNDECODABLE + unsafe);
		assertThatThrownBy(() -> MailerHelper.validate(imported)).isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessageContaining("email.header.[X-Legacy]");
		try (Mailer mailer = MAIL.mailerBuilder().withSMTPServer("unused.example.org", 25).buildMailer()) {
			assertThatThrownBy(() -> mailer.rehearse(imported)).isInstanceOf(MailSuspiciousCRLFValueException.class)
					.hasMessageContaining("email.header.[X-Legacy]");
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"Subject", "Disposition-Notification-To", "Return-Receipt-To", "Return-Path", "Content-Description", "message-id"})
	void typedHeaderDecodingRetainsItsExistingFailureContract(final String name) {
		assertThatThrownBy(() -> MAIL.converter().fromEml(multipart(name + ": " + UNDECODABLE + "\r\n")).toEmail())
				.isInstanceOf(MailException.class).hasRootCauseInstanceOf(UnsupportedEncodingException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"attachment-name", "attachment-description", "attachment-content", "body-content"})
	void requiredResourceAndContentDecodingStillFails(final String field) {
		final String source;
		switch (field) {
			case "attachment-name":
				source = multipart("").replace("filename=payload.bin", "filename=\"" + UNDECODABLE + "\"");
				break;
			case "attachment-description":
				source = multipart("").replace("filename=payload.bin\r\n", "filename=payload.bin\r\nContent-Description: " + UNDECODABLE + "\r\n");
				break;
			case "attachment-content":
				source = multipart("").replace("Content-Transfer-Encoding: base64", "Content-Transfer-Encoding: x-unsupported");
				break;
			default:
				source = ROOT + "Content-Type: text/plain; charset=X-NO-SUCH-CHARSET\r\n\r\nReadable body\r\n";
		}
		assertThatThrownBy(() -> MAIL.converter().fromEml(source).withFetchAttachmentData(true).toEmail())
				.isInstanceOf(MailException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"=?UTF-8?X?hello?=", UNDECODABLE + " =?UTF-8?X?hello?=", "=?UTF-8?X?hello?= " + UNDECODABLE})
	void unsupportedEncodedWordTransferEncodingRetainsItsFailureContract(final String value) {
		assertThatThrownBy(() -> MAIL.converter().fromEml(multipart("X-Legacy: " + value + "\r\n")).toEmail())
				.isInstanceOf(MailException.class).hasRootCauseInstanceOf(UnsupportedEncodingException.class);
	}

	@Test
	void unrelatedRuntimeFailuresFromTheSourceAreNotRecoveredAsHeaderDecoding() throws Exception {
		final IllegalStateException failure = new IllegalStateException("application header failure");
		final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(multipart("").getBytes(UTF_8))) {
			@Override
			public Enumeration<Header> getAllHeaders() {
				throw failure;
			}
		};

		assertThatThrownBy(() -> MAIL.converter().fromMimeMessage(source).toEmail()).isSameAs(failure);
	}

	private static Email convert(final ImportRoute route, final String source) throws Exception {
		switch (route) {
			case STATIC_EML: return EmailConverter.emlToEmail(source);
			case FLUENT_EML_EAGER: return MAIL.converter().fromEml(source).withFetchAttachmentData(true).toEmail();
			case FLUENT_EML_LAZY: return MAIL.converter().fromEml(source).withFetchAttachmentData(false).toEmail();
			default:
				final MimeMessage message = mimeMessage(source);
				final boolean eager = route == ImportRoute.STATIC_MIME_EAGER || route == ImportRoute.FLUENT_MIME_EAGER;
				return route == ImportRoute.STATIC_MIME_EAGER || route == ImportRoute.STATIC_MIME_LAZY
						? EmailConverter.mimeMessageToEmail(message, null, eager)
						: MAIL.converter().fromMimeMessage(message).withFetchAttachmentData(eager).toEmail();
		}
	}

	private static MimeMessage mimeMessage(final String source) throws Exception {
		return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(source.getBytes(UTF_8)));
	}

	private static String multipart(final String headers) {
		return ROOT + headers + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=parts\r\n\r\n"
				+ "--parts\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nReadable body\r\n"
				+ "--parts\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=payload.bin\r\n"
				+ "Content-Transfer-Encoding: base64\r\n\r\ncGF5bG9hZA==\r\n--parts--\r\n";
	}

	private enum ImportRoute {
		STATIC_EML, FLUENT_EML_EAGER, FLUENT_EML_LAZY, STATIC_MIME_EAGER, STATIC_MIME_LAZY, FLUENT_MIME_EAGER, FLUENT_MIME_LAZY
	}
}
