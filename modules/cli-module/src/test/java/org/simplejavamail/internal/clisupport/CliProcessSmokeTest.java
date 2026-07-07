package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class CliProcessSmokeTest {

	@Test
	public void validateCommandTerminatesCleanlyWithBatchModuleOnClasspath()
			throws Exception {
		final File cliOutput = File.createTempFile("simple-java-mail-cli-smoke-", ".log");
		try {
			final Process process = new ProcessBuilder(cliCommand(
					"validate",
					"--email:startingBlank",
					"--email:from", "sender@example.com",
					"--email:withSubject", "Smoke",
					"--email:withPlainText", "Body",
					"--email:withRecipients", "Team", "false", "TO", "Alice <alice@example.com>;bob@example.com",
					"--mailer:withSMTPServer", "localhost", "25"))
					.redirectErrorStream(true)
					.redirectOutput(cliOutput)
					.start();

			final boolean exited = process.waitFor(15, TimeUnit.SECONDS);
			if (!exited) {
				process.destroyForcibly();
			}
			final String output = readFile(cliOutput);
			assertThat(exited).as(output).isTrue();
			assertThat(process.exitValue()).as(output).isEqualTo(0);
		} finally {
			//noinspection ResultOfMethodCallIgnored
			cliOutput.delete();
		}
	}

	private static List<String> cliCommand(String... args) {
		final List<String> command = new ArrayList<>();
		command.add(resolveJavaExecutable());
		command.add("-cp");
		command.add(System.getProperty("java.class.path"));
		command.add("org.simplejavamail.cli.SimpleJavaMail");
		for (String arg : args) {
			command.add(arg);
		}
		return command;
	}

	private static String resolveJavaExecutable() {
		final String javaHome = System.getProperty("java.home");
		final String executableName = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
		return new File(new File(javaHome, "bin"), executableName).getAbsolutePath();
	}

	private static String readFile(File file)
			throws IOException {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}
}
