package org.simplejavamail.converter;

import jakarta.activation.DataSource;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.converter.internal.EmailConverterException;
import org.simplejavamail.internal.util.JakartaMailImplementation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConverterExportFailureTest {

	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void explainsAttachmentReadFailureDuringEmailExport(final boolean textOutput) {
		final IOException failure = new IOException("attachment storage is offline");
		final Email email = mail.emailBuilder().startingBlank().from("sender@example.org")
				.withRecipients(mail.recipientBuilder().to(null, "reader@example.org")).withPlainText("body")
				.withAttachment("invoice.pdf", unavailableAttachment(failure)).buildEmail();

		assertThatThrownBy(() -> {
			if (textOutput) {
				mail.converter().fromEmail(email).toEml();
			} else {
				mail.converter().fromEmail(email).toEmlBytes();
			}
		}).isInstanceOf(EmailConverterException.class).hasMessageContaining("EML")
				.hasMessageContaining("content").hasRootCause(failure);
	}

	@Test
	void retainsMessagingFailureWhenSerializingMimeToEml() {
		final MessagingException failure = new MessagingException("source content cannot be serialized");
		final MimeMessage broken = new MimeMessage(JakartaMailImplementation.createSession(new Properties())) {
			@Override
			public void writeTo(final OutputStream output) throws MessagingException {
				throw failure;
			}
		};
		assertThatThrownBy(() -> EmailConverter.mimeMessageToEMLByteArray(broken))
				.isInstanceOf(EmailConverterException.class).hasMessageContaining("EML").hasCause(failure);
		assertThatThrownBy(() -> EmailConverter.mimeMessageToEML(broken))
				.isInstanceOf(EmailConverterException.class).hasMessageContaining("EML").hasCause(failure);
	}

	private static DataSource unavailableAttachment(final IOException failure) {
		return new DataSource() {
			@Override
			public InputStream getInputStream() throws IOException {
				throw failure;
			}

			@Override
			public OutputStream getOutputStream() {
				throw new UnsupportedOperationException();
			}

			@Override
			public String getContentType() {
				return "application/pdf";
			}

			@Override
			public String getName() {
				return "invoice.pdf";
			}
		};
	}
}
