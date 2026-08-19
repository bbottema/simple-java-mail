package org.simplejavamail.mailer;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.api.SimpleJavaMail;
import testutil.ConfigLoaderTestHelper;
import testutil.testrules.SmtpServerExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static jakarta.mail.Message.RecipientType.TO;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class EmailSerializationSmtpTest {

	private static final int SMTP_PORT = 253;

	@RegisterExtension
	static final SmtpServerExtension SMTP_SERVER = new SmtpServerExtension(SMTP_PORT, null, null);

	@BeforeEach
	void clearDefaults() {
	}

	@Test
	void deserializedSnapshotCanStillBeSignedAndSentWithAllContent() throws Exception {
		final MimeMessage forwardedMessage = EmailConverter.emailToMimeMessage(SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
				.from("original-sender@example.org")
				.withRecipients(new Recipient(null, "original-recipient@example.org", TO, null))
				.withSubject("Forwarded subject")
				.withPlainText("Forwarded body")
				.buildEmail());
		final SmimeSigningConfig signingConfig = SmimeSigningConfig.builder()
				.pkcs12Config(loadPkcs12KeyStore())
				.signatureAlgorithm("SHA256withRSA")
				.build();
		final Email email = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().forwarding(forwardedMessage)
				.from("sender@example.org")
				.withRecipients(new Recipient(null, "recipient@example.org", TO, null))
				.withPlainText("Serialized body")
				.withHTMLText("<p>Serialized body</p><img src=\"cid:logo.png\">")
				.withAttachment("report.txt", "attachment data".getBytes(UTF_8), "text/plain")
				.withEmbeddedImage("logo.png", "image data".getBytes(UTF_8), "image/png")
				.signWithSmime(signingConfig)
				.buildEmail();
		final Email restored = roundTrip(email);

		final Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withSMTPServer("localhost", SMTP_PORT).buildMailer();
		try {
			mailer.sendMail(restored);
		} finally {
			mailer.close();
		}

		final MimeMessage receivedMessage = SMTP_SERVER.getOnlyMessage("recipient@example.org");
		final Email received = EmailConverter.mimeMessageToEmailBuilder(receivedMessage, loadPkcs12KeyStore()).buildEmail();

		assertThat(received.getOriginalSmimeDetails().getSmimeSignatureValid())
				.as("received S/MIME details: %s; verification=%s; failure=%s",
						received.getOriginalSmimeDetails(),
						received.getOriginalSmimeDetails().getVerificationStatus(),
						received.getOriginalSmimeDetails().getFailureReason())
				.isTrue();
		assertThat(received.getSubject()).isEqualTo("Fwd: Forwarded subject");
		assertThat(received.getPlainText()).isEqualTo("Serialized body");
		assertThat(received.getHTMLText()).isEqualTo("<p>Serialized body</p><img src=\"cid:logo.png\">");
		assertThat(findResource(received, "report.txt").readAllData()).isEqualTo("attachment data");
		assertThat(findEmbeddedImage(received, "logo.png").readAllData()).isEqualTo("image data");
		assertThat(findResource(received, "ForwardedMessage.eml").readAllData())
				.contains("Forwarded subject")
				.contains("Forwarded body");
	}

	private static AttachmentResource findResource(Email email, String name) {
		return email.getAttachments().stream()
				.filter(resource -> name.equals(resource.getName()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Attachment not found: " + name));
	}

	private static AttachmentResource findEmbeddedImage(Email email, String name) {
		return email.getEmbeddedImages().stream()
				.filter(resource -> name.equals(resource.getName()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Embedded image not found: " + name));
	}

	private static Email roundTrip(Email email) throws Exception {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream outputStream = new ObjectOutputStream(bytes)) {
			outputStream.writeObject(email);
		}
		try (ObjectInputStream inputStream = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			return (Email) inputStream.readObject();
		}
	}
}
