package org.simplejavamail.converter;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class MultipartAttachmentImportTest {

	private static final String INNER_TYPE = "multipart/mixed; boundary=inner; x-bundle=review";
	private static final String INNER_CONTENT = "--inner\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nAttached text\r\n"
			+ "--inner\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<p>Attached HTML</p>\r\n"
			+ "--inner\r\nContent-Type: application/octet-stream\r\n"
			+ "Content-Disposition: attachment; filename=inside.bin\r\n\r\nInner file\r\n"
			+ "--inner\r\nContent-Type: image/png\r\nContent-Disposition: inline; filename=inside.png\r\n"
			+ "Content-ID: <inside-image>\r\n\r\nInner image\r\n--inner--\r\n";
	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

	@ParameterizedTest
	@EnumSource(ImportRoute.class)
	void importsTheAttachedMultipartAsOneResource(final ImportRoute route) throws Exception {
		final String source = messageWithContainer("attachment; filename=bundle.mime", INNER_TYPE);
		final Email imported = convert(route, source);
		assertImportedBundle(imported);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void preservesAnAttachmentNestedInsideAnOrdinaryContainer(final boolean eager) throws Exception {
		final String source = messageWithContainer("attachment; filename=bundle.mime", INNER_TYPE, true);
		assertImportedBundle(mail.converter().fromEml(source).withFetchAttachmentData(eager).toEmail());
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void preservesTheBundleAcrossRepeatedMimeAndEmlConversions(final boolean eager) throws Exception {
		Email imported = mail.converter().fromEml(messageWithContainer("attachment; filename=bundle.mime", INNER_TYPE))
				.withFetchAttachmentData(eager).toEmail();
		for (int round = 0; round < 3; round++) {
			assertImportedBundle(imported);
			final MimeMessage rendered = mail.converter().fromEmail(imported).toMimeMessage();
			imported = mail.converter().fromMimeMessage(rendered).withFetchAttachmentData(eager).toEmail();
			assertImportedBundle(imported);
			final String eml = mail.converter().fromEmail(imported).toEml();
			imported = mail.converter().fromEml(eml).withFetchAttachmentData(eager).toEmail();
		}
		assertImportedBundle(imported);
	}

	@ParameterizedTest
	@ValueSource(strings = {"multipart/alternative", "multipart/related", "multipart/report"})
	void honorsExplicitAttachmentDispositionForEveryMultipartSubtype(final String type) throws Exception {
		for (final boolean eager : new boolean[] {false, true}) {
			final Email imported = mail.converter().fromEml(messageWithContainer("AtTaChMeNt; filename=bundle.mime",
					type + "; boundary=inner; x-bundle=review")).withFetchAttachmentData(eager).toEmail();
			assertThat(imported.getPlainText()).isEqualTo("Visible body");
			assertThat(imported.getAttachments()).hasSize(1);
			final AttachmentResource bundle = imported.getAttachments().get(0);
			assertThat(new ContentType(bundle.getDataSource().getContentType()).getBaseType()).isEqualTo(type);
			assertBundleContent(bundle);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void keepsTheExistingEagerAndLazyStreamOwnership(final boolean eager) throws Exception {
		final AtomicInteger opened = new AtomicInteger();
		final AtomicInteger closed = new AtomicInteger();
		final MimeBodyPart bundle = new MimeBodyPart() {
			@Override public Object getContent() { throw new AssertionError("An attached container must not be traversed"); }
		};
		bundle.setDataHandler(new DataHandler(new DataSource() {
			@Override public InputStream getInputStream() {
				opened.incrementAndGet();
				return new ByteArrayInputStream(INNER_CONTENT.getBytes(UTF_8)) {
					@Override public void close() throws IOException { closed.incrementAndGet(); super.close(); }
				};
			}
			@Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
			@Override public String getContentType() { return INNER_TYPE; }
			@Override public String getName() { return "bundle.mime"; }
		}));
		bundle.setDisposition(Part.ATTACHMENT);
		bundle.setFileName("bundle.mime");
		final MimeMultipart parts = new MimeMultipart();
		parts.addBodyPart(bundle);
		final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		message.setContent(parts);
		final DataSource imported = MimeMessageParser.parseMimeMessage(message, eager)
				.getAttachmentList().iterator().next().getDataSource();
		assertThat(opened.get()).isEqualTo(eager ? 1 : 0);
		assertThat(closed.get()).isEqualTo(eager ? 1 : 0);
		assertThat(new ContentType(imported.getContentType()).getParameter("boundary")).isEqualTo("inner");
		assertThat(opened.get()).isEqualTo(eager ? 1 : 0);
		try (InputStream stream = imported.getInputStream()) {
			assertThat(new String(stream.readAllBytes(), UTF_8)).isEqualTo(INNER_CONTENT);
		}
		assertThat(opened.get()).isEqualTo(1);
		assertThat(closed.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "inline; filename=bundle.mime", "; filename=bundle.mime"})
	void keepsFlatteningOrdinaryInlineAndFilenameOnlyContainers(final String disposition) {
		final String actualDisposition = disposition.startsWith(";") ? null : disposition;
		final String type = INNER_TYPE + (disposition.startsWith(";") ? "; name=bundle.mime" : "");
		final Email imported = mail.converter().fromEml(messageWithContainer(actualDisposition, type)).toEmail();
		assertThat(imported.getPlainText()).isEqualTo("Visible bodyAttached text");
		assertThat(imported.getHTMLText()).isEqualTo("<p>Visible HTML</p><p>Attached HTML</p>");
		assertThat(imported.getAttachments()).extracting(AttachmentResource::getName).containsExactly("inside.bin", "inside.png");
	}

	@Test
	void keepsInterpretingAnEffectiveRootWithAttachmentDisposition() {
		final String root = "From: sender@example.org\r\nTo: reader@example.org\r\nMIME-Version: 1.0\r\n"
				+ "Content-Type: " + INNER_TYPE + "\r\nContent-Disposition: attachment; filename=root.mime\r\n\r\n" + INNER_CONTENT;
		final Email imported = mail.converter().fromEml(root).toEmail();
		assertThat(imported.getPlainText()).isEqualTo("Attached text");
		assertThat(imported.getHTMLText()).isEqualTo("<p>Attached HTML</p>");
		assertThat(imported.getAttachments()).extracting(AttachmentResource::getName).containsExactly("inside.bin", "inside.png");
	}

	private static void assertImportedBundle(final Email imported) throws Exception {
		assertThat(imported.getPlainText()).isEqualTo("Visible body");
		assertThat(imported.getHTMLText()).isEqualTo("<p>Visible HTML</p>");
		assertThat(imported.getEmbeddedImages()).isEmpty();
		assertThat(imported.getDeliveryStatusReport()).isNull();
		assertThat(imported.getAttachments()).hasSize(1);
		final AttachmentResource bundle = imported.getAttachments().get(0);
		assertThat(bundle.getName()).isEqualTo("bundle.mime");
		assertThat(bundle.getDescription()).isEqualTo("Attached bundle");
		assertThat(bundle.getContentId()).isEqualTo("bundle@example.org");
		assertThat(bundle.getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BIT7);
		assertThat(new ContentType(bundle.getDataSource().getContentType()).getBaseType()).isEqualTo("multipart/mixed");
		assertBundleContent(bundle);
	}

	private static void assertBundleContent(final AttachmentResource bundle) throws Exception {
		final ContentType type = new ContentType(bundle.getDataSource().getContentType());
		assertThat(type.getParameter("boundary")).isEqualTo("inner");
		assertThat(type.getParameter("x-bundle")).isEqualTo("review");
		final MimeMultipart content = new MimeMultipart(new ByteArrayDataSource(bundle.readAllBytes(), type.toString()));
		assertThat(content.getCount()).isEqualTo(4);
		assertThat(content.getBodyPart(0).getContent()).isEqualTo("Attached text");
		assertThat(content.getBodyPart(1).getContent()).isEqualTo("<p>Attached HTML</p>");
		assertThat(content.getBodyPart(2).getFileName()).isEqualTo("inside.bin");
		try (InputStream stream = content.getBodyPart(2).getInputStream()) {
			assertThat(new String(stream.readAllBytes(), UTF_8)).isEqualTo("Inner file");
		}
		assertThat(content.getBodyPart(3).getHeader("Content-ID")).containsExactly("<inside-image>");
		try (InputStream stream = content.getBodyPart(3).getInputStream()) {
			assertThat(new String(stream.readAllBytes(), UTF_8)).isEqualTo("Inner image");
		}
	}

	private Email convert(final ImportRoute route, final String eml) throws Exception {
		switch (route) {
			case STATIC_EML: return EmailConverter.emlToEmail(eml);
			case FLUENT_EML_EAGER: return mail.converter().fromEml(eml).withFetchAttachmentData(true).toEmail();
			case FLUENT_EML_LAZY: return mail.converter().fromEml(eml).withFetchAttachmentData(false).toEmail();
			default:
				final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml.getBytes(UTF_8)));
				final boolean eager = route == ImportRoute.STATIC_MIME_EAGER || route == ImportRoute.FLUENT_MIME_EAGER;
				return route == ImportRoute.STATIC_MIME_EAGER || route == ImportRoute.STATIC_MIME_LAZY
						? EmailConverter.mimeMessageToEmail(message, null, eager)
						: mail.converter().fromMimeMessage(message).withFetchAttachmentData(eager).toEmail();
		}
	}

	private static String messageWithContainer(final String disposition, final String type) {
		return messageWithContainer(disposition, type, false);
	}

	private static String messageWithContainer(final String disposition, final String type, final boolean nested) {
		return "From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Attached multipart\r\nMIME-Version: 1.0\r\n"
				+ "Content-Type: multipart/mixed; boundary=outer\r\n\r\n"
				+ "--outer\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nVisible body\r\n"
				+ "--outer\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<p>Visible HTML</p>\r\n"
				+ "--outer\r\n"
				+ (nested ? "Content-Type: multipart/related; boundary=wrapper\r\n\r\n--wrapper\r\n" : "")
				+ "Content-Type: " + type + "\r\n"
				+ (disposition == null || disposition.isEmpty() ? "" : "Content-Disposition: " + disposition + "\r\n")
				+ "Content-ID: <bundle@example.org>\r\nContent-Description: Attached bundle\r\n"
				+ "Content-Transfer-Encoding: 7bit\r\n\r\n" + INNER_CONTENT
				+ (nested ? "--wrapper--\r\n" : "") + "--outer--\r\n";
	}

	private enum ImportRoute {
		STATIC_EML, FLUENT_EML_EAGER, FLUENT_EML_LAZY,
		STATIC_MIME_EAGER, STATIC_MIME_LAZY, FLUENT_MIME_EAGER, FLUENT_MIME_LAZY
	}
}
