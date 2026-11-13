package org.simplejavamail.converter;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Properties;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class ImportedEmptyBodyTest {

	private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
	private static final String HEADERS = "From: sender@example.org\r\nTo: reader@example.org\r\n"
			+ "Subject: Empty alternatives\r\nMIME-Version: 1.0\r\n";

	@ParameterizedTest
	@MethodSource("emptyBodyInputs")
	void keepsPresentEmptyAlternativesDistinctFromAbsentOnes(final ImportRoute route, final BodyShape shape) throws Exception {
		assertBodies(convert(route, message(shape)), shape);
	}

	@Test
	void readsAnEmptyBodyFromAnInMemoryMimeMessage() throws Exception {
		final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()));
		source.setContent("", "text/html; charset=UTF-8");

		assertBodies(MAIL.converter().fromMimeMessage(source).toEmail(), BodyShape.HTML);
	}

	@ParameterizedTest
	@EnumSource(BodyShape.class)
	void copyingSerializationAndOrdinaryExportRetainEmptyAlternatives(final BodyShape shape) throws Exception {
		final Email imported = MAIL.converter().fromEml(message(shape)).toEmail();
		final Email copied = MAIL.emailBuilder().copying(imported).buildEmail();
		final Email restored = roundTrip(copied);

		assertBodies(copied, shape);
		assertBodies(restored, shape);
		final MimeMessage exported = MAIL.converter().fromEmail(restored).toMimeMessage();
		assertThat(exported.isMimeType(shape == BodyShape.BOTH ? "multipart/alternative"
				: shape == BodyShape.PLAIN ? "text/plain" : "text/html")).isTrue();
		assertBodies(MAIL.converter().fromMimeMessage(exported).toEmail(), shape);
		assertBodies(MAIL.converter().fromEml(MAIL.converter().fromEmail(restored).toEmlBytes()).toEmail(), shape);
	}

	@ParameterizedTest
	@EnumSource(BodyShape.class)
	void ordinaryDefaultsFillOnlyAbsentAlternatives(final BodyShape shape) throws Exception {
		final Email imported = MAIL.converter().fromEml(message(shape)).toEmail();
		final Email defaults = MAIL.emailBuilder().startingBlank()
				.withPlainText("Default plain").withHTMLText("<p>Default HTML</p>").buildEmail();
		try (Mailer mailer = MAIL.mailerBuilder().withSMTPServer("smtp.example.org", 25).withEmailDefaults(defaults).buildMailer()) {
			final Email completed = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(imported);
			assertThat(completed.getPlainText()).isEqualTo(shape == BodyShape.HTML ? "Default plain" : "");
			assertThat(completed.getHTMLText()).isEqualTo(shape == BodyShape.PLAIN ? "<p>Default HTML</p>" : "");
			assertBodies(imported, shape);
		}
	}

	@ParameterizedTest
	@EnumSource(BodyShape.class)
	void exactInspectionRetainsBodyPresenceWithoutChangingAuthoritativeBytes(final BodyShape shape) {
		final byte[] source = message(shape).getBytes(UTF_8);
		final Email exact = MAIL.emailBuilder().startingFromExactEml(source)
				.withEnvelopeRecipients("reader@example.org").buildEmail();

		assertBodies(exact, shape);
		assertThat(MAIL.converter().fromEmail(exact).toEmlBytes()).isEqualTo(source);
	}

	@Test
	void keepsWhitespaceAndConcatenatesRepeatedPartsInEncounterOrder() {
		final String source = multipart("mixed", part("text/plain", "") + "\r\n--empty-body\r\n"
				+ part("text/plain", " \t") + "\r\n--empty-body\r\n" + part("text/plain", "next")
				+ "\r\n--empty-body\r\n" + part("text/html", "<p>first</p>")
				+ "\r\n--empty-body\r\n" + part("text/html", "")
				+ "\r\n--empty-body\r\n" + part("text/html", " \t"));
		final Email imported = MAIL.converter().fromEml(source).toEmail();

		assertThat(imported.getPlainText()).isEqualTo(" \tnext");
		assertThat(imported.getHTMLText()).isEqualTo("<p>first</p> \t");
	}

	@Test
	void textAttachmentsAndTheirNestedBodiesDoNotEstablishParentAlternatives() {
		final String source = multipart("mixed",
				"Content-Type: text/plain; charset=UTF-8\r\nContent-Disposition: attachment; filename=empty.txt\r\n\r\n"
				+ "\r\n--empty-body\r\nContent-Type: multipart/alternative; boundary=attached\r\n"
				+ "Content-Disposition: attachment; filename=alternatives.mime\r\n\r\n"
				+ "--attached\r\n" + part("text/plain", "") + "\r\n--attached\r\n"
				+ part("text/html", "") + "\r\n--attached--\r\n");
		final Email imported = MAIL.converter().fromEml(source).toEmail();

		assertThat(imported.getPlainText()).isNull();
		assertThat(imported.getHTMLText()).isNull();
		assertThat(imported.getAttachments()).hasSize(2);
	}

	private static Stream<Arguments> emptyBodyInputs() {
		return Stream.of(
				Arguments.of(ImportRoute.STATIC_EML, BodyShape.BOTH),
				Arguments.of(ImportRoute.FLUENT_EML_EAGER, BodyShape.BOTH),
				Arguments.of(ImportRoute.FLUENT_EML_LAZY, BodyShape.BOTH),
				Arguments.of(ImportRoute.STATIC_MIME_EAGER, BodyShape.BOTH),
				Arguments.of(ImportRoute.STATIC_MIME_LAZY, BodyShape.BOTH),
				Arguments.of(ImportRoute.FLUENT_MIME_EAGER, BodyShape.BOTH),
				Arguments.of(ImportRoute.FLUENT_MIME_LAZY, BodyShape.BOTH),
				Arguments.of(ImportRoute.FLUENT_EML_EAGER, BodyShape.PLAIN),
				Arguments.of(ImportRoute.FLUENT_EML_EAGER, BodyShape.HTML));
	}

	private static String message(final BodyShape shape) {
		if (shape == BodyShape.BOTH) {
			return multipart("alternative", part("text/plain", "") + "\r\n--empty-body\r\n" + part("text/html", ""));
		}
		return HEADERS + part(shape == BodyShape.PLAIN ? "text/plain" : "text/html", "");
	}

	private static String multipart(final String subtype, final String content) {
		return HEADERS + "Content-Type: multipart/" + subtype + "; boundary=empty-body\r\n\r\n"
				+ "--empty-body\r\n" + content + "\r\n--empty-body--\r\n";
	}

	private static String part(final String type, final String content) {
		return "Content-Type: " + type + "; charset=UTF-8\r\n\r\n" + content;
	}

	private static void assertBodies(final Email email, final BodyShape shape) {
		assertThat(email.getPlainText()).isEqualTo(shape == BodyShape.HTML ? null : "");
		assertThat(email.getHTMLText()).isEqualTo(shape == BodyShape.PLAIN ? null : "");
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

	private static Email roundTrip(final Email email) throws Exception {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
			output.writeObject(email);
		}
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			return (Email) input.readObject();
		}
	}

	private enum BodyShape { PLAIN, HTML, BOTH }

	private enum ImportRoute {
		STATIC_EML, FLUENT_EML_EAGER, FLUENT_EML_LAZY, STATIC_MIME_EAGER, STATIC_MIME_LAZY, FLUENT_MIME_EAGER, FLUENT_MIME_LAZY
	}
}
