package testutil.testrules;

import javax.annotation.Nonnull;

public class TestSmtpServer implements SmtpServerSupport {
	
	private final String host;
	private final Integer port;
	
	public TestSmtpServer(final String host, final Integer port) {
		this.host = host;
		this.port = port;
	}
	
	@Nonnull
	@Override
	public String getHostname() {
		return host;
	}

	@Override
	public int getPort() {
		return port;
	}
}