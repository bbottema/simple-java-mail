package org.simplejavamail.api.email;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.EncodingAware;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.email.internal.InternalEmailPopulatingBuilder;
import org.simplejavamail.mailer.MailerHelper;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Base64;
import java.util.Objects;
import java.util.Properties;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.ContentTransferEncoding.BASE_64;
import static org.simplejavamail.api.email.ContentTransferEncoding.BIT7;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class EmailSerializationTest {

	@BeforeEach
	void clearDefaults() {
		ConfigLoaderTestHelper.clearConfigProperties();
	}

	@Test
	void serializesDataSourcesAsIndependentReadOnlySnapshots() throws Exception {
		final TrackingDataSource originalDataSource = new TrackingDataSource(
				"original-source-name.txt",
				"text/custom",
				"base64",
				"content at serialization time".getBytes(UTF_8));
		final AttachmentResource original = new AttachmentResource(
				"mail-facing-name.txt",
				originalDataSource,
				"a description",
				BIT7,
				"stable-content-id");

		final byte[] serialized = serialize(original);
		originalDataSource.replaceData("content after serialization".getBytes(UTF_8));
		final AttachmentResource restored = deserialize(serialized, AttachmentResource.class);

		assertThat(originalDataSource.getInputStreamOpenCount()).isOne();
		assertThat(originalDataSource.getInputStreamCloseCount()).isOne();
		assertThat(restored.getDataSource()).isNotInstanceOf(TrackingDataSource.class);
		assertThat(restored.getName()).isEqualTo("mail-facing-name.txt");
		assertThat(restored.getDescription()).isEqualTo("a description");
		assertThat(restored.getContentTransferEncoding()).isEqualTo(BIT7);
		assertThat(restored.getContentId()).isEqualTo("stable-content-id");
		assertThat(restored.getDataSource().getName()).isEqualTo("original-source-name.txt");
		assertThat(restored.getDataSource().getContentType()).isEqualTo("text/custom");
		assertThat(restored.getDataSource()).isInstanceOf(EncodingAware.class);
		assertThat(((EncodingAware) restored.getDataSource()).getEncoding()).isEqualTo("base64");
		assertThat(restored.readAllData()).isEqualTo("content at serialization time");
		assertThat(restored.readAllData()).isEqualTo("content at serialization time");
		assertThatThrownBy(() -> restored.getDataSource().getOutputStream())
				.isInstanceOf(IOException.class)
				.hasMessage("Serialized attachment snapshots are read-only");
	}

	@Test
	void serializesAllSendRelevantEmailState() throws Exception {
		final MimeMessage messageToForward = createMessageToForward();
		final SmimeSigningConfig smimeSigningConfig = SmimeSigningConfig.builder()
				.pkcs12Config(loadPkcs12KeyStore())
				.signatureAlgorithm("SHA256withRSA")
				.build();
		final byte[] alreadyBase64Encoded = Base64.getEncoder().encode("pre-encoded data".getBytes(UTF_8));

		final InternalEmailPopulatingBuilder builder = (InternalEmailPopulatingBuilder) EmailBuilder.forwarding(messageToForward)
				.from("sender@example.org")
				.withRecipients(new Recipient(null, "recipient@example.org", RecipientType.TO, null))
				.withPlainText("Main message")
				.withHTMLText("<p>Main message</p><img src=\"cid:logo-cid\">")
				.withHeader("X-Round-Trip", "preserved")
				.withAttachment("report.txt", "attachment data".getBytes(UTF_8), "text/plain", "report", BIT7)
				.withPreEncodedAttachment("encoded.txt", alreadyBase64Encoded, "text/plain", "already encoded", BASE_64)
				.withEmbeddedImage("logo.png", "image data".getBytes(UTF_8), "image/png")
				.signWithSmime(smimeSigningConfig);
		builder.withDecryptedAttachments(singletonList(new AttachmentResource(
				"decrypted.txt",
				new ByteArrayDataSource("decrypted data", "text/plain"),
				"recovered from S/MIME")));
		final Email original = builder.buildEmail();

		final Email restored = roundTrip(original, Email.class);

		assertThat(restored.getFromRecipient()).isEqualTo(original.getFromRecipient());
		assertThat(restored.getRecipients()).containsExactlyElementsOf(original.getRecipients());
		assertThat(restored.getSubject()).isEqualTo("Fwd: Forwarded subject");
		assertThat(restored.getPlainText()).isEqualTo("Main message");
		assertThat(restored.getHTMLText()).isEqualTo("<p>Main message</p><img src=\"cid:logo-cid\">");
		assertThat(restored.getHeaders()).isEqualTo(original.getHeaders());
		assertAttachment(restored.getAttachments().get(0), "report.txt", "report", BIT7, null, "attachment data".getBytes(UTF_8));
		assertAttachment(restored.getAttachments().get(1), "encoded.txt", "already encoded", null, BASE_64, alreadyBase64Encoded);
		assertAttachment(restored.getEmbeddedImages().get(0), "logo.png", null, null, null, "image data".getBytes(UTF_8));
		assertAttachment(restored.getDecryptedAttachments().get(0), "decrypted.txt", "recovered from S/MIME", null, null, "decrypted data".getBytes(UTF_8));
		assertThat(restored.getSmimeSigningConfig()).isEqualTo(smimeSigningConfig);
		assertThat(restored.getSmimeSigningConfig().getPkcs12Config().getPkcs12StoreData())
				.containsExactly(smimeSigningConfig.getPkcs12Config().getPkcs12StoreData());
		assertForwardedMessage(restored.getEmailToForward());

		assertThat(MailerHelper.validate(restored)).isTrue();
		assertThatNoException().isThrownBy(() -> EmailBuilder.copying(restored).buildEmail());
	}

	@Test
	void reportsUnreadableAttachmentWhileSerializing() {
		final Email email = EmailBuilder.startingBlank()
				.from("sender@example.org")
				.withRecipients(new Recipient(null, "recipient@example.org", RecipientType.TO, null))
				.withSubject("Unreadable attachment")
				.withPlainText("body")
				.withAttachment("broken.txt", new UnreadableDataSource())
				.buildEmail();

		assertThatThrownBy(() -> serialize(email))
				.isInstanceOf(IOException.class)
				.hasMessageContaining("Unable to read broken attachment");
	}

	@Test
	void readsPre92EmailForInspectionAndFailsClearlyWhenLegacyContentIsUsed() throws Exception {
		final byte[] fixture;
		try (InputStream inputStream = Objects.requireNonNull(
				EmailSerializationTest.class.getResourceAsStream("/serialization/email-9.1.7.base64"),
				"9.1.7 serialization fixture")) {
			fixture = Base64.getDecoder().decode(new String(readAllBytes(inputStream), UTF_8).trim());
		}

		final Email restored = deserialize(fixture, Email.class);

		assertThat(restored.getSubject()).isEqualTo("9.1.7 serialized email");
		assertThat(restored.getPlainText()).isEqualTo("legacy body remains inspectable");
		assertThat(restored.getHeaders()).containsEntry("X-Legacy-Fixture", singletonList("9.1.7"));
		assertThat(restored.getFromRecipient().getAddress()).isEqualTo("legacy-sender@example.org");
		assertThat(restored.getRecipients()).extracting(Recipient::getAddress)
				.containsExactly("legacy-recipient@example.org");
		assertThat(restored.getAttachments()).hasSize(2);
		assertThat(restored.getAttachments().get(0).getName()).isEqualTo("legacy.txt");
		assertThat(restored.getAttachments().get(0).getDescription()).isEqualTo("legacy attachment");
		assertThat(restored.getAttachments().get(0).getContentTransferEncoding()).isEqualTo(BIT7);
		assertThat(restored.getAttachments().get(1).getName()).isEqualTo("legacy-encoded.txt");
		assertThat(restored.getAttachments().get(1).getPreEncodedContentTransferEncoding()).isEqualTo(BASE_64);
		assertThat(restored.getEmbeddedImages()).extracting(AttachmentResource::getName)
				.containsExactly("legacy-logo.png");
		assertThat(restored.getDecryptedAttachments()).extracting(AttachmentResource::getName)
				.containsExactly("legacy-decrypted.txt");
		assertThat(restored.getEmailToForward()).isNull();
		assertThat(restored.getSmimeSigningConfig()).isNull();
		assertThat(restored.toString()).contains("9.1.7 serialized email", "legacy.txt");
		assertThatNoException().isThrownBy(() -> EmailBuilder.copying(restored).buildEmail());

		assertLegacyContentUnavailable(restored.getAttachments().get(0));
		assertThatThrownBy(() -> EmailConverter.emailToMimeMessage(restored).writeTo(new ByteArrayOutputStream()))
				.isInstanceOf(IOException.class)
				.hasMessage("Attachment data is unavailable because this Email was serialized by Simple Java Mail before 9.2.0");
	}

	private static void assertAttachment(AttachmentResource attachment, String name, String description,
			ContentTransferEncoding contentTransferEncoding, ContentTransferEncoding preEncodedContentTransferEncoding, byte[] expectedData)
			throws IOException {
		assertThat(attachment.getName()).isEqualTo(name);
		assertThat(attachment.getDescription()).isEqualTo(description);
		assertThat(attachment.getContentTransferEncoding()).isEqualTo(contentTransferEncoding);
		assertThat(attachment.getPreEncodedContentTransferEncoding()).isEqualTo(preEncodedContentTransferEncoding);
		assertThat(attachment.readAllBytes()).containsExactly(expectedData);
	}

	private static void assertLegacyContentUnavailable(AttachmentResource attachment) {
		assertThatThrownBy(() -> attachment.getDataSource().getInputStream())
				.isInstanceOf(IOException.class)
				.hasMessage("Attachment data is unavailable because this Email was serialized by Simple Java Mail before 9.2.0");
	}

	private static MimeMessage createMessageToForward() throws Exception {
		final MimeMessage forwarded = new MimeMessage(Session.getInstance(new Properties()));
		forwarded.setFrom(new InternetAddress("original-sender@example.org"));
		forwarded.setRecipient(RecipientType.TO, new InternetAddress("original-recipient@example.org"));
		forwarded.setSubject("Forwarded subject", UTF_8.name());

		final MimeBodyPart body = new MimeBodyPart();
		body.setText("Forwarded body", UTF_8.name());
		final MimeBodyPart attachment = new MimeBodyPart();
		attachment.setDataHandler(new DataHandler(new ByteArrayDataSource("forwarded attachment", "text/plain")));
		attachment.setFileName("forwarded.txt");
		final MimeMultipart multipart = new MimeMultipart("mixed");
		multipart.addBodyPart(body);
		multipart.addBodyPart(attachment);
		forwarded.setContent(multipart);
		forwarded.saveChanges();
		forwarded.setHeader("X-Forwarded-Snapshot", "preserved");
		return forwarded;
	}

	private static void assertForwardedMessage(MimeMessage forwarded) throws Exception {
		assertThat(forwarded).isNotNull();
		assertThat(forwarded.getSubject()).isEqualTo("Forwarded subject");
		assertThat(forwarded.getHeader("X-Forwarded-Snapshot", null)).isEqualTo("preserved");
		assertThat(forwarded.getSession()).isNotNull();
		assertThat(forwarded.getContent()).isInstanceOf(MimeMultipart.class);
		final MimeMultipart multipart = (MimeMultipart) forwarded.getContent();
		assertThat(multipart.getCount()).isEqualTo(2);
		assertThat(multipart.getBodyPart(0).getContent()).isEqualTo("Forwarded body");
		assertThat(multipart.getBodyPart(1).getFileName()).isEqualTo("forwarded.txt");
		assertThat(readAllBytes(multipart.getBodyPart(1).getInputStream())).containsExactly("forwarded attachment".getBytes(UTF_8));
	}

	private static byte[] readAllBytes(InputStream inputStream) throws IOException {
		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		final byte[] buffer = new byte[1024];
		int bytesRead;
		while ((bytesRead = inputStream.read(buffer)) != -1) {
			outputStream.write(buffer, 0, bytesRead);
		}
		return outputStream.toByteArray();
	}

	private static byte[] serialize(Object value) throws IOException {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream outputStream = new ObjectOutputStream(bytes)) {
			outputStream.writeObject(value);
		}
		return bytes.toByteArray();
	}

	private static <T> T roundTrip(Object value, Class<T> type) throws IOException, ClassNotFoundException {
		return deserialize(serialize(value), type);
	}

	private static <T> T deserialize(byte[] data, Class<T> type) throws IOException, ClassNotFoundException {
		try (ObjectInputStream inputStream = new ObjectInputStream(new ByteArrayInputStream(data))) {
			return type.cast(inputStream.readObject());
		}
	}

	private static final class TrackingDataSource implements DataSource, EncodingAware, Serializable {

		private static final long serialVersionUID = 1L;

		private final String name;
		private final String contentType;
		private final String encoding;
		private byte[] data;
		private int inputStreamOpenCount;
		private int inputStreamCloseCount;

		private TrackingDataSource(String name, String contentType, String encoding, byte[] data) {
			this.name = name;
			this.contentType = contentType;
			this.encoding = encoding;
			this.data = data.clone();
		}

		@Override
		public InputStream getInputStream() {
			inputStreamOpenCount++;
			return new FilterInputStream(new ByteArrayInputStream(data)) {
				@Override
				public void close() throws IOException {
					inputStreamCloseCount++;
					super.close();
				}
			};
		}

		@Override
		public OutputStream getOutputStream() {
			return new ByteArrayOutputStream();
		}

		@Override
		public String getContentType() {
			return contentType;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public String getEncoding() {
			return encoding;
		}

		private void replaceData(byte[] data) {
			this.data = data.clone();
		}

		private int getInputStreamOpenCount() {
			return inputStreamOpenCount;
		}

		private int getInputStreamCloseCount() {
			return inputStreamCloseCount;
		}
	}

	private static final class UnreadableDataSource implements DataSource {

		@Override
		public InputStream getInputStream() throws IOException {
			throw new IOException("Unable to read broken attachment");
		}

		@Override
		public OutputStream getOutputStream() {
			return new ByteArrayOutputStream();
		}

		@Override
		public String getContentType() {
			return "text/plain";
		}

		@Override
		public String getName() {
			return "broken.txt";
		}
	}
}
