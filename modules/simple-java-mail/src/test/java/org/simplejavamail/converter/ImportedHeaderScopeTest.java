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
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class ImportedHeaderScopeTest {

	private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
	private static final String ROOT = "From: Sender <sender@shop.example>\r\nTo: reader@example.org\r\n"
			+ "Subject: Invoice\r\nX-Context: parent first\r\nX-Context: parent second\r\n";
	private static final String ROOT_CHOICES = "Return-Path: <bounces@shop.example>\r\n"
			+ "Disposition-Notification-To: receipt@shop.example\r\nReturn-Receipt-To: return@shop.example\r\n";
	private static final String CHILD_CHOICES = "Return-Path: <attachment@other.example>\r\n"
			+ "Disposition-Notification-To: receipt@other.example\r\nReturn-Receipt-To: return@other.example\r\n"
			+ "X-Context: child\r\nX-Child-Only: attachment fact\r\n";
	private static final String ATTACHMENT = "Content-Type: application/octet-stream\r\n"
			+ "Content-Disposition: attachment; filename=payload.bin\r\nContent-Description: Invoice payload\r\n"
			+ "Content-ID: <payload-id>\r\nContent-Transfer-Encoding: base64\r\n";

	@ParameterizedTest
	@EnumSource(ImportRoute.class)
	void childHeadersCannotReplaceOrExtendRootFacts(final ImportRoute route) throws Exception {
		final Email email = convert(route, multipart(ROOT_CHOICES, CHILD_CHOICES));
		assertRootFacts(email);
		assertThat(email.getPlainText()).isEqualTo("Invoice body");
		assertThat(email.getAttachments()).hasSize(1);
		assertThat(email.getAttachments().get(0).getName()).isEqualTo("payload.bin");
		assertThat(email.getAttachments().get(0).getContentId()).isEqualTo("payload-id");
		assertThat(email.getAttachments().get(0).getDescription()).isEqualTo("Invoice payload");
		assertThat(email.getAttachments().get(0).getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
		assertThat(email.getAttachments().get(0).getDataSource().getContentType()).isEqualTo("application/octet-stream");
		assertThat(email.getAttachments().get(0).readAllBytes()).isEqualTo("payload".getBytes(UTF_8));
	}

	@ParameterizedTest
	@EnumSource(ImportRoute.class)
	void missingRootChoicesStayAbsentDespiteChildHeaders(final ImportRoute route) throws Exception {
		final Email email = convert(route, multipart("", CHILD_CHOICES));
		assertThat(email.getBounceToRecipient()).isNull();
		assertThat(email.getDispositionNotificationToRecipients()).isEmpty();
		assertThat(email.getReturnReceiptToRecipients()).isEmpty();
		assertRootHeaders(email);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "<>", "(comment only)", "broken <address"})
	void unusableChildReturnPathsCannotClearParentBounceOrEscapeAsRootHeaders(final String value) {
		for (final boolean eager : new boolean[] {true, false}) {
			final Email email = MAIL.converter().fromEml(multipart(ROOT_CHOICES, "Return-Path: " + value + "\r\n"))
					.withFetchAttachmentData(eager).toEmail();
			assertRootFacts(email);
			assertThat(email.getHeaders()).doesNotContainKey("Return-Path");
		}
	}

	@Test
	void unusableRootReturnPathRemainsSourceEvidenceDespiteUsableChildAddress() {
		final Email email = MAIL.converter().fromEml(multipart("Return-Path: (root comment)\r\n", CHILD_CHOICES)).toEmail();
		assertThat(email.getBounceToRecipient()).isNull();
		assertThat(email.getHeaders().get("Return-Path")).containsExactly("(root comment)");
		assertThat(email.getDispositionNotificationToRecipients()).isEmpty();
		assertThat(email.getReturnReceiptToRecipients()).isEmpty();
		assertRootHeaders(email);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void nestedContainersBodyPartsAndInlineResourcesDoNotSupplyRootHeaders(final boolean eager) {
		final String source = ROOT + ROOT_CHOICES + "Content-Type: multipart/mixed; boundary=outer\r\n\r\n"
				+ "--outer\r\n" + CHILD_CHOICES + "Content-Type: multipart/related; boundary=inner\r\n\r\n"
				+ "--inner\r\n" + CHILD_CHOICES + "Content-Type: text/html; charset=UTF-8\r\n\r\n"
				+ "<img src=\"cid:picture\">\r\n--inner\r\n" + CHILD_CHOICES
				+ "Content-Type: image/png\r\nContent-Disposition: inline; filename=picture.png\r\nContent-ID: <picture>\r\n\r\n"
				+ "image bytes\r\n--inner--\r\n--outer\r\n" + ATTACHMENT + CHILD_CHOICES
				+ "\r\ncGF5bG9hZA==\r\n--outer--\r\n";
		final Email email = MAIL.converter().fromEml(source).withFetchAttachmentData(eager).toEmail();
		assertRootFacts(email);
		assertThat(email.getHTMLText()).isEqualTo("<img src=\"cid:picture\">");
		assertThat(email.getEmbeddedImages()).hasSize(1);
		assertThat(email.getEmbeddedImages().get(0).getContentId()).isEqualTo("picture");
		assertThat(email.getEmbeddedImages().get(0).readAllBytes()).isEqualTo("image bytes".getBytes(UTF_8));
		assertThat(email.getAttachments()).extracting("name").containsExactly("payload.bin");
	}

	@Test
	void attachedMessageKeepsItsOwnHeadersWhenReadSeparately() {
		final String nested = "From: nested@example.org\r\nReturn-Path: <nested@example.org>\r\n"
				+ "Disposition-Notification-To: nested-receipt@example.org\r\nX-Nested: nested value\r\n\r\nNested body\r\n";
		final String source = ROOT + ROOT_CHOICES + "Content-Type: multipart/mixed; boundary=parts\r\n\r\n"
				+ "--parts\r\nContent-Type: text/plain\r\n\r\nInvoice body\r\n--parts\r\n"
				+ "Content-Type: message/rfc822\r\nContent-Disposition: attachment; filename=forwarded.eml\r\n"
				+ CHILD_CHOICES + "\r\n" + nested + "--parts--\r\n";
		final Email parent = MAIL.converter().fromEml(source).toEmail();
		assertRootFacts(parent);
		assertThat(parent.getHeaders()).doesNotContainKey("X-Nested");
		assertThat(parent.getAttachments()).hasSize(1);
		final Email attached = MAIL.converter().fromEml(parent.getAttachments().get(0).readAllBytes()).toEmail();
		assertThat(attached.getBounceToRecipient().getAddress()).isEqualTo("nested@example.org");
		assertThat(attached.getDispositionNotificationToRecipients()).extracting("address").containsExactly("nested-receipt@example.org");
		assertThat(attached.getHeaders().get("X-Nested")).containsExactly("nested value");
	}

	@Test
	void singlePartRootStillSuppliesMessageFacts() {
		final Email email = MAIL.converter().fromEml(ROOT + ROOT_CHOICES + "Content-Type: text/plain\r\n\r\nInvoice body").toEmail();
		assertRootFacts(email);
		assertThat(email.getPlainText()).isEqualTo("Invoice body");
	}

	@Test
	void lazyHeaderInterpretationDoesNotReadAttachmentContent() throws Exception {
		final AtomicInteger reads = new AtomicInteger();
		final AtomicInteger closes = new AtomicInteger();
		final MimeBodyPart attachment = new MimeBodyPart();
		attachment.setDisposition(Part.ATTACHMENT);
		attachment.setFileName("payload.bin");
		attachment.setHeader("Return-Path", "<attachment@other.example>");
		attachment.setHeader("X-Child-Only", "attachment fact");
		attachment.setDataHandler(new DataHandler(new DataSource() {
			@Override public InputStream getInputStream() {
				reads.incrementAndGet();
				return new ByteArrayInputStream(new byte[] {1, 2, 3}) {
					@Override public void close() { closes.incrementAndGet(); }
				};
			}
			@Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
			@Override public String getContentType() { return "application/octet-stream"; }
			@Override public String getName() { return "payload.bin"; }
		}));
		final MimeMultipart parts = new MimeMultipart();
		parts.addBodyPart(attachment);
		final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		message.setHeader("Return-Path", "<bounces@shop.example>");
		message.setContent(parts);
		final MimeMessageParser.ParsedMimeMessageComponents parsed = MimeMessageParser.parseMimeMessage(message, false);
		assertThat(parsed.getBounceToAddress().getAddress()).isEqualTo("bounces@shop.example");
		assertThat(parsed.getHeaders()).doesNotContainKey("X-Child-Only");
		assertThat(reads.get()).isZero();
		assertThat(closes.get()).isZero();
		try (InputStream stream = parsed.getAttachmentList().iterator().next().getDataSource().getInputStream()) {
			assertThat(stream.readAllBytes()).containsExactly(1, 2, 3);
		}
		assertThat(reads.get()).isEqualTo(1);
		assertThat(closes.get()).isEqualTo(1);
	}

	@Test
	void protectedImportUsesTheEffectiveRootAndPreservesOriginalSignedBytes() throws Exception {
		final Session session = Session.getInstance(new Properties());
		final MimeMessage plain = new MimeMessage(session, new ByteArrayInputStream(multipart(ROOT_CHOICES, CHILD_CHOICES).getBytes(UTF_8)));
		final Email sender = MAIL.emailBuilder().startingBlank().from("sender@shop.example").to("reader@example.org").buildEmail();
		final MimeMessage signed = ModuleLoader.loadSmimeModule().signMessageWithSmime(session, sender, plain,
				SmimeSigningConfig.builder().pkcs12Config(loadPkcs12KeyStore()).build());
		final byte[] source = EmailConverter.mimeMessageToEMLByteArray(signed);
		final Email email = MAIL.converter().fromEml(source).toEmail();
		assertRootFacts(email);
		assertThat(email.getPlainText()).isEqualTo("Invoice body");
		assertThat(email.getAttachments()).extracting("name").contains("payload.bin");
		assertThat(email.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(VerificationStatus.VALID);
		assertThat(email.getOriginalSmimeDetails().getOriginalProtectedMessage()).isEqualTo(source);
	}

	@Test
	void exactInspectionUsesRootFactsWithoutChangingAuthoritativeBytes() {
		final byte[] source = multipart(ROOT_CHOICES, CHILD_CHOICES).getBytes(UTF_8);
		final Email email = MAIL.emailBuilder().startingFromExactEml(source).withEnvelopeRecipients("reader@example.org").buildEmail();
		// Exact submission deliberately keeps its envelope sender separate from the historical Return-Path header.
		assertThat(email.getBounceToRecipient()).isNull();
		assertThat(email.getDispositionNotificationToRecipients()).extracting("address").containsExactly("receipt@shop.example");
		assertThat(email.getReturnReceiptToRecipients()).extracting("address").containsExactly("return@shop.example");
		assertRootHeaders(email);
		assertThat(MAIL.converter().fromEmail(email).toEmlBytes()).isEqualTo(source);
	}

	private static void assertRootFacts(final Email email) {
		assertThat(email.getBounceToRecipient().getAddress()).isEqualTo("bounces@shop.example");
		assertThat(email.getDispositionNotificationToRecipients()).extracting("address").containsExactly("receipt@shop.example");
		assertThat(email.getReturnReceiptToRecipients()).extracting("address").containsExactly("return@shop.example");
		assertRootHeaders(email);
	}

	private static void assertRootHeaders(final Email email) {
		assertThat(email.getSubject()).isEqualTo("Invoice");
		assertThat(email.getFromRecipient().getAddress()).isEqualTo("sender@shop.example");
		assertThat(email.getHeaders().get("X-Context")).containsExactly("parent first", "parent second");
		assertThat(email.getHeaders()).doesNotContainKey("X-Child-Only");
	}

	private static String multipart(final String rootChoices, final String childChoices) {
		return ROOT + rootChoices + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=parts\r\n\r\n"
				+ "--parts\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nInvoice body\r\n"
				+ "--parts\r\n" + ATTACHMENT + childChoices + "\r\ncGF5bG9hZA==\r\n--parts--\r\n";
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

	private enum ImportRoute {
		STATIC_EML, FLUENT_EML_EAGER, FLUENT_EML_LAZY, STATIC_MIME_EAGER, STATIC_MIME_LAZY, FLUENT_MIME_EAGER, FLUENT_MIME_LAZY
	}
}
