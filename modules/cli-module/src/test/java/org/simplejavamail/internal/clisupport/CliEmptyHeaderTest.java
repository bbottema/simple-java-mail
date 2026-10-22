package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.config.ConfigLoader;
import org.subethamail.smtp.server.SMTPServer;
import org.subethamail.wiser.Wiser;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CliEmptyHeaderTest {

	@TempDir Path workingDirectory;

	@Test
	void omittedOptionalHeaderValuesProducePresentEmptyFieldsDuringSending() {
		final Wiser smtpServer = Wiser.create(SMTPServer.port(0).insertReceivedHeaders(false));
		smtpServer.start();
		try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), new OneShotMailerProvider())) {
			final CliExecutionResult result = CliSupport.execute(new String[] {
					"send", "--email:startingBlank", "--email:from", "sender@example.org", "--email:to", "reader@example.org",
					"--email:withHeader", "X-Example", "--email:withHeader", "X-Explicit-Empty", "",
					"--email:withPlainText", "body", "--mailer:withSMTPServer", "localhost", String.valueOf(smtpServer.getServer().getPortAllocated())
			}, workingDirectory, UUID.randomUUID(), environment, null);

			assertThat(result.exitCode()).as(result.stderr()).isZero();
			assertThat(smtpServer.getMessages()).singleElement().satisfies(received -> {
				assertThat(received.getMimeMessage().getHeader("X-Example")).containsExactly("");
				assertThat(received.getMimeMessage().getHeader("X-Explicit-Empty")).containsExactly("");
			});
		} finally {
			smtpServer.stop();
		}
	}
}
