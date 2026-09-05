package org.simplejavamail.internal.moduleloader;

import org.junit.jupiter.api.Test;
import org.simplejavamail.internal.modules.OutlookModule;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class ModuleLoaderTest {

	@Test
	public void concurrentLoadsReuseOneModuleInstance() throws Exception {
		final int threadCount = 32;
		final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		final Map<Class<?>, Object> loadedModules = loadedModules();
		try {
			for (int attempt = 0; attempt < 10; attempt++) {
				loadedModules.clear();
				final CountDownLatch ready = new CountDownLatch(threadCount);
				final CountDownLatch start = new CountDownLatch(1);
				final List<Future<OutlookModule>> moduleLoads = new ArrayList<>();
				for (int i = 0; i < threadCount; i++) {
					moduleLoads.add(executor.submit(() -> {
						ready.countDown();
						start.await();
						return ModuleLoader.loadOutlookModule();
					}));
				}

				assertThat(ready.await(10, SECONDS)).isTrue();
				start.countDown();
				final Set<OutlookModule> distinctModules = Collections.newSetFromMap(new IdentityHashMap<>());
				for (final Future<OutlookModule> moduleLoad : moduleLoads) {
					distinctModules.add(moduleLoad.get(10, SECONDS));
				}
				assertThat(distinctModules).hasSize(1);
			}
		} finally {
			loadedModules.clear();
			executor.shutdownNow();
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<Class<?>, Object> loadedModules() throws ReflectiveOperationException {
		final Field loadedModules = ModuleLoader.class.getDeclaredField("LOADED_MODULES");
		loadedModules.setAccessible(true);
		return (Map<Class<?>, Object>) loadedModules.get(null);
	}
}
