package org.simplejavamail.converter;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.util.JakartaMailImplementation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.mailer.MailFailureKind.CONVERSION;

class CalendarStreamCharsetTest {

	private static final String CALENDAR = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nMETHOD:REQUEST\r\nSUMMARY:R\u00e9union au caf\u00e9\r\nEND:VCALENDAR\r\n";
	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

	@ParameterizedTest
	@MethodSource("declaredCharsets")
	void readsTheDeclaredCharsetThroughBothPublicImportRoutes(final ContentPath contentPath, final boolean fluent,
			final Charset charset) throws Exception {
		final CalendarSource source = new CalendarSource(CALENDAR.getBytes(charset), charset.name());
		try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class)) {
			final Email imported = convert(message(source, contentPath), fluent);
			assertThat(imported.getCalendarText()).isEqualTo(CALENDAR);
			assertThat(imported.getCalendarMethod()).isEqualTo(CalendarMethod.REQUEST);
		}
		assertThat(source.opened).isEqualTo(1);
		assertThat(source.closed).isEqualTo(1);
	}

	private static Stream<Arguments> declaredCharsets() {
		return Arrays.stream(ContentPath.values()).flatMap(path -> Stream.of(false, true)
				.flatMap(fluent -> Stream.of(UTF_8, ISO_8859_1).map(charset -> Arguments.of(path, fluent, charset))));
	}

	@ParameterizedTest
	@MethodSource("invalidCharsets")
	void rejectsUnsupportedAndIllegalDeclarationsBeforeOpeningContent(final ContentPath contentPath,
			final boolean fluent, final String charset) throws Exception {
		final CalendarSource source = new CalendarSource(CALENDAR.getBytes(UTF_8), charset);
		try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class)) {
			final MimeMessage incoming = message(source, contentPath);
			assertThatThrownBy(() -> convert(incoming, fluent)).isInstanceOfSatisfying(MailException.class, failure -> {
				assertThat(failure.getKind()).isEqualTo(CONVERSION);
				assertThat(failure.getCause()).isInstanceOf(UnsupportedEncodingException.class).hasMessage(charset);
			});
		}
		assertThat(source.opened).as("invalid charset declarations must not acquire content streams").isZero();
		assertThat(source.closed).isZero();
	}

	private static Stream<Arguments> invalidCharsets() {
		return Arrays.stream(ContentPath.values()).flatMap(path -> Stream.of(false, true)
				.flatMap(fluent -> Stream.of("X-NOT-A-CHARSET", "invalid charset", "")
						.map(charset -> Arguments.of(path, fluent, charset))));
	}

	@ParameterizedTest
	@EnumSource(ContentPath.class)
	void retainsEachContentPathsExistingDefaultWhenTheCharsetIsAbsent(final ContentPath contentPath) throws Exception {
		final byte[] bytes = CALENDAR.getBytes(UTF_8);
		final CalendarSource source = new CalendarSource(bytes, null);
		try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class)) {
			final Email imported = convert(message(source, contentPath), true);
			assertThat(imported.getCalendarText()).isEqualTo(new String(bytes,
					contentPath == ContentPath.PROVIDER_STREAM ? UTF_8 : US_ASCII));
		}
		assertThat(source.opened).isEqualTo(1);
		assertThat(source.closed).isEqualTo(1);
	}

	private Email convert(final MimeMessage incoming, final boolean fluent) {
		return fluent ? mail.converter().fromMimeMessage(incoming).toEmail() : EmailConverter.mimeMessageToEmail(incoming);
	}

	private static MimeMessage message(final CalendarSource source, final ContentPath contentPath) throws Exception {
		final MimeMessage incoming = new MimeMessage(JakartaMailImplementation.createSession(new Properties())) {
			@Override
			public Object getContent() throws IOException, jakarta.mail.MessagingException {
				return contentPath == ContentPath.PROVIDER_STREAM ? source.getInputStream() : super.getContent();
			}
		};
		incoming.setFrom("sender@example.org");
		incoming.setRecipients(RecipientType.TO, "reader@example.org");
		incoming.setDataHandler(new DataHandler(source));
		incoming.setHeader("Content-Type", source.getContentType());
		return incoming;
	}

	private enum ContentPath { LOCAL_HANDLER, PROVIDER_STREAM }

	private static final class CalendarSource implements DataSource {
		private final byte[] bytes;
		private final String charset;
		private int opened;
		private int closed;

		private CalendarSource(final byte[] bytes, final String charset) {
			this.bytes = bytes;
			this.charset = charset;
		}

		@Override
		public InputStream getInputStream() {
			opened++;
			return new ByteArrayInputStream(bytes) {
				@Override public void close() { closed++; }
			};
		}

		@Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
		@Override public String getContentType() {
			return "text/calendar; method=REQUEST" + (charset == null ? "" : "; charset=\"" + charset + "\"");
		}
		@Override public String getName() { return "calendar.ics"; }
	}
}
