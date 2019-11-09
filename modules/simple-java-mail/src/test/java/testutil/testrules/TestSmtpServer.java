package testutil.testrules;

import org.jetbrains.annotations.NotNull;

public class TestSmtpServer implements SmtpServerSupport {
	
	private final String host;
	private final Integer port;
	
	public TestSmtpServer(final String host, final Integer port) {
		this.host = host;
		this.port = port;
	}
	
	@NotNull
	@Override
	public String getHostname() {
		return host;
	}

	@Override
	public int getPort() {
		return port;
	}
}