package org.simplejavamail.converter;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
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
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser.ParsedMimeMessageComponents;
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

class MalformedDispositionImportTest {

	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
	private static final byte[] PAYLOAD = {0, 1, 2, 3};
	private static final String BROKEN_ATTACHMENT = "attachment; filename=\"unterminated";

	@ParameterizedTest
	@EnumSource(ImportRoute.class)
	void recoversIndependentNamingAndKeepsReadableNeighbors(final ImportRoute route) throws Exception {
		final Email imported = convert(route, message(BROKEN_ATTACHMENT,
				"application/octet-stream; name=report.bin", "AQIDAA==", null));
		assertThat(imported.getSubject()).isEqualTo("Readable message");
		assertThat(imported.getPlainText()).isEqualTo("Readable body");
		assertThat(imported.getAttachments()).hasSize(1);
		final AttachmentResource attachment = imported.getAttachments().get(0);
		assertThat(attachment.getName()).isEqualTo("report.bin");
		assertThat(attachment.getDescription()).isEqualTo("Useful attachment");
		assertThat(attachment.getContentId()).isEqualTo("report@example.org");
		assertThat(attachment.getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
		assertThat(attachment.readAllBytes()).containsExactly((byte) 1, (byte) 2, (byte) 3, (byte) 0);
		final Email rendered = mail.converter().fromEml(mail.converter().fromEmail(imported).toEmlBytes()).toEmail();
		assertThat(rendered.getAttachments().get(0).readAllBytes()).isEqualTo(attachment.readAllBytes());
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void usesTheEstablishedMissingNameFallback(final boolean eager) {
		final String source = message(BROKEN_ATTACHMENT, "application/octet-stream", "AAECAw==", null)
				.replace("Content-ID: <report@example.org>\r\n", "");
		final Email imported = mail.converter().fromEml(source).withFetchAttachmentData(eager).toEmail();
		assertThat(imported.getAttachments().get(0).getName()).isEqualTo("UnknownAttachment");
		assertThat(imported.getAttachments().get(0).readAllBytes()).isEqualTo(PAYLOAD);
	}

	@Test
	void usesJakartaMailParameterDecodingForTheIndependentName() {
		final Email imported = mail.converter().fromEml(message(BROKEN_ATTACHMENT,
				"application/octet-stream; name*=UTF-8''r%C3%A9sum%C3%A9.bin", "AAECAw==", null)).toEmail();
		assertThat(imported.getAttachments().get(0).getName()).isEqualTo("r\u00e9sum\u00e9.bin");
		assertThat(imported.getAttachments().get(0).readAllBytes()).isEqualTo(PAYLOAD);
	}

	@Test
	void keepsTheRecognizedInlineRoleForAReferencedImage() {
		final Email imported = mail.converter().fromEml(message("(image) InLiNe; filename=\"unterminated",
				"image/png; name=logo.png", "AAECAw==", "<img src=\"cid:report@example.org\">")).toEmail();
		assertThat(imported.getEmbeddedImages()).hasSize(1);
		assertThat(imported.getEmbeddedImages().get(0).getName()).isEqualTo("logo.png");
		assertThat(imported.getEmbeddedImages().get(0).readAllBytes()).isEqualTo(PAYLOAD);
		assertThat(imported.getAttachments()).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void keepsAnExplicitAttachedMultipartBoundary(final boolean eager) {
		final String inner = "--inner\r\nContent-Type: text/plain\r\n\r\nAttached text\r\n--inner--\r\n";
		final String source = message(BROKEN_ATTACHMENT, "multipart/mixed; boundary=inner; name=bundle.mime", inner, null)
				.replace("Content-Transfer-Encoding: base64", "Content-Transfer-Encoding: 7bit");
		final Email imported = mail.converter().fromEml(source).withFetchAttachmentData(eager).toEmail();
		assertThat(imported.getPlainText()).isEqualTo("Readable body");
		assertThat(imported.getAttachments()).hasSize(1);
		assertThat(imported.getAttachments().get(0).getName()).isEqualTo("bundle.mime");
		assertThat(new String(imported.getAttachments().get(0).readAllBytes(), UTF_8)).isEqualTo(inner);
	}

	@ParameterizedTest
	@ValueSource(strings = {"attachment inline; filename=\"broken", "attachment, inline; filename=\"broken", "unknown; filename=\"broken"})
	void doesNotGuessACompetingOrUnknownDisposition(final String disposition) {
		assertThatThrownBy(() -> mail.converter().fromEml(message(disposition,
				"application/octet-stream; name=report.bin", "AAECAw==", null)).toEmail())
				.isInstanceOf(MailException.class);
	}

	@Test
	void doesNotRecoverCompetingDispositionHeaderOccurrences() {
		final String source = message(BROKEN_ATTACHMENT + "\r\nContent-Disposition: inline",
				"application/octet-stream; name=report.bin", "AAECAw==", null);
		assertThatThrownBy(() -> mail.converter().fromEml(source).toEmail()).isInstanceOf(MailException.class);
	}

	@Test
	void leavesTheSuppliedMimeMessageAndExactSourceUntouched() throws Exception {
		final byte[] source = message(BROKEN_ATTACHMENT, "application/octet-stream; name=report.bin",
				"AAECAw==", null).getBytes(UTF_8);
		final MimeMessage mime = mime(source);
		final byte[] before = EmailConverter.mimeMessageToEMLByteArray(mime);
		assertThat(mail.converter().fromMimeMessage(mime).toEmail().getAttachments().get(0).readAllBytes()).isEqualTo(PAYLOAD);
		assertThat(EmailConverter.mimeMessageToEMLByteArray(mime)).isEqualTo(before);
		final Email exact = mail.emailBuilder().startingFromExactEml(source)
				.withEnvelopeRecipients("reader@example.org").buildEmail();
		assertThat(mail.converter().fromEmail(exact).toEmlBytes()).isEqualTo(source);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void retainsParserReadTimingAndPropagatesRealContentFailures(final boolean eager) throws Exception {
		final AtomicInteger opened = new AtomicInteger();
		final AtomicInteger closed = new AtomicInteger();
		final MimeBodyPart attachment = new MimeBodyPart();
		attachment.setDataHandler(new DataHandler(new DataSource() {
			@Override public InputStream getInputStream() {
				opened.incrementAndGet();
				return new InputStream() {
					@Override public int read() throws IOException { throw new IOException("Attachment read failed"); }
					@Override public void close() { closed.incrementAndGet(); }
				};
			}
			@Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
			@Override public String getContentType() { return "application/octet-stream"; }
			@Override public String getName() { return ""; }
		}));
		attachment.setHeader("Content-Type", "application/octet-stream; name=report.bin");
		attachment.setHeader("Content-Disposition", BROKEN_ATTACHMENT);
		final MimeMultipart parts = new MimeMultipart();
		parts.addBodyPart(attachment);
		final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()));
		source.setContent(parts);
		if (eager) {
			assertThatThrownBy(() -> MimeMessageParser.parseMimeMessage(source, true))
					.isInstanceOf(MailException.class).hasRootCauseMessage("Attachment read failed");
			assertThat(closed).hasValue(1);
		} else {
			final ParsedMimeMessageComponents imported = MimeMessageParser.parseMimeMessage(source, false);
			assertThat(opened).hasValue(0);
			assertThatThrownBy(() -> {
				try (InputStream content = imported.getAttachmentList().iterator().next().getDataSource().getInputStream()) {
					content.readAllBytes();
				}
			}).isInstanceOf(IOException.class).hasMessage("Attachment read failed");
			assertThat(closed).hasValue(1);
		}
		assertThat(opened).hasValue(1);
	}

	private Email convert(final ImportRoute route, final String source) throws Exception {
		switch (route) {
			case STATIC_EML: return EmailConverter.emlToEmail(source);
			case FLUENT_EML_EAGER: return mail.converter().fromEml(source).withFetchAttachmentData(true).toEmail();
			case FLUENT_EML_LAZY: return mail.converter().fromEml(source).withFetchAttachmentData(false).toEmail();
			default:
				final MimeMessage mime = mime(source.getBytes(UTF_8));
				final boolean eager = route == ImportRoute.STATIC_MIME_EAGER || route == ImportRoute.FLUENT_MIME_EAGER;
				return route == ImportRoute.STATIC_MIME_EAGER || route == ImportRoute.STATIC_MIME_LAZY
						? EmailConverter.mimeMessageToEmail(mime, null, eager)
						: mail.converter().fromMimeMessage(mime).withFetchAttachmentData(eager).toEmail();
		}
	}

	private static MimeMessage mime(final byte[] source) throws Exception {
		return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(source));
	}

	private static String message(final String disposition, final String contentType, final String payload, final String html) {
		return "From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Readable message\r\nMIME-Version: 1.0\r\n"
				+ "Content-Type: multipart/mixed; boundary=outer\r\n\r\n"
				+ "--outer\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nReadable body\r\n"
				+ (html == null ? "" : "--outer\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html + "\r\n")
				+ "--outer\r\nContent-Type: " + contentType + "\r\nContent-Disposition: " + disposition + "\r\n"
				+ "Content-ID: <report@example.org>\r\nContent-Description: Useful attachment\r\n"
				+ "Content-Transfer-Encoding: base64\r\n\r\n" + payload + "\r\n--outer--\r\n";
	}

	private enum ImportRoute {
		STATIC_EML, FLUENT_EML_EAGER, FLUENT_EML_LAZY,
		STATIC_MIME_EAGER, STATIC_MIME_LAZY, FLUENT_MIME_EAGER, FLUENT_MIME_LAZY
	}
}
