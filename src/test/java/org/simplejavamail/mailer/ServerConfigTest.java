package org.simplejavamail.mailer;

import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.util.ConfigLoader;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class ServerConfigTest {

	@Before
	public void restoreOriginalStaticProperties()
			throws IOException {
		String s = "simplejavamail.smtp.host=smtp.default.com\n"
				+ "simplejavamail.smtp.port=25\n"
				+ "simplejavamail.smtp.username=username smtp\n"
				+ "simplejavamail.smtp.password=password smtp";
		ConfigLoader.loadProperties(new ByteArrayInputStream(s.getBytes()), false);
	}

	@Test
	public void NoArgconstructor_WithoutConfigFile_WithoutHost()
			throws Exception {
		ConfigLoaderTestHelper.clearConfigProperties();
		try {
			new ServerConfig(null, null, null, null);
			fail("IllegalArgumentException expected for host");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("host address");
		}
	}

	@Test
	public void NoArgconstructor_WithoutConfigFile_WithoutPort()
			throws Exception {
		ConfigLoaderTestHelper.clearConfigProperties();
		try {
			new ServerConfig("host", null, null, null);
			fail("IllegalArgumentException expected for port");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("host port");
		}
	}

	@Test
	public void NoArgconstructor_WithoutConfigFile_MissingPasswordOrUsername()
			throws Exception {
		ConfigLoaderTestHelper.clearConfigProperties();
		ServerConfig serverConfig = new ServerConfig("host", 1234, "username", null);
		verifyServerConfig(serverConfig, "host", 1234, "username", null);

		try {
			new ServerConfig("host", 1234, null, "password");
			fail("IllegalArgumentException expected for username");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("username");
		}
	}

	@Test
	public void NoArgconstructor_WithoutConfigFile_Authenticated()
			throws Exception {
		ConfigLoaderTestHelper.clearConfigProperties();
		ServerConfig serverConfig = new ServerConfig("host", 1234, "username", "password");
		verifyServerConfig(serverConfig, "host", 1234, "username", "password");
	}

	@Test
	public void testToString()
			throws Exception {
		ConfigLoaderTestHelper.clearConfigProperties();
		ServerConfig serverConfig = new ServerConfig("host", 1234, null, null);
		assertThat(serverConfig.toString()).isEqualTo("host:1234");
		serverConfig = new ServerConfig("host", 1234, "username", null);
		assertThat(serverConfig.toString()).isEqualTo("host:1234, username: username");
		serverConfig = new ServerConfig("host", 1234, "username", "password");
		assertThat(serverConfig.toString()).isEqualTo("host:1234, username: username (authenticated)");
	}

	private void verifyServerConfig(ServerConfig serverConfig, String host, Integer port, String username, String password) {
		assertThat(serverConfig.getHost()).isEqualTo(host);
		assertThat(serverConfig.getPort()).isEqualTo(port);
		assertThat(serverConfig.getUsername()).isEqualTo(username);
		assertThat(serverConfig.getPassword()).isEqualTo(password);
	}
}