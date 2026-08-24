package org.simplejavamail.internal.clisupport;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Binds one immutable configuration generation to its builder facade and Mailer ownership policy.
 * One-shot commands use a close-after-command provider; the daemon keeps one environment whose provider leases
 * profile-compatible Mailers across concurrent requests.
 */
public final class CliExecutionEnvironment implements AutoCloseable {
	private final SimpleJavaMailConfig config;
	private final SimpleJavaMail simpleJavaMail;
	private final MailerProvider mailerProvider;
	private final Path configurationWorkingDirectory;

	public CliExecutionEnvironment(@NotNull final SimpleJavaMailConfig config,
			@NotNull final MailerProvider mailerProvider) {
		this(config, mailerProvider, Path.of("").toAbsolutePath());
	}

	public CliExecutionEnvironment(@NotNull final SimpleJavaMailConfig config, @NotNull final MailerProvider mailerProvider,
			@NotNull final Path configurationWorkingDirectory) {
		this.config = Objects.requireNonNull(config, "config");
		this.simpleJavaMail = SimpleJavaMail.withConfig(config);
		this.mailerProvider = Objects.requireNonNull(mailerProvider, "mailerProvider");
		this.configurationWorkingDirectory = Objects
				.requireNonNull(configurationWorkingDirectory, "configurationWorkingDirectory")
				.toAbsolutePath().normalize();
	}

	public static CliExecutionEnvironment oneShot() {
		return new CliExecutionEnvironment(loadConventionalConfig(), new OneShotMailerProvider());
	}

	public static SimpleJavaMailConfig loadConventionalConfig() {
		return ConfigLoader.builder()
				.withClasspathResource(ConfigLoader.DEFAULT_CONFIG_FILENAME)
				.withEnvironmentVariables()
				.withSystemProperties()
				.load();
	}

	public SimpleJavaMailConfig config() {
		return config;
	}

	public SimpleJavaMail simpleJavaMail() {
		return simpleJavaMail;
	}

	public MailerProvider mailerProvider() {
		return mailerProvider;
	}

	public Path configurationWorkingDirectory() {
		return configurationWorkingDirectory;
	}

	@Override
	public void close() {
		mailerProvider.close();
	}
}
