package org.simplejavamail.converter;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.converter.ConfiguredEmailConverter;
import org.simplejavamail.api.converter.ConfiguredEmailConverter.EmlConversion;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.util.JakartaMailImplementation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FluentEmlPreservationTest {

	private static final String SIGNED_EML = "From: sender@example.org\r\nSubject: Protected source\r\nMIME-Version: 1.0\r\n"
			+ "Content-Type: multipart/signed; boundary=boundary; protocol=\"application/pgp-signature\"\r\n\r\n"
			+ "--boundary\r\nContent-Type: text/plain\r\n\r\nSigned content\r\n--boundary\r\n"
			+ "Content-Type: application/pgp-signature\r\n\r\noriginal signature bytes\r\n--boundary--\r\n";
	private final ConfiguredEmailConverter converter = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).converter();

	@ParameterizedTest(name = "{0}, {1}, builder={2}")
	@MethodSource("protectedImports")
	void retainsOriginalProtectedBytesForEveryEmlImport(final LineEndings endings, final Source source,
			final boolean builderTarget, @TempDir final Path directory) throws Exception {
		final byte[] original = endings.apply(SIGNED_EML).getBytes(UTF_8);
		final EmlConversion selected = source.select(converter, original, directory);
		final Email imported = builderTarget ? selected.toEmailBuilder().buildEmail() : selected.toEmail();

		assertThat(imported.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).isEqualTo(original);
		assertThat(EmailConverter.emlToEmail(new ByteArrayInputStream(original)).getOriginalOpenPgpDetails().getOriginalProtectedMessage())
				.as("released static conversion retains the same original bytes").isEqualTo(original);
		assertThatThrownBy(selected::toEmail).hasMessageContaining("already been used");
	}

	private static Stream<Arguments> protectedImports() {
		return Arrays.stream(LineEndings.values()).flatMap(endings -> Arrays.stream(Source.values())
				.flatMap(source -> Stream.of(Arguments.of(endings, source, false), Arguments.of(endings, source, true))));
	}

	@Test
	void emailImportReadsTheSelectedStreamOnlyAtTheTerminalAndLeavesItOpen() {
		final TrackingInput input = new TrackingInput(LineEndings.MIXED.apply(SIGNED_EML).getBytes(UTF_8));
		final EmlConversion selected = converter.fromEml(input).withFetchAttachmentData(false);
		assertThat(input.available()).isEqualTo(input.initialLength);
		selected.toEmail();
		assertThat(input.available()).isZero();
		assertThat(input.closed).isFalse();
		assertThatThrownBy(selected::toEmail).hasMessageContaining("already been used");
	}

	@Test
	void directMimeTargetKeepsJakartaMailParsingAndTheSelectedSession() throws Exception {
		final byte[] original = LineEndings.MIXED.apply(SIGNED_EML).getBytes(UTF_8);
		final Session session = JakartaMailImplementation.createSession(new Properties());
		final MimeMessage actual = converter.fromEml(original).withSession(session).toMimeMessage();
		final MimeMessage existing = EmailConverter.emlToMimeMessage(new ByteArrayInputStream(original), session);
		assertThat(actual.getSession()).isSameAs(session);
		assertThat(EmailConverter.mimeMessageToEMLByteArray(actual)).isEqualTo(EmailConverter.mimeMessageToEMLByteArray(existing));
	}

	private enum LineEndings {
		CRLF, LF, MIXED;

		String apply(final String eml) {
			switch (this) {
				case LF:
					return eml.replace("\r\n", "\n");
				case MIXED:
					return eml.replace("From: sender@example.org\r\n", "From: sender@example.org\n");
				default:
					return eml;
			}
		}
	}

	private enum Source {
		BYTES, STRING, STREAM, PATH, FILE;

		EmlConversion select(final ConfiguredEmailConverter converter, final byte[] bytes, final Path directory) throws IOException {
			switch (this) {
				case BYTES:
					return converter.fromEml(bytes);
				case STRING:
					return converter.fromEml(new String(bytes, UTF_8));
				case STREAM:
					return converter.fromEml(new ByteArrayInputStream(bytes));
				case PATH:
					return converter.fromEml(Files.write(directory.resolve("source.eml"), bytes));
				case FILE:
					return converter.fromEml(Files.write(directory.resolve("source.eml"), bytes).toFile());
				default:
					throw new AssertionError(this);
			}
		}
	}

	private static final class TrackingInput extends ByteArrayInputStream {
		private final int initialLength;
		private boolean closed;

		TrackingInput(final byte[] input) {
			super(input);
			initialLength = input.length;
		}

		@Override
		public void close() {
			closed = true;
		}
	}
}
