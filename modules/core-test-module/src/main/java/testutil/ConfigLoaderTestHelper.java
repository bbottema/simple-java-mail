/*
 * Copyright (C) 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package testutil;

import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigLoader.Property;

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
			throw new AssertionError(e.getMessage(), e);
		}
	}

	public static void restoreOriginalConfigProperties() {
		ConfigLoader.loadProperties(ConfigLoader.DEFAULT_CONFIG_FILENAME, false);
	}
}
