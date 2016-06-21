package org.simplejavamail.internal.util;

import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.mailer.TransportStrategy;
import org.simplejavamail.internal.util.ConfigLoader.Property;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.mailer.TransportStrategy.SMTP_SSL;
import static org.simplejavamail.internal.util.ConfigLoader.Property.*;

public class ConfigLoaderTest {

	@Before
	public void restoreOriginalStaticProperties() {
		ConfigLoader.loadProperties("simplejavamail.properties", false);
	}

	@Test
	public void valueOrProperty()
			throws Exception {
		Map<Property, Object> properties = new HashMap<>();
		properties.put(TRANSPORT_STRATEGY, "preconfiguredValue");
		ConfigLoaderTestHelper.setResolvedProperties(properties);

		assertThat(ConfigLoader.valueOrProperty("value", TRANSPORT_STRATEGY)).isEqualTo("value");
		assertThat(ConfigLoader.valueOrProperty(null, TRANSPORT_STRATEGY)).isEqualTo("preconfiguredValue");
		assertThat(ConfigLoader.valueOrProperty(null, SMTP_HOST)).isNull();
	}

	@Test
	public void valueOrPropertyDefaultValue()
			throws Exception {
		Map<Property, Object> properties = new HashMap<>();
		properties.put(TRANSPORT_STRATEGY, "preconfiguredValue");
		ConfigLoaderTestHelper.setResolvedProperties(properties);

		assertThat(ConfigLoader.valueOrProperty("value", TRANSPORT_STRATEGY, "backup")).isEqualTo("value");
		assertThat(ConfigLoader.valueOrProperty(null, TRANSPORT_STRATEGY, "backup")).isEqualTo("preconfiguredValue");
		assertThat(ConfigLoader.valueOrProperty(null, SMTP_HOST, "backup")).isEqualTo("backup");
		assertThat(ConfigLoader.valueOrProperty(null, SMTP_HOST, null)).isNull();
	}

	@Test
	public void hasProperty()
			throws Exception {
		Map<Property, Object> properties = new HashMap<>();
		properties.put(TRANSPORT_STRATEGY, "preconfiguredValue1");
		properties.put(DEFAULT_FROM_ADDRESS, "preconfiguredValue2");
		properties.put(DEFAULT_BCC_NAME, null);
		ConfigLoaderTestHelper.setResolvedProperties(properties);

		assertThat(ConfigLoader.hasProperty(TRANSPORT_STRATEGY)).isTrue();
		assertThat(ConfigLoader.hasProperty(DEFAULT_FROM_ADDRESS)).isTrue();
		assertThat(ConfigLoader.hasProperty(DEFAULT_BCC_NAME)).isTrue();
		assertThat(ConfigLoader.hasProperty(PROXY_HOST)).isFalse();
	}

	@Test
	public void getProperty()
			throws Exception {
		Map<Property, Object> properties = new HashMap<>();
		properties.put(TRANSPORT_STRATEGY, "preconfiguredValue1");
		properties.put(DEFAULT_FROM_ADDRESS, "preconfiguredValue2");
		properties.put(DEFAULT_BCC_NAME, null);
		ConfigLoaderTestHelper.setResolvedProperties(properties);

		assertThat(ConfigLoader.getProperty(TRANSPORT_STRATEGY)).isEqualTo("preconfiguredValue1");
		assertThat(ConfigLoader.getProperty(DEFAULT_FROM_ADDRESS)).isEqualTo("preconfiguredValue2");
		assertThat(ConfigLoader.getProperty(DEFAULT_BCC_NAME)).isNull();
		assertThat(ConfigLoader.getProperty(PROXY_HOST)).isNull();
	}

	@Test
	public void parsePropertyValue() {
		assertThat(ConfigLoader.parsePropertyValue("simple string value")).isEqualTo("simple string value");
		assertThat(ConfigLoader.parsePropertyValue("12345")).isEqualTo(12345);
		assertThat(ConfigLoader.parsePropertyValue("123d45")).isEqualTo("123d45");
		assertThat(ConfigLoader.parsePropertyValue("0")).isEqualTo(false);
		assertThat(ConfigLoader.parsePropertyValue("false")).isEqualTo(false);
		assertThat(ConfigLoader.parsePropertyValue("no")).isEqualTo(false);
		assertThat(ConfigLoader.parsePropertyValue("1")).isEqualTo(true);
		assertThat(ConfigLoader.parsePropertyValue("true")).isEqualTo(true);
		assertThat(ConfigLoader.parsePropertyValue("yes")).isEqualTo(true);
		assertThat(ConfigLoader.parsePropertyValue("yesno")).isEqualTo("yesno");
		assertThat(ConfigLoader.parsePropertyValue("SMTP_PLAIN")).isEqualTo(TransportStrategy.SMTP_PLAIN);
		assertThat(ConfigLoader.parsePropertyValue("SMTP_TLS")).isEqualTo(TransportStrategy.SMTP_TLS);
	}

	@Test
	public void loadPropertiesFromFileClassPath()
			throws Exception {
		ConfigLoader.loadProperties("simplejavamail.properties", false);
		assertThat(ConfigLoader.getProperty(JAVAXMAIL_DEBUG)).isEqualTo(true);
		assertThat(ConfigLoader.getProperty(TRANSPORT_STRATEGY)).isSameAs(SMTP_SSL);
		assertThat(ConfigLoader.getProperty(SMTP_HOST)).isEqualTo("smtp.default.com");
		assertThat(ConfigLoader.getProperty(SMTP_PORT)).isEqualTo(25);
		assertThat(ConfigLoader.getProperty(SMTP_USERNAME)).isEqualTo("username");
		assertThat(ConfigLoader.getProperty(SMTP_PASSWORD)).isEqualTo("password");
		assertThat(ConfigLoader.getProperty(PROXY_HOST)).isEqualTo("proxy.default.com");
		assertThat(ConfigLoader.getProperty(PROXY_PORT)).isEqualTo(1080);
		assertThat(ConfigLoader.getProperty(PROXY_USERNAME)).isEqualTo("username proxy");
		assertThat(ConfigLoader.getProperty(PROXY_PASSWORD)).isEqualTo("password proxy");
		assertThat(ConfigLoader.getProperty(PROXY_SOCKS5BRIDGE_PORT)).isEqualTo(1081);
		assertThat(ConfigLoader.getProperty(DEFAULT_FROM_NAME)).isEqualTo("From Default");
		assertThat(ConfigLoader.getProperty(DEFAULT_FROM_ADDRESS)).isEqualTo("from@default.com");
		assertThat(ConfigLoader.getProperty(DEFAULT_REPLYTO_NAME)).isEqualTo("Reply-To Default");
		assertThat(ConfigLoader.getProperty(DEFAULT_REPLYTO_ADDRESS)).isEqualTo("reply-to@default.com");
		assertThat(ConfigLoader.getProperty(DEFAULT_TO_NAME)).isEqualTo("To Default");
		assertThat(ConfigLoader.getProperty(DEFAULT_TO_ADDRESS)).isEqualTo("to@default.com");
		assertThat(ConfigLoader.getProperty(DEFAULT_CC_NAME)).isEqualTo("CC Default");
		assertThat(ConfigLoader.getProperty(DEFAULT_CC_ADDRESS)).isEqualTo("cc@default.com");
		assertThat(ConfigLoader.getProperty(DEFAULT_BCC_NAME)).isEqualTo("BCC Default");
		assertThat(ConfigLoader.getProperty(DEFAULT_BCC_ADDRESS)).isEqualTo("bcc@default.com");
	}

	@Test
	public void loadPropertiesAddingMode()
			throws Exception {
		String s1 = "simplejavamail.javaxmail.debug=true\n"
				+ "simplejavamail.transportstrategy=SMTP_SSL";
		String s2 = "simplejavamail.defaults.to.name=To Default\n"
				+ "simplejavamail.defaults.to.address=to@default.com";

		ConfigLoader.loadProperties(new ByteArrayInputStream(s1.getBytes()), false);
		ConfigLoader.loadProperties(new ByteArrayInputStream(s2.getBytes()), true);

		// some checks from the config file
		assertThat(ConfigLoader.getProperty(JAVAXMAIL_DEBUG)).isEqualTo(true);
		assertThat(ConfigLoader.getProperty(TRANSPORT_STRATEGY)).isEqualTo(TransportStrategy.SMTP_SSL);
		// now check if the extra properties were added
		assertThat(ConfigLoader.getProperty(DEFAULT_TO_NAME)).isEqualTo("To Default");
		assertThat(ConfigLoader.getProperty(DEFAULT_TO_ADDRESS)).isEqualTo("to@default.com");

	}

	@Test
	public void loadPropertiesFromInputStream()
			throws IOException {

		String s = "simplejavamail.javaxmail.debug=true\n"
				+ "simplejavamail.transportstrategy=SMTP_SSL\n"
				+ "simplejavamail.smtp.host=smtp.default.com\n"
				+ "simplejavamail.smtp.port=25\n"
				+ "simplejavamail.smtp.username=username\n"
				+ "simplejavamail.smtp.password=password\n";

		ConfigLoader.loadProperties(new ByteArrayInputStream(s.getBytes()), false);
		assertThat(ConfigLoader.getProperty(JAVAXMAIL_DEBUG)).isEqualTo(true);
		assertThat(ConfigLoader.getProperty(TRANSPORT_STRATEGY)).isSameAs(SMTP_SSL);
		assertThat(ConfigLoader.getProperty(SMTP_HOST)).isEqualTo("smtp.default.com");
		assertThat(ConfigLoader.getProperty(SMTP_PORT)).isEqualTo(25);
		assertThat(ConfigLoader.getProperty(SMTP_USERNAME)).isEqualTo("username");
		assertThat(ConfigLoader.getProperty(SMTP_PASSWORD)).isEqualTo("password");
	}

	@Test
	public void loadPropertiesFileNotAvailable() {
		ConfigLoader.loadProperties("non-existent.properties", false);
		// success: no error occurred while config file was missing
	}

	@Test(expected = IllegalArgumentException.class)
	public void loadPropertiesFileMalformed() {
		ConfigLoader.loadProperties("malformed.properties", false);
		// error: unknown properties should cause an illegal argument exception
	}

}