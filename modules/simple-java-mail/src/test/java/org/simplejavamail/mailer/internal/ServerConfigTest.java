package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.config.ServerConfig;
import testutil.ConfigLoaderTestHelper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class ServerConfigTest {

	@Test
	public void NoArgconstructor_WithoutConfigFile_MissingPasswordOrUsername() {
		ConfigLoaderTestHelper.clearConfigProperties();
		ServerConfig serverConfig = new ServerConfigImpl("host", 1234, "username", null, null, null);
		verifyServerConfig(serverConfig, "host", 1234, "username", null, null, null);

		try {
			new ServerConfigImpl("host", 1234, null, "password", null, null);
			fail("IllegalArgumentException expected for username");
		} catch (IllegalArgumentException e) {
			assertThat(e.getMessage()).containsIgnoringCase("username");
		}
	}

	@Test
	public void NoArgconstructor_WithoutConfigFile_Authenticated() {
		ConfigLoaderTestHelper.clearConfigProperties();
		ServerConfig serverConfig = new ServerConfigImpl("host", 1234, "username", "password", null, null);
		verifyServerConfig(serverConfig, "host", 1234, "username", "password", null, null);
	}

	@Test
	public void testToString() {
		ConfigLoaderTestHelper.clearConfigProperties();
		ServerConfig serverConfig = new ServerConfigImpl("host", 1234, null, null, null, null);
		assertThat(serverConfig.toString()).isEqualTo("host:1234");
		serverConfig = new ServerConfigImpl("host", 1234, "username", null, null, null);
		assertThat(serverConfig.toString()).isEqualTo("host:1234, username: username");
		serverConfig = new ServerConfigImpl("host", 1234, "username", "password", null, null);
		assertThat(serverConfig.toString()).isEqualTo("host:1234, username: username (authenticated)");
	}

	@SuppressWarnings("SameParameterValue")
	private void verifyServerConfig(ServerConfig serverConfig, @Nullable String host, @Nullable Integer port, @Nullable String username, @Nullable String password, Object customSSLFactoryClass,
			Object customSSLFactoryInstance) {
		assertThat(serverConfig.getHost()).isEqualTo(host);
		assertThat(serverConfig.getPort()).isEqualTo(port);
		assertThat(serverConfig.getUsername()).isEqualTo(username);
		assertThat(serverConfig.getPassword()).isEqualTo(password);
		assertThat(serverConfig.getCustomSSLFactoryClass()).isEqualTo(customSSLFactoryClass);
		assertThat(serverConfig.getCustomSSLFactoryInstance()).isEqualTo(customSSLFactoryInstance);
	}
}