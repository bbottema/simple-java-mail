package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DaemonBootstrapRequestTest {
	@Test
	void freezesApplicationRoutingContract() {
		assertThat(DaemonBootstrapRequest.parse(new String[] { "send" }, Path.of(".")))
				.extracting(DaemonBootstrapRequest::route, DaemonBootstrapRequest::mode, DaemonBootstrapRequest::instance)
				.containsExactly(DaemonBootstrapRequest.Route.LOCAL, DaemonExecutionMode.OFF, "default");

		for (String selector : new String[] { "-d", "--daemon", "--daemon=acquire" }) {
			final DaemonBootstrapRequest parsed = DaemonBootstrapRequest.parse(
					new String[] { "send", selector, "--daemon-instance=work", "--email:startingBlank" }, Path.of("."));
			assertThat(parsed.route()).isEqualTo(DaemonBootstrapRequest.Route.DAEMON_COMMAND);
			assertThat(parsed.mode()).isEqualTo(DaemonExecutionMode.ACQUIRE);
			assertThat(parsed.instance()).isEqualTo("work");
			assertThat(parsed.commandArguments()).containsExactly("send", "--email:startingBlank");
		}

		assertThat(DaemonBootstrapRequest.parse(new String[] { "--daemon=require", "validate" }, Path.of("."))).satisfies(parsed -> {
			assertThat(parsed.route()).isEqualTo(DaemonBootstrapRequest.Route.DAEMON_COMMAND);
			assertThat(parsed.mode()).isEqualTo(DaemonExecutionMode.REQUIRE);
		});
		assertThat(DaemonBootstrapRequest.parse(new String[] { "connect", "--no-daemon" }, Path.of("."))).satisfies(parsed -> {
			assertThat(parsed.route()).isEqualTo(DaemonBootstrapRequest.Route.LOCAL);
			assertThat(parsed.mode()).isEqualTo(DaemonExecutionMode.OFF);
		});
	}

	@Test
	void helpAlwaysStaysLocalAndSelectorsMayNotConflict() {
		assertThat(DaemonBootstrapRequest.parse(new String[] { "send", "-d", "--help" }, Path.of(".")).route())
				.isEqualTo(DaemonBootstrapRequest.Route.LOCAL);
		assertThatThrownBy(() -> DaemonBootstrapRequest.parse(
				new String[] { "send", "-d", "--daemon=require" }, Path.of(".")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Conflicting");
		assertThatThrownBy(() -> DaemonBootstrapRequest.parse(
				new String[] { "daemon", "status", "--daemon-instance=../other" }, Path.of(".")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void managementCommandsUseTheSameNamedInstanceSelector() {
		final DaemonBootstrapRequest request = DaemonBootstrapRequest.parse(
				new String[] { "daemon", "restart", "--daemon-instance", "personal" }, Path.of("."));
		assertThat(request.route()).isEqualTo(DaemonBootstrapRequest.Route.MANAGEMENT);
		assertThat(request.managementAction()).isEqualTo("restart");
		assertThat(request.instance()).isEqualTo("personal");
		assertThat(DaemonBootstrapRequest.parse(new String[] { "daemon", "--version" }, Path.of("."))
				.managementAction()).isEqualTo("version");
	}

	@Test
	void argumentFilesAreExpandedBeforeRouting() throws Exception {
		final Path directory = java.nio.file.Files.createTempDirectory("sjm args ");
		final Path nested = directory.resolve("nested.args");
		final Path root = directory.resolve("root.args");
		try {
			java.nio.file.Files.writeString(nested, "\"\" --email:withSubject \"hello world\" C:\\\\mail\\\\body.txt");
			java.nio.file.Files.writeString(root, "validate\n-d\n@nested.args\n");
			final DaemonBootstrapRequest request = DaemonBootstrapRequest.parse(
					new String[] { "@" + root.getFileName() }, directory);
			assertThat(request.route()).isEqualTo(DaemonBootstrapRequest.Route.DAEMON_COMMAND);
			assertThat(request.commandArguments()).containsExactly("validate", "", "--email:withSubject", "hello world",
					"C:\\mail\\body.txt");
		} finally {
			java.nio.file.Files.deleteIfExists(root);
			java.nio.file.Files.deleteIfExists(nested);
			java.nio.file.Files.deleteIfExists(directory);
		}
	}

	@Test
	void argumentFilesAreByteBoundedAndRequireValidUtf8() throws Exception {
		final Path directory = java.nio.file.Files.createTempDirectory("sjm bounded args ");
		final Path oversized = directory.resolve("oversized.args");
		final Path malformed = directory.resolve("malformed.args");
		try {
			java.nio.file.Files.write(oversized, new byte[64 * 1024 + 1]);
			java.nio.file.Files.write(malformed, new byte[] { (byte) 0xc3, 0x28 });
			assertThatThrownBy(() -> DaemonBootstrapRequest.parse(
					new String[] { "@" + oversized.getFileName() }, directory))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("exceeds");
			assertThatThrownBy(() -> DaemonBootstrapRequest.parse(
					new String[] { "@" + malformed.getFileName() }, directory))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Unable to read argument file");
		} finally {
			java.nio.file.Files.deleteIfExists(oversized);
			java.nio.file.Files.deleteIfExists(malformed);
			java.nio.file.Files.deleteIfExists(directory);
		}
	}

	@Test
	void directArgumentsUseTheSamePerArgumentByteLimitAsDaemonFrames() {
		assertThatThrownBy(() -> DaemonBootstrapRequest.parse(
				new String[] { "send", "x".repeat(64 * 1024 + 1) }, Path.of(".")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("argument exceeds");
	}

	@Test
	void aggregateDaemonRequestsAreRejectedBeforeConnecting() {
		final String[] arguments = new String[19];
		arguments[0] = "validate";
		Arrays.fill(arguments, 1, arguments.length, "x".repeat(64 * 1024));
		arguments[1] = "--daemon=require";

		assertThatThrownBy(() -> DaemonBootstrapRequest.parse(arguments, Path.of(".")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("frame is too large");
	}

	@Test
	void doubledAtSignEscapesAValueThatWouldOtherwiseNameAnArgumentFile() {
		final DaemonBootstrapRequest request = DaemonBootstrapRequest.parse(
				new String[] { "validate", "--email:withSubject", "@@literal" }, Path.of("."));

		assertThat(request.commandArguments()).containsExactly("validate", "--email:withSubject", "@literal");
	}

	@Test
	void unreadableAtFileNamesRemainLiteralLikeTheExistingPicocliContract() {
		final DaemonBootstrapRequest request = DaemonBootstrapRequest.parse(
				new String[] { "validate", "--email:withSubject", "@does-not-exist" }, Path.of("."));

		assertThat(request.commandArguments()).containsExactly("validate", "--email:withSubject", "@does-not-exist");
	}

	@Test
	void simplifiedAtFileModeStillTreatsEachNonCommentLineAsOneArgument() {
		final String previous = System.getProperty("picocli.useSimplifiedAtFiles");
		try {
			System.setProperty("picocli.useSimplifiedAtFiles", "true");
			assertThat(ArgumentFileExpander.tokenize("# comment\nsubject with spaces\n\n--daemon=require\n"))
					.containsExactly("subject with spaces", "--daemon=require");
		} finally {
			if (previous == null) {
				System.clearProperty("picocli.useSimplifiedAtFiles");
			} else {
				System.setProperty("picocli.useSimplifiedAtFiles", previous);
			}
		}
	}
}
