package org.simplejavamail.converter;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MimeTransferEncodingCommentTest {

	private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

	@Test
	void acceptsTheDocumentedImportWithoutChangingCallerCode() {
		String eml = String.join("\r\n",
				"From: sender@example.org",
				"To: reader@example.net",
				"MIME-Version: 1.0",
				"Content-Type: text/plain; charset=UTF-8",
				"Content-Transfer-Encoding: base64 (encoded for transport)",
				"",
				"SGVsbG8h");

		Email email = SimpleJavaMail.fromDefaults().converter().fromEml(eml).toEmail();

		assertThat(email.getPlainText()).isEqualTo("Hello!");
		assertThat(email.getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
	}

	@ParameterizedTest
	@EnumSource(ImportRoute.class)
	void interpretsCommentsAcrossTheExistingImportRoutes(final ImportRoute route) throws Exception {
		final String eml = body("base64 (encoded for transport)", "SGVsbG8h");
		final Email email;
		switch (route) {
			case STATIC_EML: email = EmailConverter.emlToEmail(eml); break;
			case FLUENT_EML: email = MAIL.converter().fromEml(eml).toEmail(); break;
			default:
				final MimeMessage message = mimeMessage(eml);
				email = route == ImportRoute.STATIC_MIME ? EmailConverter.mimeMessageToEmail(message)
						: MAIL.converter().fromMimeMessage(message).toEmail();
		}
		assertThat(email.getPlainText()).isEqualTo("Hello!");
		assertThat(email.getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"base64 (encoded for transport)", "(transport) base64", "(before (nested))\tBaSe64\t(after)",
			"base64 (escaped \\(parentheses\\))", "base64\r\n\t(folded\r\n\tcomment)",
			"(quoted-printable and 7bit) base64 (binary)"
	})
	void usesJakartaMailsCommentGrammar(final String encoding) {
		final Email email = MAIL.converter().fromEml(body(encoding, "SGVsbG8h")).toEmail();
		assertThat(email.getPlainText()).isEqualTo("Hello!");
		assertThat(email.getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void readsBodyAndAttachmentEncodingCommentsWithEquivalentEagerAndLazyContent(final boolean eager) {
		final String source = "From: sender@example.org\r\nTo: reader@example.net\r\nMIME-Version: 1.0\r\n"
				+ "Content-Type: multipart/mixed; boundary=parts\r\n\r\n"
				+ "--parts\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64 (body)\r\n\r\nSGVsbG8h\r\n"
				+ "--parts\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: quoted-printable (HTML)\r\n\r\n=3Cb=3EHello!=3C/b=3E\r\n"
				+ "--parts\r\nContent-Type: text/calendar; method=REQUEST\r\nContent-Transfer-Encoding: 7bit (calendar)\r\n\r\nBEGIN:VCALENDAR\r\nMETHOD:REQUEST\r\nEND:VCALENDAR\r\n"
				+ "--parts\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=payload.bin\r\n"
				+ "Content-Transfer-Encoding: (resource) base64 (bytes)\r\n\r\nAAECf4D/\r\n--parts--\r\n";
		final Email email = MAIL.converter().fromEml(source).withFetchAttachmentData(eager).toEmail();

		assertThat(email.getPlainText()).isEqualTo("Hello!");
		assertThat(email.getHTMLText()).isEqualTo("<b>Hello!</b>");
		assertThat(email.getHTMLTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.QUOTED_PRINTABLE);
		assertThat(email.getCalendarTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BIT7);
		assertThat(email.getCalendarText()).isEqualTo("BEGIN:VCALENDAR\r\nMETHOD:REQUEST\r\nEND:VCALENDAR");
		assertThat(email.getAttachments()).hasSize(1);
		assertThat(email.getAttachments().get(0).getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
		assertThat(email.getAttachments().get(0).readAllBytes()).containsExactly(new byte[] {0, 1, 2, 127, (byte) 128, (byte) 255});
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void referencedInlineContentKeepsItsDecodedBytes(final boolean eager) {
		final String source = "From: sender@example.org\r\nTo: reader@example.net\r\nMIME-Version: 1.0\r\n"
				+ "Content-Type: multipart/related; boundary=parts\r\n\r\n"
				+ "--parts\r\nContent-Type: text/html\r\n\r\n<img src=\"cid:logo\">\r\n"
				+ "--parts\r\nContent-Type: image/png\r\nContent-Disposition: inline; filename=logo.png\r\nContent-ID: <logo>\r\n"
				+ "Content-Transfer-Encoding: base64 (inline bytes)\r\n\r\nAAECf4D/\r\n--parts--\r\n";
		final Email email = MAIL.converter().fromEml(source).withFetchAttachmentData(eager).toEmail();
		assertThat(email.getHTMLText()).isEqualTo("<img src=\"cid:logo\">");
		assertThat(email.getEmbeddedImages()).hasSize(1);
		assertThat(email.getEmbeddedImages().get(0).readAllBytes()).containsExactly(new byte[] {0, 1, 2, 127, (byte) 128, (byte) 255});
		assertThat(email.getAttachments()).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(ContentTransferEncoding.class)
	void preservesEveryUndecoratedSupportedEncodingAndItsCommentedForm(final ContentTransferEncoding encoding) throws Exception {
		final MimeBodyPart part = new MimeBodyPart();
		part.setHeader("Content-Transfer-Encoding", encoding.getEncoder());
		assertThat(ContentTransferEncoding.byEncoder(MimeMessageParser.parseContentTransferEncoding(part))).isEqualTo(encoding);
		part.setHeader("Content-Transfer-Encoding", encoding.getEncoder() + " (transport)");
		assertThat(ContentTransferEncoding.byEncoder(MimeMessageParser.parseContentTransferEncoding(part))).isEqualTo(encoding);
	}

	@ParameterizedTest
	@ValueSource(strings = {"7bit 8bit", "7bit, base64", "7bit; base64", "(comment only)", "\"7bit\"", "7bit (unfinished"})
	void doesNotTurnAmbiguousOrMalformedDeclarationsIntoAKnownEncoding(final String encoding) {
		assertThatThrownBy(() -> MAIL.converter().fromEml(body(encoding, "Hello!")).toEmail())
				.isInstanceOf(RuntimeException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"7bit\r\nContent-Transfer-Encoding: base64", "7bit (first)\r\nContent-Transfer-Encoding: 8bit (second)",
			"7bit\r\nContent-Transfer-Encoding: 7bit"})
	void doesNotChooseAmongRepeatedEncodingDeclarations(final String encoding) {
		assertThatThrownBy(() -> MAIL.converter().fromEml(body(encoding, "Hello!")).toEmail())
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown content transfer encoder");
	}

	@Test
	void unknownEncodingStillFailsInsteadOfBecomingAnUnencodedBody() {
		assertThatThrownBy(() -> MAIL.converter().fromEml(body("x-unknown (transport)", "Hello!")).toEmail())
				.isInstanceOf(MailException.class).hasRootCauseInstanceOf(IOException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"x-unknown", "x-unknown (transport)"})
	void unknownResourceEncodingsStillFailForBothExtractionModes(final String encoding) {
		final String source = "From: sender@example.org\r\nTo: reader@example.net\r\nMIME-Version: 1.0\r\n"
				+ "Content-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=payload.bin\r\n"
				+ "Content-Transfer-Encoding: " + encoding + "\r\n\r\nHello!";
		for (final boolean eager : new boolean[] {false, true}) {
			assertThatThrownBy(() -> MAIL.converter().fromEml(source).withFetchAttachmentData(eager).toEmail())
					.isInstanceOf(RuntimeException.class);
		}
	}

	@Test
	void standaloneEnumParsingRemainsStrictAndAMissingHeaderStaysAbsent() throws Exception {
		assertThatThrownBy(() -> ContentTransferEncoding.byEncoder("base64 (transport)"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown content transfer encoder");
		assertThat(MimeMessageParser.parseContentTransferEncoding(new MimeBodyPart())).isNull();
	}

	@Test
	void requiredResourceReadFailuresKeepTheirOriginalCauseWithAValidEncodingComment() throws Exception {
		final IOException unreadable = new IOException("unreadable attachment");
		final MimeBodyPart resource = new MimeBodyPart();
		resource.setDataHandler(new DataHandler(new DataSource() {
			@Override public InputStream getInputStream() throws IOException { throw unreadable; }
			@Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
			@Override public String getContentType() { return "application/octet-stream"; }
			@Override public String getName() { return "payload.bin"; }
		}));
		resource.setHeader("Content-Type", "application/octet-stream");
		resource.setHeader("Content-Transfer-Encoding", "7bit (transport)");
		resource.setDisposition(Part.ATTACHMENT);
		assertThatThrownBy(() -> MimeMessageParser.parseMimeMessage(container(resource), true))
				.isInstanceOf(MailException.class).hasCause(unreadable);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void interpretingResourceMetadataDoesNotReadBorrowedContent(final boolean eager) throws Exception {
		final AtomicInteger opens = new AtomicInteger();
		final AtomicInteger closes = new AtomicInteger();
		final MimeBodyPart resource = new MimeBodyPart();
		resource.setDataHandler(new DataHandler(new DataSource() {
			@Override public InputStream getInputStream() {
				opens.incrementAndGet();
				return new ByteArrayInputStream("content".getBytes(UTF_8)) {
					@Override public void close() { closes.incrementAndGet(); }
				};
			}
			@Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
			@Override public String getContentType() { return "application/octet-stream"; }
			@Override public String getName() { return "payload.bin"; }
		}));
		resource.setDisposition(Part.ATTACHMENT);
		resource.setHeader("Content-Transfer-Encoding", "7bit (transport)");
		final MimeMessageParser.ParsedMimeMessageComponents parsed = MimeMessageParser.parseMimeMessage(container(resource), eager);
		assertThat(parsed.getAttachmentList()).hasSize(1);
		assertThat(parsed.getAttachmentList().iterator().next().getContentTransferEncoding()).isEqualTo("7bit");
		assertThat(opens).hasValue(eager ? 1 : 0);
		assertThat(closes).hasValue(eager ? 1 : 0);
	}

	@Test
	void exactEmlRetainsTheOriginalCommentAndBytes() {
		final byte[] original = body("base64 (encoded for transport)", "SGVsbG8h\r\n").getBytes(UTF_8);
		final Email exact = MAIL.emailBuilder().startingFromExactEml(original).withEnvelopeRecipients("reader@example.net").buildEmail();
		assertThat(exact.getPlainText()).isEqualTo("Hello!");
		assertThat(exact.getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
		assertThat(EmailConverter.emailToEMLByteArray(exact)).isEqualTo(original);
	}

	@Test
	void protectedEmlKeepsTheOriginalCommentAndSourceBytes() {
		final byte[] original = ("From: sender@example.org\r\nTo: reader@example.net\r\nMIME-Version: 1.0\r\n"
				+ "Content-Type: multipart/signed; boundary=signature; protocol=\"application/pgp-signature\"\r\n\r\n"
				+ "--signature\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64 (signed content)\r\n\r\nSGVsbG8h\r\n"
				+ "--signature\r\nContent-Type: application/pgp-signature\r\n\r\noriginal signature bytes\r\n--signature--\r\n").getBytes(UTF_8);
		final Email email = MAIL.converter().fromEml(original).toEmail();
		assertThat(email.getPlainText()).isEqualTo("Hello!");
		assertThat(email.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).isEqualTo(original);
	}

	private static String body(final String encoding, final String content) {
		return "From: sender@example.org\r\nTo: reader@example.net\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n"
				+ "Content-Transfer-Encoding: " + encoding + "\r\n\r\n" + content;
	}

	private static MimeMessage mimeMessage(final String source) throws Exception {
		return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(source.getBytes(UTF_8)));
	}

	private static MimeMessage container(final MimeBodyPart resource) throws Exception {
		final MimeMultipart parts = new MimeMultipart();
		parts.addBodyPart(resource);
		final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		message.setContent(parts);
		message.setHeader("Content-Type", parts.getContentType());
		return message;
	}

	private enum ImportRoute { STATIC_EML, FLUENT_EML, STATIC_MIME, FLUENT_MIME }
}
