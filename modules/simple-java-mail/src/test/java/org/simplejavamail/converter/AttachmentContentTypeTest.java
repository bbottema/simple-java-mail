package org.simplejavamail.converter;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.BodyPart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class AttachmentContentTypeTest {

	private static final byte[] CONTENT = "caf\u00e9".getBytes(ISO_8859_1);
	private static final String SOURCE_TYPE = "text/plain; charset=ISO-8859-1; x-document-mode=review; name=notes.txt";
	private static final String EML = "From: sender@example.org\r\nTo: reader@example.org\r\n"
			+ "Subject: Attachment metadata\r\nMIME-Version: 1.0\r\n"
			+ "Content-Type: multipart/mixed; boundary=parts\r\n\r\n"
			+ "--parts\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nBody\r\n"
			+ "--parts\r\nContent-Type: " + SOURCE_TYPE + "\r\n"
			+ "Content-Disposition: attachment; filename=notes.txt\r\n"
			+ "Content-ID: <notes@example.org>\r\nContent-Description: Original notes\r\n"
			+ "Content-Transfer-Encoding: base64\r\n\r\nY2Fm6Q==\r\n--parts--\r\n";
	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

	@Test
	void exportPreservesCharsetAndExtensionParametersWithoutChangingBytesOrOtherMetadata() throws Exception {
		final AttachmentResource resource = AttachmentResource.builder().withName("notes.txt")
				.withData(CONTENT, "text/plain; charset=ISO-8859-1; x-document-mode=review")
				.withDescription("Original notes").withContentId("notes@example.org")
				.withContentTransferEncoding(ContentTransferEncoding.BASE_64).build();
		final Email email = mail.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
				.withPlainText("Body").withAttachment(resource).buildEmail();
		final BodyPart attachment = attachment(mail.converter().fromEmail(email).toMimeMessage());
		assertType(attachment.getContentType());
		assertThat(attachment.getFileName()).isEqualTo("notes.txt");
		assertThat(attachment.getDescription()).isEqualTo("Original notes");
		assertThat(attachment.getHeader("Content-ID")).containsExactly("<notes@example.org>");
		assertThat(attachment.getHeader("Content-Transfer-Encoding")).containsExactly("base64");
		try (InputStream stream = attachment.getInputStream()) {
			assertThat(stream.readAllBytes()).isEqualTo(CONTENT);
		}
		final String outgoing = mail.converter().fromEmail(email).toEml();
		for (final boolean eager : new boolean[] {false, true}) {
			final AttachmentResource imported = mail.converter().fromEml(outgoing).withFetchAttachmentData(eager)
					.toEmail().getAttachments().get(0);
			assertType(imported.getDataSource().getContentType());
			assertThat(imported.readAllBytes()).isEqualTo(CONTENT);
		}
	}

	@ParameterizedTest
	@EnumSource(ImportRoute.class)
	void importRetainsTheSameTypeRegardlessOfAttachmentReadTiming(final ImportRoute route) throws Exception {
		final Email imported = convert(route);
		final AttachmentResource resource = imported.getAttachments().get(0);
		assertType(resource.getDataSource().getContentType());
		assertThat(resource.getName()).isEqualTo("notes.txt");
		assertThat(resource.getDescription()).isEqualTo("Original notes");
		assertThat(resource.getContentId()).isEqualTo("notes@example.org");
		assertThat(resource.getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
		assertThat(resource.readAllBytes()).isEqualTo(CONTENT);
		assertThat(imported.getPlainText()).isEqualTo("Body");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"text/plain; charset=ISO-8859-1; x-caption=\"a; b\\\"c\\\\d\"",
			"text/plain;\r\n\tcharset=ISO-8859-1;\r\n x-caption=\"a; b\\\"c\\\\d\"",
			"text/\r\n\tplain; charset=ISO-8859-1; x-caption=\"a; b\\\"c\\\\d\""
	})
	void exportPreservesQuotedEscapedAndFoldedParameters(final String sourceType) throws Exception {
		final ContentType type = new ContentType(exportedAttachment(sourceType).getContentType());
		assertThat(type.getParameter("charset")).isEqualTo("ISO-8859-1");
		assertThat(type.getParameter("x-caption")).isEqualTo("a; b\"c\\d");
	}

	@Test
	void exportPreservesEncodedExtensionParameters() throws Exception {
		final ContentType type = new ContentType(exportedAttachment("text/plain; x-title*=utf-8''caf%C3%A9").getContentType());
		assertThat(type.getParameter("x-title")).isEqualTo("caf\u00e9");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"name=untrusted.txt; filename=other.txt",
			"NAME=untrusted.txt; FILENAME=other.txt",
			"name*=utf-8''untrusted.txt; filename*=utf-8''other.txt",
			"name*0*=utf-8''untrusted; name*1*=.txt; filename*0=other; filename*1=.txt",
			"name*3=orphan.txt; filename*3=other.txt"
	})
	void suppliedNamingParametersCannotReplaceTheSelectedFilename(final String names) throws Exception {
		final BodyPart part = exportedAttachment("text/plain; charset=ISO-8859-1; " + names);
		final ContentType type = new ContentType(part.getContentType());
		assertThat(type.getParameter("charset")).isEqualTo("ISO-8859-1");
		assertThat(type.getParameter("name")).isEqualTo("notes.txt");
		assertThat(type.getParameter("filename")).isNull();
		assertThat(part.getFileName()).isEqualTo("notes.txt");
		assertThat(part.getContentType()).doesNotContain("untrusted", "other.txt", "orphan.txt");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"text/plain\r\nX-Bad: yes",
			"text/plain; charset=ISO-8859-1\r\nX-Bad: yes",
			"text/plain; x-caption=\"unfinished"
	})
	void malformedOrInjectedParametersRetainTheUsableBaseType(final String sourceType) throws Exception {
		final BodyPart part = exportedAttachment(sourceType);
		final ContentType type = new ContentType(part.getContentType());
		assertThat(type.getBaseType()).isEqualTo("text/plain");
		assertThat(type.getParameter("name")).isEqualTo("notes.txt");
		assertThat(part.getHeader("X-Bad")).isNull();
		assertThat(part.getContentType()).doesNotContain("X-Bad", "unfinished");
	}

	@Test
	void encodedHeaderLinesAreNotDecodedIntoAnOutgoingHeader() throws Exception {
		final BodyPart part = exportedAttachment("text/plain; charset=ISO-8859-1; x-caption*=utf-8''%0D%0AX-Bad%3Ayes");
		assertThat(part.getHeader("X-Bad")).isNull();
		assertThat(part.getContentType()).doesNotContain("X-Bad");
	}

	@ParameterizedTest
	@ValueSource(strings = {" ", "not-a-type; charset=ISO-8859-1"})
	void unusableBaseTypeKeepsTheExistingBinaryFallback(final String sourceType) throws Exception {
		assertThat(new ContentType(exportedAttachment(sourceType).getContentType()).getBaseType())
				.isEqualTo("application/octet-stream");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void parameterRetentionDoesNotAddAttachmentReads(final boolean eager) throws Exception {
		final AtomicInteger reads = new AtomicInteger();
		final MimeBodyPart part = new MimeBodyPart();
		part.setDisposition(Part.ATTACHMENT);
		part.setDataHandler(new DataHandler(new DataSource() {
			@Override public InputStream getInputStream() { reads.incrementAndGet(); return new ByteArrayInputStream(CONTENT); }
			@Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
			@Override public String getContentType() { return SOURCE_TYPE; }
			@Override public String getName() { return "notes.txt"; }
		}));
		final MimeMultipart parts = new MimeMultipart();
		parts.addBodyPart(part);
		final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		message.setContent(parts);
		final DataSource imported = MimeMessageParser.parseMimeMessage(message, eager).getAttachmentList().iterator().next().getDataSource();
		assertThat(reads.get()).isEqualTo(eager ? 1 : 0);
		assertType(imported.getContentType());
		assertThat(reads.get()).isEqualTo(eager ? 1 : 0);
	}

	private BodyPart exportedAttachment(final String sourceType) throws Exception {
		final Email email = mail.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
				.withAttachment("notes.txt", CONTENT, sourceType).buildEmail();
		return attachment(mail.converter().fromEmail(email).toMimeMessage());
	}

	private static BodyPart attachment(final MimeMessage message) throws Exception {
		final MimeMultipart parts = (MimeMultipart) message.getContent();
		for (int index = 0; index < parts.getCount(); index++) {
			if (Part.ATTACHMENT.equalsIgnoreCase(parts.getBodyPart(index).getDisposition())) {
				return parts.getBodyPart(index);
			}
		}
		throw new AssertionError("No attachment in exported message");
	}

	private static void assertType(final String value) throws Exception {
		final ContentType type = new ContentType(value);
		assertThat(type.getBaseType()).isEqualTo("text/plain");
		assertThat(type.getParameter("charset")).isEqualTo("ISO-8859-1");
		assertThat(type.getParameter("x-document-mode")).isEqualTo("review");
		assertThat(type.getParameter("name")).isEqualTo("notes.txt");
	}

	private Email convert(final ImportRoute route) throws Exception {
		switch (route) {
			case STATIC_EML: return EmailConverter.emlToEmail(EML);
			case FLUENT_EML_EAGER: return mail.converter().fromEml(EML).withFetchAttachmentData(true).toEmail();
			case FLUENT_EML_LAZY: return mail.converter().fromEml(EML).withFetchAttachmentData(false).toEmail();
			default:
				final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(EML.getBytes(UTF_8)));
				final boolean eager = route == ImportRoute.STATIC_MIME_EAGER || route == ImportRoute.FLUENT_MIME_EAGER;
				return route == ImportRoute.STATIC_MIME_EAGER || route == ImportRoute.STATIC_MIME_LAZY
						? EmailConverter.mimeMessageToEmail(message, null, eager)
						: mail.converter().fromMimeMessage(message).withFetchAttachmentData(eager).toEmail();
		}
	}

	private enum ImportRoute {
		STATIC_EML, FLUENT_EML_EAGER, FLUENT_EML_LAZY,
		STATIC_MIME_EAGER, STATIC_MIME_LAZY, FLUENT_MIME_EAGER, FLUENT_MIME_LAZY
	}
}
