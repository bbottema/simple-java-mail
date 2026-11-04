package org.simplejavamail.converter;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import testutil.ConfigLoaderTestHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static demo.ResourceFolderHelper.determineResourceFolder;

class InputAcquisitionInspectionTest {

	private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

	enum Route { FLUENT_EML, STATIC_EML, FLUENT_MSG, STATIC_MSG }

	@ParameterizedTest
	@EnumSource(Route.class)
	void knownInputReadFailuresRemainInspectable(final Route route) {
		final InputStream input = new InputStream() {
			@Override public int read() throws IOException { throw new IOException("Reader unavailable"); }
		};
		final Email email;
		switch (route) {
			case FLUENT_EML: email = MAIL.converter().fromEml(input).toEmail(); break;
			case STATIC_EML: email = EmailConverter.emlToEmail(input); break;
			case FLUENT_MSG: email = MAIL.converter().fromOutlookMsg(input).toEmail(); break;
			default: email = EmailConverter.outlookMsgToEmail(input); break;
		}
		assertThat(email).isNotNull();
		assertThat(email.getProblems()).isNotEmpty();
	}

	@ParameterizedTest
	@EnumSource(Route.class)
	void aPartialInputRetainsItsPrefixWithoutChangingCallerClosingOwnership(final Route route) throws Exception {
		final byte[] prefix = "Message-ID: <partial@example.org>\r\nFrom: sender@example.org\r\n".getBytes(US_ASCII);
		final PrefixInput input = new PrefixInput(prefix);
		final Email email = read(route, input);
		assertThat(input.closed).isEqualTo(route == Route.STATIC_MSG ? 1 : 0);
		assertThat(email.getPlainText()).isNull();
		assertThat(email.getAttachments()).isEmpty();
		final EmailSourceContent captured = email.getSourceData().getParts().get(0).getContent();
		assertThat(captured).isNotNull();
		assertThat(captured.isFullyCaptured()).isFalse();
		assertThat(captured.getRepresentation()).isEqualTo(EmailSourceContent.Representation.SOURCE_BYTES);
		assertThat(captured.readAllBytes()).containsExactly(prefix);
		assertThat(email.getProblems()).anySatisfy(problem -> {
			assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONTENT_READ_FAILED);
			assertThat(problem.getSources()).singleElement().isSameAs(email.getSourceData().getParts().get(0));
			assertThat(problem.getOrigin()).isSameAs(email.getSourceData().getOrigin());
		});
		final int reads = input.reads;
		try (InputStream stored = captured.openStream()) { assertThat(stored.readAllBytes()).containsExactly(prefix); }
		final Email copy = MAIL.emailBuilder().copying(email).withSubject("Edited").buildEmail();
		assertThat(copy.getSourceData()).isSameAs(email.getSourceData());
		assertThat(copy.getProblems()).containsExactlyElementsOf(email.getProblems());
		assertThat(input.reads).isEqualTo(reads);
	}

	@Test
	void unavailablePathsStillProduceAnInspectionValue(@TempDir final Path directory) {
		final Path missing = directory.resolve("missing");
		for (Email email : Arrays.asList(MAIL.converter().fromEml(missing).toEmail(), EmailConverter.emlToEmail(missing.toFile()),
				MAIL.converter().fromOutlookMsg(missing).toEmail())) {
			assertThat(email).isNotNull();
			assertThat(email.getProblems()).isNotEmpty();
			assertThat(email.getSourceData().getParts()).singleElement().satisfies(part -> assertThat(part.getContent()).isNull());
		}
	}

	@Test
	void recognizedOutlookFormatFailuresKeepTheCompleteInput() {
		final byte[] zip = new byte[512];
		zip[0] = 'P'; zip[1] = 'K'; zip[2] = 3; zip[3] = 4;
		for (byte[] bytes : Arrays.asList(new byte[0], zip, "not an Outlook document".getBytes(US_ASCII))) {
			final Email email = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(bytes)).toEmail();
			assertThat(email.getProblems()).anySatisfy(problem -> assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.FIELD_NOT_INTERPRETED));
			final EmailSourceContent content = email.getSourceData().getParts().get(0).getContent();
			assertThat(content.isFullyCaptured()).isTrue();
			assertThat(content.readAllBytes()).containsExactly(bytes);
		}
	}

	@ParameterizedTest
	@EnumSource(Route.class)
	void unrelatedInputRuntimeFailuresStillPropagate(final Route route) {
		final IllegalStateException failure = new IllegalStateException("Application reader failed");
		assertThatThrownBy(() -> read(route, new InputStream() {
			@Override public int read() { throw failure; }
		})).isSameAs(failure);
	}

	@Test
	void aCloseFailureKeepsTheSuccessfullyParsedOutlookContent() throws Exception {
		final byte[] bytes = Files.readAllBytes(Path.of(determineResourceFolder("simple-java-mail"),
				"test/resources/test-messages/simple email with TO and CC.msg"));
		final Email email = EmailConverter.outlookMsgToEmail(new ByteArrayInputStream(bytes) {
			@Override public void close() throws IOException { throw new IOException("Input close failed"); }
		});
		assertThat(email.getSubject()).isEqualTo("Test E-Mail");
		assertThat(email.getPlainText()).isNotEmpty();
		assertThat(email.getProblems()).anySatisfy(problem -> {
			assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.RESOURCE_CLOSE_FAILED);
			assertThat(problem.getMessage()).contains("Input close failed");
		});
	}

	private static Email read(final Route route, final InputStream input) {
		switch (route) {
			case FLUENT_EML: return MAIL.converter().fromEml(input).toEmail();
			case STATIC_EML: return EmailConverter.emlToEmail(input);
			case FLUENT_MSG: return MAIL.converter().fromOutlookMsg(input).toEmail();
			default: return EmailConverter.outlookMsgToEmail(input);
		}
	}

	private static final class PrefixInput extends InputStream {
		private final byte[] prefix;
		private int position;
		private int reads;
		private int closed;
		private PrefixInput(final byte[] prefix) { this.prefix = prefix; }
		@Override public int read() throws IOException {
			reads++;
			if (position == prefix.length) throw new IOException("Input read failed");
			return prefix[position++] & 0xff;
		}
		@Override public void close() { closed++; }
	}
}
