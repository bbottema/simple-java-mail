package testutil;

import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigLoader.Property;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Properties;

/**
 * Test helper that can empty any properties loaded by the ConfigLoader.
 */
public class ConfigLoaderTestHelper {
	public static void setResolvedProperties(Map<Property, Object> value)
			throws Exception {
		Field field = ConfigLoader.class.getDeclaredField("RESOLVED_PROPERTIES");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		final Map<Property, Object> resolvedProperties = (Map<Property, Object>) field.get(null);
		resolvedProperties.clear();
		resolvedProperties.putAll(value);
	}

	public static void clearConfigProperties() {
		ConfigLoader.loadProperties(new Properties(), false);
	}

	public static void restoreOriginalConfigProperties() {
		ConfigLoader.loadProperties(ConfigLoader.DEFAULT_CONFIG_FILENAME, false);
	}
}
