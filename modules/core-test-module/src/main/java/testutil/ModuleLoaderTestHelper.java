package testutil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Test helper that can force disable modules that are on the classpath
 */
public class ModuleLoaderTestHelper {
	public static void _forceDisableBatchModule() {
		try {
			Class<?> classModuleLoader = Class.forName("org.simplejavamail.internal.moduleloader.ModuleLoader");
			Method m = classModuleLoader.getDeclaredMethod("_forceDisableBatchModule");
			m.invoke(null);
		} catch (IllegalAccessException | InvocationTargetException | ClassNotFoundException | NoSuchMethodException e) {
			throw new RuntimeException("Exception trying to access ModuleLoader", e);
		}
	}
}
