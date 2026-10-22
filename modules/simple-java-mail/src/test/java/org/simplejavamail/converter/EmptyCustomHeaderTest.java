package org.simplejavamail.converter;

import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.mailer.MailSuspiciousCRLFValueException;
import org.simplejavamail.mailer.MailerHelper;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmptyCustomHeaderTest {

	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" \t ", "ordinary text"})
	void validationAcceptsSafeEmptyAndNonemptyValuesWithoutChangingMetadata(final String value) {
		final Email email = message().withHeader("X-Example", value).buildEmail();

		assertThatNoException().isThrownBy(() -> MailerHelper.validate(email));
		assertThat(email.getHeaders().get("X-Example")).containsExactly(value);
	}

	@ParameterizedTest
	@ValueSource(strings = {"static-mime", "fluent-mime", "static-eml", "fluent-eml"})
	void everyOrdinaryExportProducesAPresentEmptyFieldForNull(final String route) throws Exception {
		final Email email = message().withHeader("X-Example", null).withHeader("X-Empty", "").buildEmail();
		final MimeMessage rendered;
		switch (route) {
			case "static-mime": rendered = EmailConverter.emailToMimeMessage(email); break;
			case "fluent-mime": rendered = mail.converter().fromEmail(email).toMimeMessage(); break;
			case "static-eml": rendered = EmailConverter.emlToMimeMessage(EmailConverter.emailToEML(email)); break;
			case "fluent-eml": rendered = mail.converter().fromEml(mail.converter().fromEmail(email).toEml()).toMimeMessage(); break;
			default: throw new IllegalArgumentException(route);
		}

		assertThat(rendered.getHeader("X-Example")).containsExactly("");
		assertThat(rendered.getHeader("X-Empty")).containsExactly("");
		assertThat(EmailConverter.mimeMessageToEML(rendered)).contains("\r\nX-Example: \r\n", "\r\nX-Empty: \r\n");
		assertThat(email.getHeaders().get("X-Example")).containsExactly((String) null);
	}

	@Test
	void mixedValuesKeepTheirExistingIdentityOrderAndDistinctStoredChoices() throws Exception {
		final Email email = message().withHeader("X-Mixed", null).withHeader("x-mixed", "")
				.withHeader("X-MIXED", "first").withHeader("X-Mixed", null).withHeader("X-Mixed", "")
				.withHeader("X-Mixed", " \t ").withHeader("X-Mixed", "last").buildEmail();

		assertThat(email.getHeaders()).containsOnlyKeys("X-Mixed");
		assertThat(email.getHeaders().get("X-Mixed")).containsExactly(null, "", "first", " \t ", "last");
		final String[] rendered = mail.converter().fromEmail(email).toMimeMessage().getHeader("x-mixed");
		final String whitespaceOnly = mail.converter().fromEmail(message().withHeader("X-Mixed", " \t ").buildEmail())
				.toMimeMessage().getHeader("X-Mixed")[0];
		assertThat(rendered).containsExactly("", "", "first", whitespaceOnly, "last");
	}

	@Test
	void replacingWithNullRemovesEarlierValuesAndLeavesAnEmptyField() throws Exception {
		final Email email = message().withHeader("X-Example", "old").withHeader("x-example", null, true).buildEmail();

		assertThat(email.getHeaders()).containsOnlyKeys("X-Example");
		assertThat(email.getHeaders().get("X-Example")).containsExactly((String) null);
		assertThat(mail.converter().fromEmail(email).toMimeMessage().getHeader("X-Example")).containsExactly("");
	}

	@Test
	void copiedAndSerializedNullMetadataStillProducesAnEmptyField() throws Exception {
		final Email original = message().withHeader("X-Example", null).buildEmail();
		final Email restored = roundTrip(original);
		final Email copied = mail.emailBuilder().copying(restored).buildEmail();

		for (Email email : new Email[] {original, restored, copied}) {
			assertThat(email.getHeaders().get("X-Example")).containsExactly((String) null);
			assertThat(mail.converter().fromEmail(email).toMimeMessage().getHeader("X-Example")).containsExactly("");
		}
	}

	@Test
	void importedEmptyCorrelatorSurvivesCopyingSerializationAndExport() throws Exception {
		final String source = "From: sender@example.org\r\nTo: reader@example.org\r\n"
				+ "X-MS-TNEF-Correlator:\r\nSubject: Empty source header\r\n\r\nbody\r\n";
		final Email imported = mail.converter().fromEml(source).toEmail();
		final Email copied = mail.emailBuilder().copying(roundTrip(imported)).buildEmail();

		assertThat(imported.getHeaders().get("X-MS-TNEF-Correlator")).containsExactly("");
		assertThat(copied.getHeaders().get("X-MS-TNEF-Correlator")).containsExactly("");
		assertThatNoException().isThrownBy(() -> MailerHelper.validate(copied));
		assertThat(mail.converter().fromEmail(copied).toMimeMessage().getHeader("X-MS-TNEF-Correlator")).containsExactly("");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void rehearsalProducesEmptyFieldsAndRetainsEffectiveNullMetadata(final boolean full) throws Exception {
		final Email email = message().withHeader("X-Example", null).withHeader("X-Empty", "").buildEmail();
		try (Mailer mailer = mail.mailerBuilder().withSMTPServer("unused.example.org", 25).buildMailer()) {
			final org.simplejavamail.api.mailer.MailRehearsal rehearsal = mailer.rehearse(email, full);
			final MimeMessage rendered = EmailConverter.emlToMimeMessage(new ByteArrayInputStream(rehearsal.getEmlBytes()));
			assertThat(rendered.getHeader("X-Example")).containsExactly("");
			assertThat(rendered.getHeader("X-Empty")).containsExactly("");
			assertThat(rehearsal.getEffectiveEmail().getHeaders().get("X-Example")).containsExactly((String) null);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"X-Unsafe\rInjected", "X-Unsafe\nInjected", "X-Unsafe%0AInjected"})
	void aNullValueDoesNotBypassUnsafeHeaderNameDetection(final String name) throws Exception {
		final Email email = message().withHeader(name, null).buildEmail();

		assertThatThrownBy(() -> MailerHelper.validate(email)).isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessageContaining("email.header.headerName");
		try (Mailer mailer = mail.mailerBuilder().withSMTPServer("unused.example.org", 25).buildMailer()) {
			assertThatThrownBy(() -> mailer.rehearse(email)).isInstanceOf(MailSuspiciousCRLFValueException.class)
					.hasMessageContaining("email.header.headerName");
		}
	}

	@Test
	void neighboringNonemptyHeadersKeepEncodingFoldingAndInjectionProtection() throws Exception {
		final String unicode = "caf\u00e9 avec cr\u00e8me ".repeat(15);
		final Email email = message().withHeader("X-Empty", null).withHeader("X-Encoded", unicode).buildEmail();
		final String encoded = mail.converter().fromEmail(email).toMimeMessage().getHeader("X-Encoded")[0];

		assertThat(encoded).contains("=?UTF-8?", "\r\n");
		assertThat(MimeUtility.decodeText(MimeUtility.unfold(encoded))).isEqualTo(unicode);
		assertThatThrownBy(() -> MailerHelper.validate(message().withHeader("X-Empty", null)
				.withHeader("X-Unsafe", "value\rInjected").buildEmail())).isInstanceOf(MailSuspiciousCRLFValueException.class);
	}

	@Test
	void exactEmptyHeaderBytesKeepTheirOriginalRepresentation() throws Exception {
		final byte[] source = ("From: sender@example.org\r\nTo: reader@example.org\r\nX-Empty:\r\n\r\nbody\r\n").getBytes(US_ASCII);
		final Email exact = mail.emailBuilder().startingFromExactEml(source).withEnvelopeRecipients("reader@example.org").buildEmail();
		try (Mailer mailer = mail.mailerBuilder().withSMTPServer("unused.example.org", 25).buildMailer()) {
			assertThat(mailer.rehearse(exact).getEmlBytes()).containsExactly(source);
		}
	}

	private EmailPopulatingBuilder message() {
		return mail.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org").withPlainText("body");
	}

	private Email roundTrip(final Email email) throws Exception {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
			output.writeObject(email);
		}
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			return (Email) input.readObject();
		}
	}
}
