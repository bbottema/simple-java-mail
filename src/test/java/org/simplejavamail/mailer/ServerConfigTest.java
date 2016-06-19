package org.simplejavamail.mailer;

import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.internal.util.ConfigLoader;
import testutil.ConfigHelper;

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
		ConfigHelper.clearConfigProperties();
		try {
			new ServerConfig();
			fail("IllegalArgumentException expected for host");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("host address");
		}
		try {
			new ServerConfig(null, null);
			fail("IllegalArgumentException expected for host");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("host address");
		}
		try {
			new ServerConfig(null, null, null);
			fail("IllegalArgumentException expected for host");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("host address");
		}
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
		ConfigHelper.clearConfigProperties();
		try {
			new ServerConfig("host", null);
			fail("IllegalArgumentException expected for port");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("host port");
		}
		try {
			new ServerConfig("host", null, null);
			fail("IllegalArgumentException expected for port");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("host port");
		}
		try {
			new ServerConfig("host", null, null, null);
			fail("IllegalArgumentException expected for port");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("host port");
		}
	}

	@Test
	public void NoArgconstructor_WithoutConfigFile_AnonymousLogin()
			throws Exception {
		ConfigHelper.clearConfigProperties();
		ServerConfig serverConfig = new ServerConfig("host", 1234);
		ServerConfig serverConfigAlternative1 = new ServerConfig("host", 1234, null);
		ServerConfig serverConfigAlternative2 = new ServerConfig("host", 1234, null, null);
		assertThat(serverConfig).isEqualToComparingFieldByField(serverConfigAlternative1).isEqualToComparingFieldByField(serverConfigAlternative2);
		verifyServerConfig(serverConfig, "host", 1234, null, null);
	}

	@Test
	public void NoArgconstructor_WithoutConfigFile_MissingPasswordOrUsername()
			throws Exception {
		ConfigHelper.clearConfigProperties();
		ServerConfig serverConfig = new ServerConfig("host", 1234, "username", null);
		ServerConfig serverConfigAlternative = new ServerConfig("host", 1234, "username");
		assertThat(serverConfig).isEqualToComparingFieldByField(serverConfigAlternative);
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
		ConfigHelper.clearConfigProperties();
		ServerConfig serverConfig = new ServerConfig("host", 1234, "username", "password");
		verifyServerConfig(serverConfig, "host", 1234, "username", "password");
	}

	@Test
	public void NoArgconstructor_WithConfigFile_WithoutHost() {
		ServerConfig emptyServerConfig = new ServerConfig();
		verifyServerConfig(emptyServerConfig, "smtp.default.com", 25, "username smtp", "password smtp");
	}

	@Test
	public void NoArgconstructor_WithConfigFile_WithoutPort() {
		ServerConfig emptyServerConfig = new ServerConfig("host", null);
		verifyServerConfig(emptyServerConfig, "host", 25, "username smtp", "password smtp");
		emptyServerConfig = new ServerConfig("host", null, null, null);
		verifyServerConfig(emptyServerConfig, "host", 25, "username smtp", "password smtp");
	}

	@Test
	public void NoArgconstructor_WithConfigFile_AnonymousLogin()
			throws Exception {
		ServerConfig serverConfig = new ServerConfig("host", 1234);
		ServerConfig serverConfigAlternative1 = new ServerConfig("host", 1234, null);
		ServerConfig serverConfigAlternative2 = new ServerConfig("host", 1234, null, null);
		assertThat(serverConfig).isEqualToComparingFieldByField(serverConfigAlternative1).isEqualToComparingFieldByField(serverConfigAlternative2);
		verifyServerConfig(serverConfig, "host", 1234, "username smtp", "password smtp");
	}

	@Test
	public void NoArgconstructor_WithConfigFile_MissingPasswordOrUsername() {
		ServerConfig serverConfig = new ServerConfig("host", 1234, "username", null);
		verifyServerConfig(serverConfig, "host", 1234, "username", "password smtp");
		serverConfig = new ServerConfig("host", 1234, null, "password");
		verifyServerConfig(serverConfig, "host", 1234, "username smtp", "password");
	}

	@Test
	public void NoArgconstructor_WithConfigFile_Authenticated()
			throws Exception {
		ServerConfig serverConfig = new ServerConfig("host", 1234, "username", "password");
		verifyServerConfig(serverConfig, "host", 1234, "username", "password");
	}

	@Test
	public void testToString()
			throws Exception {
		ConfigHelper.clearConfigProperties();
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