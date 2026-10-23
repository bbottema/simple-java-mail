package org.simplejavamail.converter.internal;

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
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.ContentTransferEncoding.BASE_64;
import static org.simplejavamail.api.email.ContentTransferEncoding.BIT7;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.VALID;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class InlineResourceMetadataTest {

	private static final byte[] IMAGE = {0, 1, 2, 3, 127, (byte) 128, (byte) 255};
	private static final String HTML = "<img src=\"cid:company-logo\"><img src=\"cid:secondary-logo\">";
	private static final String EML = "From: sender@example.org\r\nTo: reader@example.org\r\n"
			+ "Subject: Inline metadata\r\nMIME-Version: 1.0\r\n"
			+ "Content-Type: multipart/mixed; boundary=outer\r\n\r\n"
			+ "--outer\r\nContent-Type: multipart/related; boundary=related\r\n\r\n"
			+ "--related\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: 7bit\r\n\r\n" + HTML + "\r\n"
			+ "--related\r\nContent-Type: image/png; name=logo.png\r\nContent-Disposition: inline; filename=logo.png\r\n"
			+ "Content-ID: <company-logo>\r\nContent-Description: Company logo\r\nContent-Transfer-Encoding: base64\r\n\r\nAAECA3+A/w==\r\n"
			+ "--related\r\nContent-Type: image/png; name=secondary.png\r\nContent-Disposition: inline; filename=secondary.png\r\n"
			+ "Content-ID: <secondary-logo>\r\n\r\nsecondary\r\n--related--\r\n"
			+ "--outer\r\nContent-Type: application/octet-stream; name=document.txt\r\nContent-Disposition: attachment; filename=document.txt\r\n"
			+ "Content-Description: Ordinary attachment\r\nContent-Transfer-Encoding: base64\r\n\r\ncGFwZXJ3b3Jr\r\n"
			+ "--outer\r\nContent-Type: image/png; name=unused.png\r\nContent-Disposition: inline; filename=unused.png\r\n"
			+ "Content-ID: <unused-image>\r\nContent-Description: Unreferenced image\r\nContent-Transfer-Encoding: 7bit\r\n\r\nunused\r\n--outer--\r\n";
	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

	@ParameterizedTest
	@EnumSource(ImportRoute.class)
	void importRetainsInlineMetadataAndDecodedContentWithoutChangingClassification(final ImportRoute route) throws Exception {
		assertImported(convert(route));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void compositionExportAndImportRetainTheCallersInlineResourceMetadata(final boolean eager) {
		final AttachmentResource logo = AttachmentResource.builder().withData(IMAGE, "image/png").withName("logo.png")
				.withContentId("company-logo").withDescription("Company logo").withContentTransferEncoding(BASE_64).build();
		final Email original = mail.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
				.withHTMLText("<img src=\"cid:company-logo\">").withEmbeddedImage(logo).buildEmail();
		final String exported = mail.converter().fromEmail(original).toEml();
		final Email imported = mail.converter().fromEml(exported).withFetchAttachmentData(eager).toEmail();
		assertThat(imported.getEmbeddedImages()).hasSize(1);
		assertLogo(imported.getEmbeddedImages().get(0));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void copyingAndSupportedSerializationKeepInlineMetadataAndDecodedBytes(final boolean eager) throws Exception {
		final Email imported = mail.converter().fromEml(EML).withFetchAttachmentData(eager).toEmail();
		final Email copied = mail.emailBuilder().copying(imported).buildEmail();
		assertThat(copied.getEmbeddedImages().get(0).getDataSource()).isSameAs(imported.getEmbeddedImages().get(0).getDataSource());
		final Email restored = serializedCopy(imported);
		assertImported(copied);
		assertImported(restored);
		assertImported(mail.emailBuilder().copying(restored).buildEmail());
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void exportAndReimportDoNotDoubleEncodeImportedContent(final boolean eager) throws Exception {
		final Email imported = mail.converter().fromEml(EML).withFetchAttachmentData(eager).toEmail();
		final MimeMessage rendered = mail.converter().fromEmail(imported).toMimeMessage();
		final MimeBodyPart logo = findInline(rendered, "<company-logo>");
		assertThat(logo.getDescription()).isEqualTo("Company logo");
		assertThat(logo.getEncoding()).isEqualTo("base64");
		try (InputStream input = logo.getInputStream()) {
			assertThat(input.readAllBytes()).isEqualTo(IMAGE);
		}
		final Email reimported = mail.converter().fromEml(mail.converter().fromEmail(imported).toEml())
				.withFetchAttachmentData(eager).toEmail();
		assertLogo(reimported.getEmbeddedImages().get(0));
		assertThat(reimported.getHTMLText()).isEqualTo(HTML);
		assertThat(reimported.getAttachments()).extracting(AttachmentResource::getName).containsExactly("document.txt", "unused.png");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void inlineMetadataMappingAndCopyingAddNoContentReads(final boolean eager) throws Exception {
		final AtomicInteger reads = new AtomicInteger();
		final AtomicInteger closes = new AtomicInteger();
		final MimeBodyPart logo = new MimeBodyPart();
		logo.setDataHandler(new DataHandler(new DataSource() {
			@Override public InputStream getInputStream() {
				reads.incrementAndGet();
				return new ByteArrayInputStream(IMAGE) {
					@Override public void close() { closes.incrementAndGet(); }
				};
			}
			@Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
			@Override public String getContentType() { return "image/png"; }
			@Override public String getName() { return "logo.png"; }
		}));
		logo.setDisposition(Part.INLINE);
		logo.setFileName("logo.png");
		logo.setContentID("<company-logo>");
		logo.setDescription("Company logo");
		logo.setHeader("Content-Transfer-Encoding", "base64");
		final MimeBodyPart body = new MimeBodyPart();
		body.setContent("<img src=\"cid:company-logo\">", "text/html; charset=UTF-8");
		final MimeMultipart parts = new MimeMultipart("related");
		parts.addBodyPart(body);
		parts.addBodyPart(logo);
		final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()));
		source.setContent(parts);
		final MimeMessageParser.ParsedMimeMessageComponents parsed = MimeMessageParser.parseMimeMessage(source, eager);
		assertThat(reads.get()).isEqualTo(eager ? 1 : 0);
		assertThat(closes.get()).isEqualTo(eager ? 1 : 0);
		assertThat(parsed.getCidMap()).hasSize(1);
		final DataSource parsedSource = parsed.getCidMap().values().iterator().next().getDataSource();
		final Email imported = MimeMessageEmailMapper.populate(mail.emailBuilder().startingBlank(), parsed).buildEmail();
		final Email copied = mail.emailBuilder().copying(imported).buildEmail();
		for (Email email : new Email[] {imported, copied}) {
			final AttachmentResource image = email.getEmbeddedImages().get(0);
			assertThat(image.getDescription()).isEqualTo("Company logo");
			assertThat(image.getContentTransferEncoding()).isEqualTo(BASE_64);
			assertThat(image.getPreEncodedContentTransferEncoding()).isNull();
			assertThat(image.getDataSource()).isSameAs(parsedSource);
		}
		assertThat(reads.get()).isEqualTo(eager ? 1 : 0);
		assertThat(closes.get()).isEqualTo(eager ? 1 : 0);
		assertThat(copied.getEmbeddedImages().get(0).readAllBytes()).isEqualTo(IMAGE);
		assertThat(reads.get()).isEqualTo(1);
		assertThat(closes.get()).isEqualTo(1);
	}

	@Test
	void exactInspectionRetainsAuthoritativeBytesWhileExposingInlineMetadata() {
		final byte[] bytes = EML.getBytes(UTF_8);
		final Email exact = mail.emailBuilder().startingFromExactEml(bytes).withEnvelopeRecipients("reader@example.org").buildEmail();
		assertLogo(exact.getEmbeddedImages().get(0));
		assertThat(mail.converter().fromEmail(exact).toEmlBytes()).isEqualTo(bytes);
	}

	@Test
	void signedImportRetainsOriginalProtectedBytesAndInlineMetadata() throws Exception {
		final Session session = Session.getInstance(new Properties());
		final MimeMessage source = new MimeMessage(session, new ByteArrayInputStream(EML.getBytes(UTF_8)));
		final Email configuration = mail.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org").buildEmail();
		final MimeMessage signed = ModuleLoader.loadSmimeModule().signMessageWithSmime(session, configuration, source,
				SmimeSigningConfig.builder().pkcs12Config(loadPkcs12KeyStore()).build());
		final byte[] bytes = EmailConverter.mimeMessageToEMLByteArray(signed);
		final Email imported = mail.converter().fromEml(bytes).toEmail();
		assertThat(imported.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(VALID);
		assertThat(imported.getOriginalSmimeDetails().getOriginalProtectedMessage()).isEqualTo(bytes);
		assertLogo(imported.getEmbeddedImages().get(0));
	}

	private void assertImported(final Email imported) {
		assertThat(imported.getHTMLText()).isEqualTo(HTML);
		assertThat(imported.getEmbeddedImages()).extracting(AttachmentResource::getName).containsExactly("logo.png", "secondary.png");
		assertThat(imported.getEmbeddedImages()).extracting(AttachmentResource::getContentId).containsExactly("company-logo", "secondary-logo");
		assertLogo(imported.getEmbeddedImages().get(0));
		final AttachmentResource secondary = imported.getEmbeddedImages().get(1);
		assertThat(secondary.getDescription()).isNull();
		assertThat(secondary.getContentTransferEncoding()).isNull();
		assertThat(secondary.getPreEncodedContentTransferEncoding()).isNull();
		assertThat(secondary.readAllBytes()).isEqualTo("secondary".getBytes(UTF_8));
		assertThat(imported.getAttachments()).extracting(AttachmentResource::getName).containsExactly("document.txt", "unused.png");
		final AttachmentResource attachment = imported.getAttachments().get(0);
		assertThat(attachment.getDescription()).isEqualTo("Ordinary attachment");
		assertThat(attachment.getContentTransferEncoding()).isEqualTo(BASE_64);
		assertThat(attachment.readAllBytes()).isEqualTo("paperwork".getBytes(UTF_8));
		final AttachmentResource unused = imported.getAttachments().get(1);
		assertThat(unused.getDescription()).isEqualTo("Unreferenced image");
		assertThat(unused.getContentTransferEncoding()).isEqualTo(BIT7);
		assertThat(unused.readAllBytes()).isEqualTo("unused".getBytes(UTF_8));
	}

	private static void assertLogo(final AttachmentResource logo) {
		assertThat(logo.getName()).isEqualTo("logo.png");
		assertThat(logo.getContentId()).isEqualTo("company-logo");
		assertThat(logo.getDescription()).isEqualTo("Company logo");
		assertThat(logo.getContentTransferEncoding()).isEqualTo(BASE_64);
		assertThat(logo.getPreEncodedContentTransferEncoding()).isNull();
		assertThat(logo.readAllBytes()).isEqualTo(IMAGE);
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

	private static Email serializedCopy(final Email email) throws Exception {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(email); }
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { return (Email) input.readObject(); }
	}

	private static MimeBodyPart findInline(final Part part, final String contentId) throws Exception {
		if (part instanceof MimeBodyPart && contentId.equals(((MimeBodyPart) part).getContentID())) {
			return (MimeBodyPart) part;
		}
		if (part.isMimeType("multipart/*")) {
			final MimeMultipart children = (MimeMultipart) part.getContent();
			for (int i = 0; i < children.getCount(); i++) {
				final MimeBodyPart found = findInline(children.getBodyPart(i), contentId);
				if (found != null) { return found; }
			}
		}
		return null;
	}

	private enum ImportRoute {
		STATIC_EML, FLUENT_EML_EAGER, FLUENT_EML_LAZY,
		STATIC_MIME_EAGER, STATIC_MIME_LAZY, FLUENT_MIME_EAGER, FLUENT_MIME_LAZY
	}
}
