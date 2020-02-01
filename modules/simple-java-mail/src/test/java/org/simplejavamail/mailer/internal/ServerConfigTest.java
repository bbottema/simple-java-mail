package org.simplejavamail.mailer.internal;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.simplejavamail.api.mailer.config.ServerConfig;
import org.simplejavamail.config.ConfigLoader;
import testutil.ConfigLoaderTestHelper;

import org.jetbrains.annotations.Nullable;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class ServerConfigTest {

	@Before
	public void restoreOriginalStaticProperties() {
		String s = "simplejavamail.smtp.host=smtp.default.com\n"
				+ "simplejavamail.smtp.port=25\n"
				+ "simplejavamail.smtp.username=username smtp\n"
				+ "simplejavamail.smtp.password=password smtp";
		ConfigLoader.loadProperties(new ByteArrayInputStream(s.getBytes()), false);
	}

	@Test
	@Ignore("Enable once the notnull plugin is enabled again or substituted")
	public void NoArgconstructor_WithoutConfigFile_WithoutHost() {
		ConfigLoaderTestHelper.clearConfigProperties();
		
		try {
			new ServerConfigImpl(null, null, null, null);
			fail("IllegalArgumentException expected for host");
		} catch (IllegalArgumentException e) {
			// ok
		}
	}

	@Test
	@Ignore("Enable once the notnull plugin is enabled again or substituted")
	public void NoArgconstructor_WithoutConfigFile_WithoutPort() {
		ConfigLoaderTestHelper.clearConfigProperties();
		try {
			new ServerConfigImpl("host", null, null, null);
			fail("IllegalArgumentException expected for port");
		} catch (IllegalArgumentException e) {
			// ok
		}
	}

	@Test
	public void NoArgconstructor_WithoutConfigFile_MissingPasswordOrUsername() {
		ConfigLoaderTestHelper.clearConfigProperties();
		ServerConfig serverConfig = new ServerConfigImpl("host", 1234, "username", null);
		verifyServerConfig(serverConfig, "host", 1234, "username", null);

		try {
			new ServerConfigImpl("host", 1234, null, "password");
			fail("IllegalArgumentException expected for username");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("username");
		}
	}

	@Test
	public void NoArgconstructor_WithoutConfigFile_Authenticated() {
		ConfigLoaderTestHelper.clearConfigProperties();
		ServerConfig serverConfig = new ServerConfigImpl("host", 1234, "username", "password");
		verifyServerConfig(serverConfig, "host", 1234, "username", "password");
	}

	@Test
	public void testToString() {
		ConfigLoaderTestHelper.clearConfigProperties();
		ServerConfig serverConfig = new ServerConfigImpl("host", 1234, null, null);
		assertThat(serverConfig.toString()).isEqualTo("host:1234");
		serverConfig = new ServerConfigImpl("host", 1234, "username", null);
		assertThat(serverConfig.toString()).isEqualTo("host:1234, username: username");
		serverConfig = new ServerConfigImpl("host", 1234, "username", "password");
		assertThat(serverConfig.toString()).isEqualTo("host:1234, username: username (authenticated)");
	}

	@SuppressWarnings("SameParameterValue")
	private void verifyServerConfig(ServerConfig serverConfig, @Nullable String host, @Nullable Integer port, @Nullable String username, @Nullable String password) {
		assertThat(serverConfig.getHost()).isEqualTo(host);
		assertThat(serverConfig.getPort()).isEqualTo(port);
		assertThat(serverConfig.getUsername()).isEqualTo(username);
		assertThat(serverConfig.getPassword()).isEqualTo(password);
	}
}