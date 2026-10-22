package org.simplejavamail.converter;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class ReceiptHeaderImportTest {

	private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
	private static final String ROOT = "From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Receipt request\r\n";
	private static final String[][] NAMES = {
			{"Disposition-Notification-To", "Return-Receipt-To"},
			{"disposition-notification-to", "return-receipt-to"},
			{"DISPOSITION-NOTIFICATION-TO", "RETURN-RECEIPT-TO"},
			{"dIsPoSiTiOn-NoTiFiCaTiOn-To", "rEtUrN-rEcEiPt-To"}
	};

	@ParameterizedTest
	@MethodSource("caseVariants")
	void everyHeaderSpellingExposesCompleteOrderedDestinations(final ImportRoute route,
			final String dispositionName, final String returnName) throws Exception {
		final String headers = dispositionName + ": =?UTF-8?Q?J=C3=B6rg?= <First@EXAMPLE.ORG>, Second <second@example.org>\r\n"
				+ dispositionName + ": =?UTF-8?Q?J=C3=B6rg?= <First@EXAMPLE.ORG>\r\n"
				+ returnName + ": Returns <Return@EXAMPLE.ORG>, Backup <backup@example.org>\r\n"
				+ returnName + ": Returns <Return@EXAMPLE.ORG>\r\nX-Custom-Case: unchanged\r\n";
		final Email email = convert(route, multipart(headers));
		assertThat(email.getDispositionNotificationToRecipients()).extracting("address")
				.containsExactly("First@EXAMPLE.ORG", "second@example.org", "First@EXAMPLE.ORG");
		assertThat(email.getDispositionNotificationToRecipients()).extracting("name").containsExactly("Jörg", "Second", "Jörg");
		assertThat(email.getReturnReceiptToRecipients()).extracting("address")
				.containsExactly("Return@EXAMPLE.ORG", "backup@example.org", "Return@EXAMPLE.ORG");
		assertThat(email.getUseDispositionNotificationTo()).isTrue();
		assertThat(email.getUseReturnReceiptTo()).isTrue();
		assertThat(email.getHeaders().keySet()).noneMatch(name -> name.equalsIgnoreCase("Disposition-Notification-To")
				|| name.equalsIgnoreCase("Return-Receipt-To"));
		assertThat(email.getHeaders().get("X-Custom-Case")).containsExactly("unchanged");
		assertThat(email.getPlainText()).isEqualTo("Readable body");
		assertThat(email.getAttachments().get(0).readAllBytes()).isEqualTo("payload".getBytes(UTF_8));
	}

	static Stream<Arguments> caseVariants() {
		return Arrays.stream(ImportRoute.values()).flatMap(route -> Arrays.stream(NAMES)
				.map(names -> Arguments.of(route, names[0], names[1])));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "<>", "(comment only)", "broken <address"})
	void existingValueInterpretationDoesNotDependOnHeaderCase(final String value) {
		final Email canonical = MAIL.converter().fromEml(multipart(NAMES[0][0] + ": " + value + "\r\n"
				+ NAMES[0][1] + ": " + value + "\r\n")).toEmail();
		for (final String[] names : NAMES) {
			final Email email = MAIL.converter().fromEml(multipart(names[0] + ": " + value + "\r\n"
					+ names[1] + ": " + value + "\r\n")).toEmail();
			assertThat(email.getDispositionNotificationToRecipients()).isEqualTo(canonical.getDispositionNotificationToRecipients());
			assertThat(email.getReturnReceiptToRecipients()).isEqualTo(canonical.getReturnReceiptToRecipients());
			assertThat(email.getUseDispositionNotificationTo()).isEqualTo(canonical.getUseDispositionNotificationTo());
			assertThat(email.getUseReturnReceiptTo()).isEqualTo(canonical.getUseReturnReceiptTo());
			// Blank and explicit null-path values retain their existing generic-header route and supplied name spelling.
			assertThat(email.getHeaders().containsKey(names[0])).isEqualTo(canonical.getHeaders().containsKey(NAMES[0][0]));
			assertThat(email.getHeaders().containsKey(names[1])).isEqualTo(canonical.getHeaders().containsKey(NAMES[0][1]));
		}
	}

	@ParameterizedTest
	@EnumSource(ImportRoute.class)
	void childRequestsOfAnyCaseCannotSupplyParentDestinations(final ImportRoute route) throws Exception {
		final Email absent = convert(route, multipart(""));
		assertThat(absent.getDispositionNotificationToRecipients()).isEmpty();
		assertThat(absent.getReturnReceiptToRecipients()).isEmpty();
		assertThat(absent.getUseDispositionNotificationTo()).isNotEqualTo(Boolean.TRUE);
		assertThat(absent.getUseReturnReceiptTo()).isNotEqualTo(Boolean.TRUE);
		final Email present = convert(route, multipart("disposition-notification-to: parent@example.org\r\n"
				+ "RETURN-RECEIPT-TO: parent-return@example.org\r\n"));
		assertParentDestinations(present);
	}

	@Test
	void separatelyImportedAttachedMessageOwnsItsReceiptHeaders() {
		final String nested = ROOT + "disposition-notification-to: nested@example.org\r\n"
				+ "RETURN-RECEIPT-TO: nested-return@example.org\r\n\r\nNested body";
		final String source = ROOT + "Content-Type: multipart/mixed; boundary=parts\r\n\r\n--parts\r\n"
				+ "Content-Type: message/rfc822\r\nContent-Disposition: attachment; filename=nested.eml\r\n\r\n"
				+ nested + "\r\n--parts--\r\n";
		final Email parent = MAIL.converter().fromEml(source).toEmail();
		assertThat(parent.getDispositionNotificationToRecipients()).isEmpty();
		assertThat(parent.getReturnReceiptToRecipients()).isEmpty();
		final Email attached = MAIL.converter().fromEml(parent.getAttachments().get(0).readAllBytes()).toEmail();
		assertThat(attached.getDispositionNotificationToRecipients()).extracting("address").containsExactly("nested@example.org");
		assertThat(attached.getReturnReceiptToRecipients()).extracting("address").containsExactly("nested-return@example.org");
	}

	@Test
	void exactInspectionRecognizesHeaderNamesWithoutChangingAuthoritativeBytes() {
		final byte[] source = parentSource().getBytes(UTF_8);
		final Email exact = MAIL.emailBuilder().startingFromExactEml(source).withEnvelopeRecipients("reader@example.org").buildEmail();
		assertParentDestinations(exact);
		assertThat(MAIL.converter().fromEmail(exact).toEmlBytes()).isEqualTo(source);
	}

	@Test
	void signedImportUsesEffectiveRootAndKeepsOriginalSignatureBytes() throws Exception {
		final Session session = Session.getInstance(new Properties());
		final MimeMessage plain = new MimeMessage(session, new ByteArrayInputStream(parentSource().getBytes(UTF_8)));
		final Email sender = MAIL.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org").buildEmail();
		final MimeMessage signed = ModuleLoader.loadSmimeModule().signMessageWithSmime(session, sender, plain,
				SmimeSigningConfig.builder().pkcs12Config(loadPkcs12KeyStore()).build());
		final byte[] source = EmailConverter.mimeMessageToEMLByteArray(signed);
		final Email email = MAIL.converter().fromEml(source).toEmail();
		assertParentDestinations(email);
		assertThat(email.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(VerificationStatus.VALID);
		assertThat(email.getOriginalSmimeDetails().getOriginalProtectedMessage()).isEqualTo(source);
	}

	private static String parentSource() {
		return multipart("disposition-notification-to: parent@example.org\r\nRETURN-RECEIPT-TO: parent-return@example.org\r\n");
	}

	private static void assertParentDestinations(final Email email) {
		assertThat(email.getDispositionNotificationToRecipients()).extracting("address").containsExactly("parent@example.org");
		assertThat(email.getReturnReceiptToRecipients()).extracting("address").containsExactly("parent-return@example.org");
		assertThat(email.getUseDispositionNotificationTo()).isTrue();
		assertThat(email.getUseReturnReceiptTo()).isTrue();
	}

	private static String multipart(final String rootHeaders) {
		return ROOT + rootHeaders + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=parts\r\n\r\n"
				+ "--parts\r\nContent-Type: text/plain\r\ndisposition-notification-to: body@example.org\r\n\r\nReadable body\r\n"
				+ "--parts\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=payload.bin\r\n"
				+ "Content-Transfer-Encoding: base64\r\nDISPOSITION-NOTIFICATION-TO: child@example.org\r\n"
				+ "rEtUrN-rEcEiPt-To: child-return@example.org\r\n\r\ncGF5bG9hZA==\r\n--parts--\r\n";
	}

	private static Email convert(final ImportRoute route, final String source) throws Exception {
		switch (route) {
			case STATIC_EML: return EmailConverter.emlToEmail(source);
			case FLUENT_EML_EAGER: return MAIL.converter().fromEml(source).withFetchAttachmentData(true).toEmail();
			case FLUENT_EML_LAZY: return MAIL.converter().fromEml(source).withFetchAttachmentData(false).toEmail();
			default:
				final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(source.getBytes(UTF_8)));
				final boolean eager = route == ImportRoute.STATIC_MIME_EAGER || route == ImportRoute.FLUENT_MIME_EAGER;
				return route == ImportRoute.STATIC_MIME_EAGER || route == ImportRoute.STATIC_MIME_LAZY
						? EmailConverter.mimeMessageToEmail(message, null, eager)
						: MAIL.converter().fromMimeMessage(message).withFetchAttachmentData(eager).toEmail();
		}
	}

	private enum ImportRoute {
		STATIC_EML, FLUENT_EML_EAGER, FLUENT_EML_LAZY, STATIC_MIME_EAGER, STATIC_MIME_LAZY, FLUENT_MIME_EAGER, FLUENT_MIME_LAZY
	}
}
