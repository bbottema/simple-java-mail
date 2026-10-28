package org.simplejavamail.converter.internal.mimemessage;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.MimeMessage;
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
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.util.JakartaMailImplementation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Properties;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.mailer.MailFailureKind.CONVERSION;

class CalendarCharsetStreamOwnershipTest {

	private static final String CALENDAR = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nMETHOD:REQUEST\r\nSUMMARY:R\u00e9union\r\nEND:VCALENDAR\r\n";
	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

	@ParameterizedTest
	@ValueSource(strings = {"X-NOT-A-CHARSET", "invalid charset", ""})
	void rejectsUnsupportedOrIllegalCharsetsBeforeOpeningContent(final String charset) throws Exception {
		final CalendarSource source = new CalendarSource(charset, Failure.NONE);
		try (MockedStatic<ModuleLoader> modules = withoutProtectionInspection()) {
			final MimeMessage incoming = message(source);
			assertThatThrownBy(() -> mail.converter().fromMimeMessage(incoming).toEmail())
					.isInstanceOfSatisfying(MailException.class, failure -> {
						assertThat(failure.getKind()).isEqualTo(CONVERSION);
						assertThat(failure.getCause()).isInstanceOf(UnsupportedEncodingException.class).hasMessage(charset);
					});
		}
		assertThat(source.opened).as("invalid charsets must not acquire content streams").isZero();
		assertThat(source.closed).isZero();
		assertThat(source.sourceClosed).isFalse();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void successfulCalendarReadsCloseTheirStreamOnce(final boolean fluent) throws Exception {
		final CalendarSource source = new CalendarSource("UTF-8", Failure.NONE);
		try (MockedStatic<ModuleLoader> modules = withoutProtectionInspection()) {
			final MimeMessage incoming = message(source);
			final Email imported = fluent ? mail.converter().fromMimeMessage(incoming).toEmail()
					: EmailConverter.mimeMessageToEmail(incoming);
			assertThat(imported.getCalendarText()).isEqualTo(CALENDAR);
		}
		assertThat(source.opened).isEqualTo(1);
		assertThat(source.closed).isEqualTo(1);
		assertThat(source.sourceClosed).isFalse();
	}

	@ParameterizedTest
	@EnumSource(value = Failure.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
	void calendarIoFailuresKeepTheirCauseAndCleanup(final Failure failurePoint) throws Exception {
		final CalendarSource source = new CalendarSource("UTF-8", failurePoint);
		try (MockedStatic<ModuleLoader> modules = withoutProtectionInspection()) {
			final MimeMessage incoming = message(source);
			assertThatThrownBy(() -> mail.converter().fromMimeMessage(incoming).toEmail())
					.isInstanceOfSatisfying(MailException.class, failure -> {
						assertThat(failure.getKind()).isEqualTo(CONVERSION);
						assertThat(failure.getCause()).isSameAs(source.primaryFailure());
						if (failurePoint == Failure.READ_AND_CLOSE) {
							assertThat(failure.getCause().getSuppressed()).containsExactly(source.closeFailure);
						}
					});
		}
		assertThat(source.opened).isEqualTo(1);
		assertThat(source.closed).isEqualTo(failurePoint == Failure.OPEN ? 0 : 1);
		assertThat(source.sourceClosed).isFalse();
	}

	@Test
	void calendarImportLeavesTheCallerOwnedEmlStreamOpen() {
		final byte[] eml = ("From: sender@example.org\r\nTo: reader@example.org\r\n"
				+ "Content-Type: text/calendar; charset=UTF-8; method=REQUEST\r\n\r\n" + CALENDAR).getBytes(UTF_8);
		final boolean[] closed = {false};
		final InputStream supplied = new ByteArrayInputStream(eml) {
			@Override public void close() { closed[0] = true; }
		};
		try (MockedStatic<ModuleLoader> modules = withoutProtectionInspection()) {
			assertThat(mail.converter().fromEml(supplied).toEmail().getCalendarText()).isEqualTo(CALENDAR);
		}
		assertThat(closed[0]).isFalse();
	}

	private static MockedStatic<ModuleLoader> withoutProtectionInspection() {
		// Isolate calendar reads from optional protection modules that may serialize a supplied message before parsing it.
		return Mockito.mockStatic(ModuleLoader.class);
	}

	private static MimeMessage message(final CalendarSource source) throws Exception {
		final MimeMessage incoming = new MimeMessage(JakartaMailImplementation.createSession(new Properties()));
		incoming.setFrom("sender@example.org");
		incoming.setRecipients(RecipientType.TO, "reader@example.org");
		incoming.setDataHandler(new DataHandler(source));
		incoming.setHeader("Content-Type", source.getContentType());
		return incoming;
	}

	private enum Failure { NONE, OPEN, READ, CLOSE, READ_AND_CLOSE }

	private static final class CalendarSource implements DataSource, AutoCloseable {
		private final String charset;
		private final Failure failurePoint;
		private final IOException openFailure = new IOException("calendar opening failed");
		private final IOException readFailure = new IOException("calendar reading failed");
		private final IOException closeFailure = new IOException("calendar closing failed");
		private int opened;
		private int closed;
		private boolean sourceClosed;

		private CalendarSource(final String charset, final Failure failurePoint) {
			this.charset = charset;
			this.failurePoint = failurePoint;
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
				private final ByteArrayInputStream content = new ByteArrayInputStream(CALENDAR.getBytes(UTF_8));

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
		@Override public String getContentType() { return "text/calendar; charset=\"" + charset + "\"; method=REQUEST"; }
		@Override public String getName() { return "calendar.ics"; }
		@Override public void close() { sourceClosed = true; }
	}
}
