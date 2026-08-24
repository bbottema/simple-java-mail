package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.SimpleJavaMail;
import testutil.ConfigLoaderTestHelper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class MailerBuilderProxyConfigTest {

	@Test
	public void authenticatedProxyUsesAutomaticBridgePortByDefault() throws Exception {
		try (org.simplejavamail.api.mailer.Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
				.withSMTPServer("smtp.example.com", 25)
				.withProxy("proxy.example.com", 1080, "username", "password")
				.buildMailer()) {
			assertThat(mailer.getProxyConfig().getProxyBridgePort()).isZero();
			assertThat(mailer.getProxyConfig().toString()).contains("proxy bridge @ loopback:automatic");
			assertThat(mailer.getSession().getProperty("mail.smtp.socks.port")).isEqualTo("0");
		}
	}

	@Test
	public void NoArgconstructor_WithoutConfigFile_WithoutHost() {
		ProxyConfig emptyProxyConfig = new ProxyConfigImpl(null, null, null, null, null);
		verifyProxyConfig(emptyProxyConfig, null, null, null, null, null);
		assertThat(emptyProxyConfig.requiresProxy()).isFalse();
		assertThat(emptyProxyConfig.requiresAuthentication()).isFalse();
	}

	@Test
	public void NoArgconstructor_WithoutConfigFile_WithoutPort() {
		try {
			SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
					.withSMTPServerHost("host")
					.withSMTPServerPort(1234)
					.withProxy("host", null)
					.buildMailer();
			fail("IllegalArgumentException expected for proxy port");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("proxyHost provided, but not a proxyPort");
		}
		try {
			SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
					.withSMTPServerHost("host")
					.withSMTPServerPort(1234)
					.withProxy("host", null, null, null)
					.buildMailer();
			fail("IllegalArgumentException expected for proxy port");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("proxyHost provided, but not a proxyPort");
		}
	}

	@Test
	public void NoArgconstructor_WithoutConfigFile_MissingPasswordOrUsername() {

		try {
			SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withSMTPServerHost("host")
					.withSMTPServerPort(123)
					.withProxy("host", 1234, "username", null)
					.buildMailer();
			fail("IllegalArgumentException expected for password");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("password");
		}
		try {
			SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
					.withSMTPServerHost("host")
					.withSMTPServerPort(1234)
					.withProxy("host", 1234, null, "password")
					.buildMailer();
			fail("IllegalArgumentException expected for username");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("username");
		}
	}

	@SuppressWarnings("SameParameterValue")
	private void verifyProxyConfig(ProxyConfig proxyConfig,
								   @Nullable String host,
								   @Nullable Integer port,
								   @Nullable String username,
								   @Nullable String password,
								   @Nullable Integer defaultProxyBridgePort) {
		assertThat(proxyConfig.getRemoteProxyHost()).isEqualTo(host);
		assertThat(proxyConfig.getRemoteProxyPort()).isEqualTo(port);
		assertThat(proxyConfig.getUsername()).isEqualTo(username);
		assertThat(proxyConfig.getPassword()).isEqualTo(password);
		assertThat(proxyConfig.getProxyBridgePort()).isEqualTo(defaultProxyBridgePort);
	}
}
