package testutil.testrules;

import org.simplejavamail.mailer.config.ServerConfig;

import javax.annotation.Nonnull;

public class TestSmtpServer implements SmtpServerSupport {
	private final ServerConfig serverConfig;

	public TestSmtpServer(final ServerConfig serverConfig) {
		this.serverConfig = serverConfig;
	}

	@Override
	public int getPort() {
		return serverConfig.getPort();
	}

	@Nonnull
	@Override
	public String getHostname() {
		return serverConfig.getHost();
	}
}