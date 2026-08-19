package testutil;

import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.config.SimpleJavaMailConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test helper for creating explicit configuration snapshots without changing process-wide state.
 */
public final class ConfigLoaderTestHelper {

	public static SimpleJavaMailConfig emptyConfig() {
		return ConfigLoader.builder().load();
	}

	public static SimpleJavaMailConfig defaultConfig() {
		return ConfigLoader.builder()
				.withClasspathResource(ConfigLoader.DEFAULT_CONFIG_FILENAME)
				.load();
	}

	public static SimpleJavaMailConfig config(final Map<Property, Object> values) {
		final Map<String, Object> properties = new LinkedHashMap<>();
		for (final Map.Entry<Property, Object> entry : values.entrySet()) {
			properties.put(entry.getKey().key(), entry.getValue());
		}
		return ConfigLoader.builder().withMap(properties).load();
	}

	private ConfigLoaderTestHelper() {
	}
}
