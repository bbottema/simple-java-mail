package org.simplejavamail.mailer.internal;

import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.mailer.MailerBuilder;
import org.simplejavamail.config.ConfigLoader;
import testutil.ConfigLoaderTestHelper;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class MailerBuilderProxyConfigTest {

	@Before
	public void restoreOriginalStaticProperties() {
		String s = "simplejavamail.proxy.host=proxy.default.com\n"
				+ "simplejavamail.proxy.port=1080\n"
				+ "simplejavamail.proxy.username=username proxy\n"
				+ "simplejavamail.proxy.password=password proxy\n"
				+ "simplejavamail.proxy.socks5bridge.port=1081\n";
		ConfigLoader.loadProperties(new ByteArrayInputStream(s.getBytes()), false);
	}

	@Test
	@SuppressWarnings("deprecation")
	public void NoArgconstructor_WithoutConfigFile_WithoutHost() {
		ConfigLoaderTestHelper.clearConfigProperties();
		ProxyConfig emptyProxyConfig = new ProxyConfigImpl(null, null, null, null, -1);
		verifyProxyConfig(emptyProxyConfig, null, null, null, null, -1);
		assertThat(emptyProxyConfig.requiresProxy()).isFalse();
		assertThat(emptyProxyConfig.requiresAuthentication()).isFalse();
	}

	@Test
	public void NoArgconstructor_WithoutConfigFile_WithoutPort() {
		ConfigLoaderTestHelper.clearConfigProperties();
		try {
			MailerBuilder
					.withSMTPServerHost("host")
					.withSMTPServerPort(1234)
					.withProxy("host", null)
					.buildMailer();
			fail("IllegalArgumentException expected for proxy port");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("proxyHost provided, but not a proxyPort");
		}
		try {
			MailerBuilder
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
		ConfigLoaderTestHelper.clearConfigProperties();

		try {
			MailerBuilder.withSMTPServerHost("host")
					.withSMTPServerPort(123)
					.withProxy("host", 1234, "username", null)
					.buildMailer();
			fail("IllegalArgumentException expected for password");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("password");
		}
		try {
			MailerBuilder
					.withSMTPServerHost("host")
					.withSMTPServerPort(1234)
					.withProxy("host", 1234, null, "password")
					.buildMailer();
			fail("IllegalArgumentException expected for username");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("username");
		}
	}

	private void verifyProxyConfig(ProxyConfig proxyConfig,
								   @Nullable String host,
								   @Nullable Integer port,
								   @Nullable String username,
								   @Nullable String password,
								   int defaultProxyBridgePort) {
		assertThat(proxyConfig.getRemoteProxyHost()).isEqualTo(host);
		assertThat(proxyConfig.getRemoteProxyPort()).isEqualTo(port);
		assertThat(proxyConfig.getUsername()).isEqualTo(username);
		assertThat(proxyConfig.getPassword()).isEqualTo(password);
		assertThat(proxyConfig.getProxyBridgePort()).isEqualTo(defaultProxyBridgePort);
	}
}