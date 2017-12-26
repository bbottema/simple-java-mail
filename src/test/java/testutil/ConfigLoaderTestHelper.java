package testutil;

import org.simplejavamail.util.ConfigLoader;
import org.simplejavamail.util.ConfigLoader.Property;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * Test helper that can empty any properties loaded by the ConfigLoader.
 */
public class ConfigLoaderTestHelper {
	public static void setResolvedProperties(Map<Property, Object> value)
			throws Exception {
		Field field = makeAccessible(ConfigLoader.class.getDeclaredField("RESOLVED_PROPERTIES"));
		field.set(null, value);
	}

	private static Field makeAccessible(Field field)
			throws NoSuchFieldException, IllegalAccessException {
		field.setAccessible(true);
		Field modifiersField = Field.class.getDeclaredField("modifiers");
		modifiersField.setAccessible(true);
		modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
		return field;
	}

	public static void clearConfigProperties() {
		try {
			setResolvedProperties(new HashMap<Property, Object>());
		} catch (Exception e) {
			throw new RuntimeException(e.getMessage(), e);
		}
	}
}
