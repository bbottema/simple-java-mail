package org.simplejavamail.converter;

import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.SignatureVerification;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import testutil.ConfigLoaderTestHelper;

import java.util.Arrays;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class RootAttachmentDispositionImportTest {

	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

	@ParameterizedTest
	@MethodSource("rootBodies")
	void interpretsAnEffectiveSinglePartRootRegardlessOfDisposition(final Body body, final boolean fluent) {
		final String source = headers(body.type) + "Content-Disposition: attachment; filename=mail.txt\r\n\r\n" + body.content;
		final Email imported = fluent ? mail.converter().fromEml(source).toEmail() : EmailConverter.emlToEmail(source);
		body.assertContent(imported);
		assertThat(imported.getAttachments()).isEmpty();
		assertThat(imported.getEmbeddedImages()).isEmpty();
	}

	@ParameterizedTest
	@MethodSource("rootBodies")
	void keepsTheSameTextTypeAsAnAttachmentWhenItIsAChild(final Body body, final boolean eager) {
		final String source = headers("multipart/mixed; boundary=outer") + "\r\n"
				+ "--outer\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nVisible parent\r\n"
				+ "--outer\r\nContent-Type: " + body.type + "\r\n"
				+ "Content-Disposition: attachment; filename=child.txt\r\n\r\n" + body.content + "\r\n--outer--\r\n";
		final Email imported = mail.converter().fromEml(source).withFetchAttachmentData(eager).toEmail();
		assertThat(imported.getPlainText()).isEqualTo("Visible parent");
		assertThat(imported.getHTMLText()).isNull();
		assertThat(imported.getCalendarText()).isNull();
		assertThat(imported.getAttachments()).hasSize(1);
		assertThat(imported.getAttachments().get(0).getName()).isEqualTo("child.txt");
		assertThat(imported.getAttachments().get(0).readAllBytes()).isEqualTo(body.content.getBytes(UTF_8));
	}

	@Test
	void preservesAnExplicitlyEmptyRootBody() {
		final Email imported = mail.converter().fromEml(headers("text/plain; charset=UTF-8")
				+ "Content-Disposition: attachment\r\n\r\n").toEmail();
		assertThat(imported.getPlainText()).isEmpty();
		assertThat(imported.getAttachments()).isEmpty();
	}

	@Test
	void interpretsTheEffectiveRootAfterSignatureInspectionWithoutChangingProtectedBytes() throws Exception {
		final jakarta.mail.internet.MimeMessage clear = EmailConverter.emlToMimeMessage(headers(Body.PLAIN.type)
				+ "Content-Disposition: attachment; filename=mail.txt\r\n\r\n" + Body.PLAIN.content);
		final Email identity = mail.emailBuilder().startingBlank().from("sender@example.org").buildEmail();
		final jakarta.mail.internet.MimeMessage signed = org.simplejavamail.internal.moduleloader.ModuleLoader.loadSmimeModule()
				.createRuntime(org.simplejavamail.config.ConfigLoader.DEFAULT_SMIME_KEY_CACHE_CAPACITY)
				.signMessageWithSmime(clear.getSession(), identity, clear,
						SmimeSigningConfig.builder().pkcs12Config(loadPkcs12KeyStore()).build());
		final byte[] source = EmailConverter.mimeMessageToEMLByteArray(signed);
		final MimeMultipart protectedParts = (MimeMultipart) EmailConverter.emlToMimeMessage(new java.io.ByteArrayInputStream(source)).getContent();
		assertThat(protectedParts.getBodyPart(0).getDisposition()).isEqualTo("attachment");
		final Email imported = mail.converter().fromEml(source).toEmail();
		assertThat(imported.getPlainText()).isEqualTo(Body.PLAIN.content);
		assertThat(imported.getInspection().getSmime().getSignatures()).extracting("status").containsOnly(SignatureVerification.VERIFIED);
		final Email exact = mail.emailBuilder().startingFromExactEml(source).withEnvelopeRecipients("reader@example.org").buildEmail();
		assertThat(mail.converter().fromEmail(exact).toEmlBytes()).isEqualTo(source);
	}

	private static Stream<Arguments> rootBodies() {
		return Arrays.stream(Body.values()).flatMap(body -> Stream.of(false, true).map(route -> Arguments.of(body, route)));
	}

	private static String headers(final String type) {
		return "From: sender@example.org\r\nTo: reader@example.org\r\nMIME-Version: 1.0\r\nContent-Type: " + type + "\r\n";
	}

	private enum Body {
		PLAIN("text/plain; charset=UTF-8", "Readable body"),
		HTML("text/html; charset=UTF-8", "<p>Readable body</p>"),
		CALENDAR("text/calendar; charset=UTF-8; method=REQUEST", "BEGIN:VCALENDAR\r\nMETHOD:REQUEST\r\nEND:VCALENDAR");

		private final String type;
		private final String content;

		Body(final String type, final String content) { this.type = type; this.content = content; }

		private void assertContent(final Email email) {
			switch (this) {
				case PLAIN: assertThat(email.getPlainText()).isEqualTo(content); break;
				case HTML: assertThat(email.getHTMLText()).isEqualTo(content); break;
				case CALENDAR:
					assertThat(email.getCalendarText()).isEqualTo(content);
					assertThat(email.getCalendarMethod()).isEqualTo(CalendarMethod.REQUEST);
					break;
				default: throw new AssertionError(this);
			}
		}
	}
}
