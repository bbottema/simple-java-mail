package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.clisupport.CliExecutionEnvironment;
import org.simplejavamail.internal.clisupport.CliExecutionResult;
import org.simplejavamail.internal.clisupport.CliSupport;
import org.simplejavamail.internal.clisupport.MailerProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArgumentFileInlineCommentTest {

	@TempDir
	Path directory;

	@ParameterizedTest
	@MethodSource("lineEndingsAndValues")
	void inlineCommentsFinishThePrecedingValue(final String lineEnding, final String value, final String expected) throws Exception {
		assertThat(expand("--email:withSubject " + value + "# comment" + lineEnding + "--email:withPlainText \"Body\""))
				.containsExactly("--email:withSubject", expected, "--email:withPlainText", "Body");
	}

	private static Stream<Arguments> lineEndingsAndValues() {
		return Stream.of("\n", "\r", "\r\n").flatMap(lineEnding -> Stream.of(
				Arguments.of(lineEnding, "Hello", "Hello"),
				Arguments.of(lineEnding, "\"Hello\"", "Hello"),
				Arguments.of(lineEnding, "'Hello'", "Hello"),
				Arguments.of(lineEnding, "\"\"", ""),
				Arguments.of(lineEnding, "''", "")));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void inlineCommentsKeepSeparateBuilderCallsInLocalAndRoutedRequests(final boolean routed) throws Exception {
		Files.writeString(directory.resolve("arguments.txt"),
				"--email:withSubject \"Hello\"# comment\n--email:withPlainText \"Body\"");
		final String[] arguments = {"send", "--email:startingBlank", "--email:from", "sender@example.test",
				"--email:to", "receiver@example.test", "--mailer:withSMTPServer", "smtp.example.test", "25", "@arguments.txt"};
		final Mailer mailer = mock(Mailer.class);
		final Mailer.Sync sync = mock(Mailer.Sync.class);
		final MailerProvider.Lease lease = mock(MailerProvider.Lease.class);
		when(mailer.sync()).thenReturn(sync);
		when(lease.mailer()).thenReturn(mailer);
		try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), (profile, factory) -> lease)) {
			final CliExecutionResult result;
			if (routed) {
				final String[] routedArguments = Stream.concat(Stream.of("--daemon=require"), Stream.of(arguments)).toArray(String[]::new);
				final DaemonBootstrapRequest request = DaemonBootstrapRequest.parse(routedArguments, directory);
				assertThat(request.route()).isEqualTo(DaemonBootstrapRequest.Route.DAEMON_COMMAND);
				result = CliSupport.executeExpandedArguments(request.commandArguments(), directory, UUID.randomUUID(), environment, null);
			} else {
				result = CliSupport.execute(arguments, directory, UUID.randomUUID(), environment, null);
			}
			assertThat(result.exitCode()).as(result.stderr()).isZero();
			final ArgumentCaptor<Email> email = ArgumentCaptor.forClass(Email.class);
			verify(sync).sendMail(email.capture());
			assertThat(email.getValue().getSubject()).isEqualTo("Hello");
			assertThat(email.getValue().getPlainText()).isEqualTo("Body");
			verify(lease).close();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"\"\"", "''", "\"Hello\""})
	void trailingCommentKeepsTheFinalValue(final String value) throws Exception {
		assertThat(expand("--email:withSubject " + value + "# final comment"))
				.containsExactly("--email:withSubject", "\"Hello\"".equals(value) ? "Hello" : "");
	}

	@Test
	void quotedHashesAndEscapedQuotesRemainPartOfTheirValues() throws Exception {
		assertThat(expand("--email:withSubject \"Hello# \\\"world\\\"\"# comment\n"
				+ "--email:withPlainText 'Body# \\'text\\''# comment\n"
				+ "--email:withAttachment C:\\\\mail\\\\logo.png"))
				.containsExactly("--email:withSubject", "Hello# \"world\"", "--email:withPlainText", "Body# 'text'",
						"--email:withAttachment", "C:\\mail\\logo.png");
	}

	@Test
	void commentOnlyLinesAndWhitespaceDoNotCreateArguments() throws Exception {
		assertThat(expand("# opening comment\n--email:withSubject Hello # spaced comment\n"
				+ "# middle comment\n\n\t--email:withPlainText Body# closing comment\n"))
				.containsExactly("--email:withSubject", "Hello", "--email:withPlainText", "Body");
	}

	@Test
	void simplifiedModeRetainsAnInlineHashWithinItsArgumentLine() throws Exception {
		final String previous = System.getProperty("picocli.useSimplifiedAtFiles");
		try {
			System.setProperty("picocli.useSimplifiedAtFiles", "true");
			assertThat(expand("# comment\nsubject# literal text\n\nbody with spaces\n"))
					.containsExactly("subject# literal text", "body with spaces");
		} finally {
			if (previous == null) {
				System.clearProperty("picocli.useSimplifiedAtFiles");
			} else {
				System.setProperty("picocli.useSimplifiedAtFiles", previous);
			}
		}
	}

	private String[] expand(final String content) throws Exception {
		Files.writeString(directory.resolve("arguments.txt"), content);
		return ArgumentFileExpander.expand(new String[] { "@arguments.txt" }, directory);
	}
}
