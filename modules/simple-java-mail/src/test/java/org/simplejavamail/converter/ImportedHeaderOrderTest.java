package org.simplejavamail.converter;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Properties;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class ImportedHeaderOrderTest {

	private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
	private static final String SOURCE = "From: sender@example.org\r\nTo: reader@example.org\r\n"
			+ "Subject: Header order\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n"
			+ "X-Step: first\r\nx-step: second\r\nX-Step: third\r\nX-STEP: first\r\nx-step:\r\n"
			+ "X-Unrelated: kept\r\n\r\nMessage body";

	@ParameterizedTest
	@EnumSource(ImportRoute.class)
	void mixedCaseNamesKeepTheirFirstSpellingAndDistinctValueOrder(final ImportRoute route) throws Exception {
		final Email imported = convert(route, SOURCE);

		assertHeaderFacts(imported);
		assertThat(imported.getFromRecipient().getAddress()).isEqualTo("sender@example.org");
		assertThat(imported.getPlainText()).isEqualTo("Message body");
	}

	@Test
	void copyingSerializationAndReconstructedExportRetainImportedHeaderOrder() throws Exception {
		final Email imported = MAIL.converter().fromEml(SOURCE).toEmail();
		final Email copied = MAIL.emailBuilder().copying(imported).buildEmail();
		final Email restored = roundTrip(copied);

		assertHeaderFacts(imported);
		assertHeaderFacts(copied);
		assertHeaderFacts(restored);
		final MimeMessage exported = MAIL.converter().fromEmail(restored).toMimeMessage();
		assertThat(exported.getHeader("x-step")).containsExactly("first", "second", "third", "");
		assertHeaderFacts(MAIL.converter().fromMimeMessage(exported).toEmail());
	}

	@Test
	void exactInspectionKeepsHeaderOrderWithoutChangingSourceBytes() {
		final byte[] source = (SOURCE + "\r\n").getBytes(UTF_8);
		final Email exact = MAIL.emailBuilder().startingFromExactEml(source)
				.withEnvelopeRecipients("reader@example.org").buildEmail();

		assertHeaderFacts(exact);
		assertThat(MAIL.converter().fromEmail(exact).toEmlBytes()).isEqualTo(source);
	}

	private static void assertHeaderFacts(final Email email) {
		assertThat(email.getHeaders().entrySet().stream()
				.filter(entry -> entry.getKey().equalsIgnoreCase("X-Step")).findFirst().orElseThrow().getValue())
				.containsExactly("first", "second", "third", "");
		assertThat(email.getHeaders()).containsOnlyKeys("X-Step", "X-Unrelated");
		assertThat(email.getHeaders().get("X-Step")).containsExactly("first", "second", "third", "");
		assertThat(email.getHeaders().get("X-Unrelated")).containsExactly("kept");
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

	private enum ImportRoute {
		STATIC_EML, FLUENT_EML_EAGER, FLUENT_EML_LAZY, STATIC_MIME_EAGER, STATIC_MIME_LAZY, FLUENT_MIME_EAGER, FLUENT_MIME_LAZY
	}
}
