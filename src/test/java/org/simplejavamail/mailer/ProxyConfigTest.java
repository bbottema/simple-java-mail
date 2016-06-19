package org.simplejavamail.mailer;

import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.internal.util.ConfigLoader;
import testutil.ConfigHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.simplejavamail.mailer.ProxyConfig.DEFAULT_PROXY_BRIDGE_PORT;

public class ProxyConfigTest {

	@Before
	public void restoreOriginalStaticProperties()
			throws IOException {
		String s = "simplejavamail.proxy.host=proxy.default.com\n"
				+ "simplejavamail.proxy.port=1080\n"
				+ "simplejavamail.proxy.username=username proxy\n"
				+ "simplejavamail.proxy.password=password proxy\n"
				+ "simplejavamail.proxy.socks5bridge.port=1081\n";
		ConfigLoader.loadProperties(new ByteArrayInputStream(s.getBytes()), false);
	}

	@Test
	public void NoArgconstructor_WithoutConfigFile_WithoutHost()
			throws Exception {
		ConfigHelper.clearConfigProperties();
		ProxyConfig emptyProxyConfig = new ProxyConfig();
		verifyProxyConfig(emptyProxyConfig, null, null, null, null, DEFAULT_PROXY_BRIDGE_PORT);
		assertThat(emptyProxyConfig.requiresProxy()).isFalse();
		assertThat(emptyProxyConfig.requiresAuthentication()).isFalse();
	}

	@Test
	public void NoArgconstructor_WithoutConfigFile_WithoutPort()
			throws Exception {
		ConfigHelper.clearConfigProperties();
		try {
			new ProxyConfig("host", null);
			fail("IllegalArgumentException expected for proxy port");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("remoteProxyPort");
		}
		try {
			new ProxyConfig("host", null, null, null);
			fail("IllegalArgumentException expected for proxy port");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("remoteProxyPort");
		}
	}

	@Test
	public void NoArgconstructor_WithoutConfigFile_AnonymousLogin()
			throws Exception {
		ConfigHelper.clearConfigProperties();
		ProxyConfig proxyConfig = new ProxyConfig("host", 1234);
		ProxyConfig proxyConfigAlternative = new ProxyConfig("host", 1234, null, null);
		assertThat(proxyConfig).isEqualToComparingFieldByField(proxyConfigAlternative);
		verifyProxyConfig(proxyConfig, "host", 1234, null, null, DEFAULT_PROXY_BRIDGE_PORT);
		assertThat(proxyConfig.requiresProxy()).isTrue();
		assertThat(proxyConfig.requiresAuthentication()).isFalse();
	}

	@Test
	public void NoArgconstructor_WithoutConfigFile_MissingPasswordOrUsername()
			throws Exception {
		ConfigHelper.clearConfigProperties();

		try {
			new ProxyConfig("host", 1234, "username", null);
			fail("IllegalArgumentException expected for password");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("password");
		}
		try {
			new ProxyConfig("host", 1234, null, "password");
			fail("IllegalArgumentException expected for username");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("username");
		}
	}

	@Test
	public void NoArgconstructor_WithoutConfigFile_Authenticated()
			throws Exception {
		ConfigHelper.clearConfigProperties();
		ProxyConfig proxyConfig = new ProxyConfig("host", 1234, "username", "password");
		verifyProxyConfig(proxyConfig, "host", 1234, "username", "password", DEFAULT_PROXY_BRIDGE_PORT);
		assertThat(proxyConfig.requiresProxy()).isTrue();
		assertThat(proxyConfig.requiresAuthentication()).isTrue();
	}

	@Test
	public void NoArgconstructor_WithConfigFile_WithoutHost() {
		ProxyConfig emptyProxyConfig = new ProxyConfig();
		verifyProxyConfig(emptyProxyConfig, "proxy.default.com", 1080, "username proxy", "password proxy", 1081);
		assertThat(emptyProxyConfig.requiresProxy()).isTrue();
		assertThat(emptyProxyConfig.requiresAuthentication()).isTrue();
	}

	@Test
	public void NoArgconstructor_WithConfigFile_WithoutPort() {
		ProxyConfig emptyProxyConfig = new ProxyConfig("host", null);
		verifyProxyConfig(emptyProxyConfig, "host", 1080, "username proxy", "password proxy", 1081);
		emptyProxyConfig = new ProxyConfig("host", null, null, null);
		verifyProxyConfig(emptyProxyConfig, "host", 1080, "username proxy", "password proxy", 1081);
		assertThat(emptyProxyConfig.requiresProxy()).isTrue();
		assertThat(emptyProxyConfig.requiresAuthentication()).isTrue();
	}

	@Test
	public void NoArgconstructor_WithConfigFile_AnonymousLogin()
			throws Exception {
		ProxyConfig proxyConfig = new ProxyConfig("host", 1234);
		ProxyConfig proxyConfigAlternative = new ProxyConfig("host", 1234, null, null);
		assertThat(proxyConfig).isEqualToComparingFieldByField(proxyConfigAlternative);
		verifyProxyConfig(proxyConfig, "host", 1234, "username proxy", "password proxy", 1081);
		assertThat(proxyConfig.requiresProxy()).isTrue();
		assertThat(proxyConfig.requiresAuthentication()).isTrue();
	}

	@Test
	public void NoArgconstructor_WithConfigFile_MissingPasswordOrUsername() {
		ProxyConfig proxyConfig = new ProxyConfig("host", 1234, "username", null);
		verifyProxyConfig(proxyConfig, "host", 1234, "username", "password proxy", 1081);
		proxyConfig = new ProxyConfig("host", 1234, null, "password");
		verifyProxyConfig(proxyConfig, "host", 1234, "username proxy", "password", 1081);
		assertThat(proxyConfig.requiresProxy()).isTrue();
		assertThat(proxyConfig.requiresAuthentication()).isTrue();
	}

	@Test
	public void NoArgconstructor_WithConfigFile_Authenticated()
			throws Exception {
		ProxyConfig proxyConfig = new ProxyConfig("host", 1234, "username", "password");
		verifyProxyConfig(proxyConfig, "host", 1234, "username", "password", 1081);
		assertThat(proxyConfig.requiresProxy()).isTrue();
		assertThat(proxyConfig.requiresAuthentication()).isTrue();
	}

	@Test
	public void testToString()
			throws Exception {
		ConfigHelper.clearConfigProperties();
		ProxyConfig proxyConfig = new ProxyConfig();
		assertThat(proxyConfig.toString()).isEqualTo("no-proxy");
		proxyConfig = new ProxyConfig("host", 1234, null, null);
		assertThat(proxyConfig.toString()).isEqualTo("host:1234");
		proxyConfig = new ProxyConfig("host", 1234, "username", "password");
		assertThat(proxyConfig.toString()).isEqualTo("host:1234, username: username");
		proxyConfig.setProxyBridgePort(999);
		assertThat(proxyConfig.toString()).isEqualTo("host:1234, username: username, proxy bridge @ localhost:999");
	}

	private void verifyProxyConfig(ProxyConfig proxyConfig, String host, Integer port, String username, String password, int defaultProxyBridgePort) {
		assertThat(proxyConfig.getRemoteProxyHost()).isEqualTo(host);
		assertThat(proxyConfig.getRemoteProxyPort()).isEqualTo(port);
		assertThat(proxyConfig.getUsername()).isEqualTo(username);
		assertThat(proxyConfig.getPassword()).isEqualTo(password);
		assertThat(proxyConfig.getProxyBridgePort()).isEqualTo(defaultProxyBridgePort);
	}

}