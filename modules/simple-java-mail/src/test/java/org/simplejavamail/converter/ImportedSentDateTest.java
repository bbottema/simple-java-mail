package org.simplejavamail.converter;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Properties;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportedSentDateTest {

	private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
	@ParameterizedTest
	@ValueSource(strings = {"31 Feb 2026 10:20:30 +0000", "29 Feb 2025 10:20:30 +0000", "00 Oct 2026 10:20:30 +0000",
			"16 Oct 2026 24:20:30 +0000", "16 Oct 2026 10:60:30 +0000", "16 Oct 2026 10:20:61 +0000",
			"16 Oct 2026 10:20:30 XYZ", "16 Oct 2026 10:20:30", "16 Oct 2026 10:20:30 +020",
			"16 Oct 2026 10:20:30 +1260", "16 Oct 2026 10:20:30 +2400", "16 Oct 2026 10:20:30 GMTXYZ",
			"29 Feb 1900 10:20:30 +0000", "16 Oct 2026 10:20:30 +0000 (unfinished", ""})
	void doesNotInventAUsableTimestamp(final String supplied) {
		assertThat(MAIL.converter().fromEml(source(supplied)).toEmail().getSentDate()).as(supplied).isNull();
	}

	@ParameterizedTest
	@MethodSource("legacyDates")
	void retainsUnambiguousLegacySyntax(final String supplied) {
		assertThat(MAIL.converter().fromEml(source(supplied)).toEmail().getSentDateAsInstant())
				.as(supplied).isEqualTo(Instant.parse("2026-10-16T10:20:30Z"));
	}

	private static Stream<String> legacyDates() {
		return Stream.of("Fri, 16 Oct 2026 10:20:30 +0000", "16 Oct 2026 12:20:30 +0200", "16 Oct 2026 03:20:30 -0700",
				"16 Oct 26 10:20:30 -0000", "16-oct-2026 10:20:30 GMT", "16 OCT 2026 10:20:30 UT",
				"16 Oct 2026 06:20:30 EDT", "16 Oct 2026 05:20:30 EST", "16 Oct 2026 05:20:30 CDT",
				"16 Oct 2026 04:20:30 CST", "16 Oct 2026 04:20:30 MDT", "16 Oct 2026 03:20:30 MST",
				"16 Oct 2026 03:20:30 PDT", "16 Oct 2026 02:20:30 PST", "16 Oct 2026 10:20:30 gmt",
				"16 Oct 2026 10:20:30 UTC", "16 Oct 2026 10:20:30 Z",
				"Thu, 16 Oct 2026 10:20:30 +0000", "16 Oct 2026 10:20:30 +0000 (UTC (archive))");
	}

	@ParameterizedTest
	@EnumSource(ImportRoute.class)
	void allImportRoutesRetainReadableNeighborsWithoutRollingTheDate(final ImportRoute route) throws Exception {
		final Email email = convert(route, source("31 Feb 2026 10:20:30 +0000"));
		assertThat(email.getSentDate()).isNull();
		assertThat(email.getPlainText()).isEqualTo("Readable body");
		assertThat(email.getSubject()).isEqualTo("Archive");
		assertThat(email.getHeaders().get("X-Neighbor")).containsExactly("unchanged");
		assertThat(email.getFromRecipient().getAddress()).isEqualTo("sender@example.org");
		assertThat(email.getRecipients()).extracting("address").containsExactly("reader@example.org");
		assertThat(email.getAttachments().get(0).readAllBytes()).isEqualTo("payload".getBytes(UTF_8));
		assertThat(MAIL.emailBuilder().copying(email).buildEmail().getSentDate()).isNull();
	}

	@Test
	void exactInspectionAndBorrowedMimeInputKeepTheirSource() throws Exception {
		final byte[] original = source("16 Oct 2026 10:20:30 XYZ").getBytes(UTF_8);
		final Email exact = MAIL.emailBuilder().startingFromExactEml(original).withEnvelopeRecipients("reader@example.org").buildEmail();
		assertThat(exact.getSentDate()).isNull();
		assertThat(MAIL.converter().fromEmail(exact).toEmlBytes()).isEqualTo(original);
		final MimeMessage message = message(source("31 Feb 2026 10:20:30 +0000"));
		assertThat(MAIL.converter().fromMimeMessage(message).toEmail().getSentDate()).isNull();
		assertThat(message.getHeader("Date", null)).isEqualTo("31 Feb 2026 10:20:30 +0000");
	}

	@Test
	void missingDateAndFirstHeaderSelectionKeepTheirExistingMeaning() {
		assertThat(MAIL.converter().fromEml(source(null)).toEmail().getSentDate()).isNull();
		final String duplicate = source("16 Oct 2026 10:20:30 +0000").replace("Subject: Archive", "Date: 31 Feb 2026 10:20:30 +0000\r\nSubject: Archive");
		assertThat(MAIL.converter().fromEml(duplicate).toEmail().getSentDateAsInstant()).isEqualTo(Instant.parse("2026-10-16T10:20:30Z"));
	}

	@Test
	void optionalSecondsFoldingAndLeapDayRemainInterpretable() {
		assertThat(MAIL.converter().fromEml(source("29 Feb 2024\r\n\t10:20 +0000")).toEmail().getSentDateAsInstant())
				.isEqualTo(Instant.parse("2024-02-29T10:20:00Z"));
	}

	@Test
	void ordinaryCompositionStillGeneratesAnAbsentSentDate() throws Exception {
		final Email imported = MAIL.converter().fromEml(source("31 Feb 2026 10:20:30 +0000")).toEmail();
		final Instant before = Instant.now().minusSeconds(1);
		assertThat(MAIL.converter().fromEmail(imported).toMimeMessage().getSentDate().toInstant()).isBetween(before, Instant.now());
	}

	@Test
	void anUnreadableDateHeaderRemainsAnImportFailure() throws Exception {
		final MessagingException failure = new MessagingException("unreadable source header");
		final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()),
				new ByteArrayInputStream(source(null).getBytes(UTF_8))) {
			@Override
			public String getHeader(final String name, final String delimiter) throws MessagingException {
				if ("Date".equalsIgnoreCase(name)) {
					throw failure;
				}
				return super.getHeader(name, delimiter);
			}
		};
		assertThatThrownBy(() -> MAIL.converter().fromMimeMessage(source).toEmail())
				.isInstanceOf(MailException.class).hasCause(failure);
	}

	private static Email convert(final ImportRoute route, final String source) throws Exception {
		switch (route) {
			case STATIC_EML: return EmailConverter.emlToEmail(source);
			case STATIC_EML_BUILDER: return EmailConverter.emlToEmailBuilder(source).buildEmail();
			case FLUENT_EML: return MAIL.converter().fromEml(source).toEmail();
			case FLUENT_EML_BUILDER: return MAIL.converter().fromEml(source).toEmailBuilder().buildEmail();
			case STATIC_MIME: return EmailConverter.mimeMessageToEmail(message(source));
			case STATIC_MIME_BUILDER: return EmailConverter.mimeMessageToEmailBuilder(message(source)).buildEmail();
			case FLUENT_MIME_LAZY: return MAIL.converter().fromMimeMessage(message(source)).withFetchAttachmentData(false).toEmail();
			case FLUENT_MIME_BUILDER: return MAIL.converter().fromMimeMessage(message(source)).toEmailBuilder().buildEmail();
			default: throw new IllegalArgumentException("Unknown import route: " + route);
		}
	}

	private static MimeMessage message(final String source) throws Exception {
		return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(source.getBytes(UTF_8)));
	}

	private static String source(final String date) {
		return "From: sender@example.org\r\nTo: reader@example.org\r\n" + (date != null ? "Date: " + date + "\r\n" : "")
				+ "Subject: Archive\r\nX-Neighbor: unchanged\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=parts\r\n\r\n"
				+ "--parts\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nReadable body\r\n"
				+ "--parts\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=payload.bin\r\n"
				+ "Content-Transfer-Encoding: base64\r\n\r\ncGF5bG9hZA==\r\n--parts--\r\n";
	}

	private enum ImportRoute {
		STATIC_EML, STATIC_EML_BUILDER, FLUENT_EML, FLUENT_EML_BUILDER, STATIC_MIME, STATIC_MIME_BUILDER, FLUENT_MIME_LAZY, FLUENT_MIME_BUILDER
	}
}
