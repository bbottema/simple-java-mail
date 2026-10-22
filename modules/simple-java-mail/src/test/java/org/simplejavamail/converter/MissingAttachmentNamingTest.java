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
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser.ParsedMimeMessageComponents;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class MissingAttachmentNamingTest {

	private static final byte[] UNNAMED_BYTES = {0, 1, 2, 127, (byte) 128, (byte) 255};
	private static final String EML = "From: Sender <sender@example.org>\r\n"
			+ "To: recipient@example.org\r\n"
			+ "Subject: Keep the message\r\n"
			+ "X-Import-Context: retained\r\n"
			+ "MIME-Version: 1.0\r\n"
			+ "Content-Type: multipart/mixed; boundary=parts\r\n\r\n"
			+ "--parts\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nPlain body\r\n"
			+ "--parts\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<p>HTML body</p>\r\n"
			+ "--parts\r\nContent-Disposition: attachment\r\n"
			+ "Content-Transfer-Encoding: base64\r\n\r\nAAECf4D/\r\n"
			+ "--parts\r\nContent-Type: application/octet-stream\r\n"
			+ "Content-Disposition: attachment; filename=known.bin\r\n\r\nNamed attachment\r\n"
			+ "--parts--\r\n";

	@ParameterizedTest
	@EnumSource(ImportRoute.class)
	void absentNamingMetadataRetainsBodiesAndAttachmentBytes(final ImportRoute route) throws Exception {
		final Email imported = convert(route);
		assertThat(imported.getSubject()).isEqualTo("Keep the message");
		assertThat(imported.getFromRecipient().getAddress()).isEqualTo("sender@example.org");
		assertThat(imported.getRecipients()).extracting("address").containsExactly("recipient@example.org");
		assertThat(imported.getHeaders()).containsKey("X-Import-Context");
		assertThat(imported.getPlainText()).isEqualTo("Plain body");
		assertThat(imported.getHTMLText()).isEqualTo("<p>HTML body</p>");
		assertThat(imported.getAttachments()).extracting("name").containsExactly("UnknownAttachment", "known.bin");
		assertThat(imported.getAttachments().get(0).readAllBytes()).isEqualTo(UNNAMED_BYTES);
		assertThat(imported.getAttachments().get(1).readAllBytes()).isEqualTo("Named attachment".getBytes(UTF_8));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void fallbackKeepsTheParserAttachmentReadBoundary(final boolean fetchAttachmentData) throws Exception {
		final AtomicInteger reads = new AtomicInteger();
		final MimeBodyPart attachment = new MimeBodyPart();
		attachment.setDisposition(Part.ATTACHMENT);
		attachment.setDataHandler(new DataHandler(new DataSource() {
			@Override public InputStream getInputStream() {
				reads.incrementAndGet();
				return new ByteArrayInputStream(UNNAMED_BYTES);
			}
			@Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
			@Override public String getContentType() { return "application/octet-stream"; }
			@Override public String getName() { return ""; }
		}));
		final MimeMultipart parts = new MimeMultipart();
		parts.addBodyPart(attachment);
		final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		message.setContent(parts);

		final ParsedMimeMessageComponents parsed = MimeMessageParser.parseMimeMessage(message, fetchAttachmentData);
		assertThat(parsed.getAttachmentList()).extracting("name").containsExactly("UnknownAttachment");
		assertThat(reads.get()).isEqualTo(fetchAttachmentData ? 1 : 0);
		try (InputStream content = parsed.getAttachmentList().iterator().next().getDataSource().getInputStream()) {
			assertThat(content.readAllBytes()).isEqualTo(UNNAMED_BYTES);
		}
		assertThat(reads.get()).isEqualTo(1);
	}

	@Test
	void explicitFilenameStillWinsWithoutAContentType() throws Exception {
		final MimeBodyPart part = new MimeBodyPart();
		part.setFileName("known.bin");
		assertThat(part.getHeader("Content-Type")).isNull();
		assertThat(MimeMessageParser.parseFileName(part)).isEqualTo("known.bin");
	}

	@Test
	void exactForwardedMessageTypeKeepsItsEstablishedName() throws Exception {
		final MimeBodyPart part = new MimeBodyPart();
		part.setHeader("Content-Type", "message/rfc822");
		assertThat(MimeMessageParser.parseFileName(part)).isEqualTo("ForwardedMessage.eml");
	}

	@ParameterizedTest
	@ValueSource(strings = {"application/octet-stream", "MESSAGE/RFC822", "message/rfc822; charset=UTF-8"})
	void otherContentTypeHeadersKeepTheExistingFallback(final String contentType) throws Exception {
		final MimeBodyPart part = new MimeBodyPart();
		part.setHeader("Content-Type", contentType);
		assertThat(MimeMessageParser.parseFileName(part)).isEqualTo("UnknownAttachment");
	}

	private static Email convert(final ImportRoute route) throws Exception {
		final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
		switch (route) {
			case STATIC_EML:
				return EmailConverter.emlToEmail(EML);
			case FLUENT_EML_EAGER:
				return mail.converter().fromEml(EML).withFetchAttachmentData(true).toEmail();
			case FLUENT_EML_LAZY:
				return mail.converter().fromEml(EML).withFetchAttachmentData(false).toEmail();
			default:
				final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(EML.getBytes(UTF_8)));
				final boolean eager = route == ImportRoute.STATIC_MIME_EAGER || route == ImportRoute.FLUENT_MIME_EAGER;
				if (route == ImportRoute.STATIC_MIME_EAGER || route == ImportRoute.STATIC_MIME_LAZY) {
					return EmailConverter.mimeMessageToEmail(message, null, eager);
				}
				return mail.converter().fromMimeMessage(message).withFetchAttachmentData(eager).toEmail();
		}
	}

	private enum ImportRoute {
		STATIC_EML, FLUENT_EML_EAGER, FLUENT_EML_LAZY,
		STATIC_MIME_EAGER, STATIC_MIME_LAZY, FLUENT_MIME_EAGER, FLUENT_MIME_LAZY
	}
}
