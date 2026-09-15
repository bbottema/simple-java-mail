package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.config.ConfigLoader;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliTlsConfigurationTest {

	@ParameterizedTest
	@ValueSource(strings = {"send", "connect", "probe"})
	void loadedTlsConflictFailsBeforeAnySmtpConnection(final String command) throws Exception {
		try (ServerSocket endpoint = new ServerSocket(0, 1, InetAddress.getByName("localhost"));
			 CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().withMap(Map.of(
					 "simplejavamail.smtp.host", "localhost", "simplejavamail.smtp.port", endpoint.getLocalPort(),
					 "simplejavamail.smtp.username", "test-user", "simplejavamail.smtp.password", "fake-password",
					 ConfigLoader.Property.DEFAULT_SESSION_TIMEOUT_MILLIS.key(), 1000,
					 "simplejavamail.transportstrategy", "SMTP_TLS",
					 "simplejavamail.extraproperties.mail.smtp.starttls.required", "false")).load(), new OneShotMailerProvider())) {
			final String[] arguments = command.equals("send")
					? new String[]{command, "--email:startingBlank", "--email:from", "sender@example.org", "--email:to", "receiver@example.org"}
					: new String[]{command};
			final CliExecutionResult result = CliSupport.execute(arguments, Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
			assertThat(result.exitCode()).as(result.stderr()).isEqualTo(CliExitCode.COMMAND_FAILED.code());
			assertThat(result.stdout()).isEmpty();
			assertThat(result.stderr()).contains("SMTP_TLS requires STARTTLS", "Remove that override", "TransportStrategy.SMTP")
					.doesNotContain("test-user", "fake-password");
			endpoint.setSoTimeout(250);
			assertThatThrownBy(() -> {
				try (Socket unexpected = endpoint.accept()) {
					throw new AssertionError("Configuration rejection must precede any SMTP connection");
				}
			}).isInstanceOf(SocketTimeoutException.class);
		}
	}
}
