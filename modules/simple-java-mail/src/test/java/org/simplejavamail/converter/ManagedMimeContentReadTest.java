package org.simplejavamail.converter;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.util.JakartaMailImplementation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.mailer.MailFailureKind.CONVERSION;

class ManagedMimeContentReadTest {

	private static final byte[] ATTACHMENT = "attachment content".getBytes(UTF_8);
	private static final byte[] CALENDAR = "BEGIN:VCALENDAR\r\nMETHOD:REQUEST\r\nEND:VCALENDAR\r\n".getBytes(UTF_8);
	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void closesEagerAttachmentStreamsBeforeReturning(final boolean fluent) throws Exception {
		final ContentSource source = new ContentSource(ATTACHMENT, null);
		try (MockedStatic<ModuleLoader> modules = withoutProtectionInspection()) {
			final MimeMessage incoming = message(attachment(source));
			final Email converted = fluent ? mail.converter().fromMimeMessage(incoming).toEmail()
					: EmailConverter.mimeMessageToEmail(incoming);
			assertThat(source.opened).isEqualTo(1);
			assertThat(source.closed).isEqualTo(1);
			assertThat(converted.getAttachments().get(0).readAllBytes()).isEqualTo(ATTACHMENT);
			assertThat(source.opened).as("eager conversion retains copied bytes").isEqualTo(1);
			assertThat(source.sourceClosed).isFalse();
		}
	}

	@ParameterizedTest
	@EnumSource(Failure.class)
	void eagerAttachmentFailuresRetainTheirCauseAndCloseTheAcquiredStream(final Failure failurePoint) throws Exception {
		final ContentSource source = new ContentSource(ATTACHMENT, failurePoint);
		try (MockedStatic<ModuleLoader> modules = withoutProtectionInspection()) {
			final MimeMessage incoming = message(attachment(source));
			assertConversionFailure(() -> mail.converter().fromMimeMessage(incoming).toEmail(), source);
		}
		assertThat(source.opened).isEqualTo(1);
		assertThat(source.closed).isEqualTo(failurePoint == Failure.OPEN ? 0 : 1);
		assertThat(source.sourceClosed).isFalse();
	}

	@Test
	void lazyAttachmentConversionDoesNotOpenTheBorrowedContent() throws Exception {
		final ContentSource source = new ContentSource(ATTACHMENT, null);
		try (MockedStatic<ModuleLoader> modules = withoutProtectionInspection()) {
			final Email converted = mail.converter().fromMimeMessage(message(attachment(source)))
					.withFetchAttachmentData(false).toEmail();
			assertThat(source.opened).isZero();
			assertThat(source.closed).isZero();
			assertThat(converted.getAttachments().get(0).readAllBytes()).isEqualTo(ATTACHMENT);
			assertThat(source.opened).isEqualTo(1);
			assertThat(source.closed).isEqualTo(1);
			assertThat(source.sourceClosed).isFalse();
		}
	}

	@Test
	void closesTheCalendarStreamFallbackAfterReadingItsContent() throws Exception {
		final ContentSource source = new ContentSource(CALENDAR, null, "text/calendar; charset=UTF-8; method=REQUEST");
		try (MockedStatic<ModuleLoader> modules = withoutProtectionInspection()) {
			final Email converted = mail.converter().fromMimeMessage(message(calendar(source))).toEmail();
			assertThat(converted.getCalendarText()).isEqualTo(new String(CALENDAR, UTF_8));
			assertThat(source.opened).isEqualTo(1);
			assertThat(source.closed).isEqualTo(1);
			assertThat(source.sourceClosed).isFalse();
		}
	}

	@ParameterizedTest
	@EnumSource(Failure.class)
	void calendarFallbackFailuresRetainTheirCauseAndCloseTheAcquiredStream(final Failure failurePoint) throws Exception {
		final ContentSource source = new ContentSource(CALENDAR, failurePoint, "text/calendar; charset=UTF-8; method=REQUEST");
		try (MockedStatic<ModuleLoader> modules = withoutProtectionInspection()) {
			final MimeMessage incoming = message(calendar(source));
			assertConversionFailure(() -> mail.converter().fromMimeMessage(incoming).toEmail(), source);
		}
		assertThat(source.opened).isEqualTo(1);
		assertThat(source.closed).isEqualTo(failurePoint == Failure.OPEN ? 0 : 1);
		assertThat(source.sourceClosed).isFalse();
	}

	@Test
	void leavesTheCallerSuppliedTopLevelEmlStreamOpen() {
		final byte[] eml = ("From: sender@example.org\r\nTo: reader@example.org\r\n"
				+ "Content-Type: text/plain; charset=UTF-8\r\n\r\nbody\r\n").getBytes(UTF_8);
		final ContentSource source = new ContentSource(eml, null);
		try (MockedStatic<ModuleLoader> modules = withoutProtectionInspection()) {
			final InputStream callerStream = source.getInputStream();
			assertThat(mail.converter().fromEml(callerStream).toEmail().getPlainText()).isEqualTo("body\r\n");
			assertThat(source.opened).isEqualTo(1);
			assertThat(source.closed).isZero();
			assertThat(source.sourceClosed).isFalse();
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void assertConversionFailure(final ThrowingConversion conversion, final ContentSource source) {
		assertThatThrownBy(conversion::run).isInstanceOfSatisfying(MailException.class, failure -> {
			assertThat(failure.getKind()).isEqualTo(CONVERSION);
			assertThat(failure.getCause()).isSameAs(source.primaryFailure());
			assertThat(failure.getSubmissionReceipt()).isEmpty();
			if (source.failurePoint == Failure.READ_AND_CLOSE) {
				assertThat(failure.getCause().getSuppressed()).containsExactly(source.closeFailure);
			}
		});
	}

	private static MockedStatic<ModuleLoader> withoutProtectionInspection() {
		// Keep these content-read checks independent of optional cryptographic serialization and inspection.
		return Mockito.mockStatic(ModuleLoader.class);
	}

	private static MimeMessage message(final MimeBodyPart bodyPart) throws MessagingException {
		final MimeMultipart parts = new MimeMultipart();
		parts.addBodyPart(bodyPart);
		final MimeMessage message = new MimeMessage(JakartaMailImplementation.createSession(new Properties()));
		message.setFrom("sender@example.org");
		message.setRecipients(jakarta.mail.Message.RecipientType.TO, "reader@example.org");
		message.setContent(parts);
		message.setHeader("Content-Type", parts.getContentType());
		return message;
	}

	private static MimeBodyPart attachment(final ContentSource source) throws MessagingException {
		final MimeBodyPart attachment = new MimeBodyPart();
		attachment.setDataHandler(new DataHandler(source));
		attachment.setDisposition(Part.ATTACHMENT);
		attachment.setFileName("payload.bin");
		return attachment;
	}

	private static MimeBodyPart calendar(final ContentSource source) throws MessagingException {
		// Some MIME providers return an InputStream even after the local text/calendar handler is configured.
		final MimeBodyPart calendar = new MimeBodyPart() {
			@Override
			public Object getContent() throws IOException {
				return source.getInputStream();
			}
		};
		calendar.setDataHandler(new DataHandler(source));
		calendar.setHeader("Content-Type", "text/calendar; charset=UTF-8; method=REQUEST");
		return calendar;
	}

	private interface ThrowingConversion {
		void run() throws Exception;
	}

	private enum Failure { OPEN, READ, CLOSE, READ_AND_CLOSE }

	private static final class ContentSource implements DataSource, AutoCloseable {
		private final byte[] bytes;
		private final Failure failurePoint;
		private final String contentType;
		private final IOException openFailure = new IOException("content opening failed");
		private final IOException readFailure = new IOException("content reading failed");
		private final IOException closeFailure = new IOException("content closing failed");
		private int opened;
		private int closed;
		private boolean sourceClosed;

		private ContentSource(final byte[] bytes, final Failure failurePoint) {
			this(bytes, failurePoint, "application/octet-stream");
		}

		private ContentSource(final byte[] bytes, final Failure failurePoint, final String contentType) {
			this.bytes = bytes;
			this.failurePoint = failurePoint;
			this.contentType = contentType;
		}

		private IOException primaryFailure() {
			return failurePoint == Failure.OPEN ? openFailure : failurePoint == Failure.CLOSE ? closeFailure : readFailure;
		}

		@Override
		public InputStream getInputStream() throws IOException {
			opened++;
			if (failurePoint == Failure.OPEN) {
				throw openFailure;
			}
			return new InputStream() {
				private final ByteArrayInputStream content = new ByteArrayInputStream(bytes);

				@Override
				public int read() throws IOException {
					checkReadFailure();
					return content.read();
				}

				@Override
				public int read(final byte[] buffer, final int offset, final int length) throws IOException {
					checkReadFailure();
					return content.read(buffer, offset, length);
				}

				private void checkReadFailure() throws IOException {
					if (failurePoint == Failure.READ || failurePoint == Failure.READ_AND_CLOSE) {
						throw readFailure;
					}
				}

				@Override
				public void close() throws IOException {
					closed++;
					if (failurePoint == Failure.CLOSE || failurePoint == Failure.READ_AND_CLOSE) {
						throw closeFailure;
					}
				}
			};
		}

		@Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
		@Override public String getContentType() { return contentType; }
		@Override public String getName() { return "payload.bin"; }
		@Override public void close() { sourceClosed = true; }
	}
}
