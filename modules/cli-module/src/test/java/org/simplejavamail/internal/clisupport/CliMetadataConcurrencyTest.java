package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.clisupport.therapijavadoc.TherapiJavadocHelper;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CliMetadataConcurrencyTest {

    @Test
    @Timeout(45)
    @SuppressWarnings("unchecked")
    void concurrentHelpCanRepopulateMissingJavadocCacheEntries() throws Exception {
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), mock(MailerProvider.class))) {
            assertThat(help(environment).exitCode()).isZero();
            final Field cacheField = TherapiJavadocHelper.class.getDeclaredField("THERAPI_CACHE");
            cacheField.setAccessible(true);
            final Map<String, Object> cache = (Map<String, Object>) cacheField.get(null);
            final Map<String, Object> original = new HashMap<>(cache);
            final ExecutorService workers = Executors.newFixedThreadPool(8);
            final CountDownLatch ready = new CountDownLatch(8);
            final CountDownLatch start = new CountDownLatch(1);
            try {
                // Retain valid option metadata but remove the Javadoc entries, as when the API invalidates therapi.data.
                cache.clear();
                final List<Future<CliExecutionResult>> results = new ArrayList<>();
                for (int index = 0; index < 8; index++) {
                    results.add(workers.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(10, SECONDS)).isTrue();
                        return help(environment);
                    }));
                }
                assertThat(ready.await(10, SECONDS)).isTrue();
                start.countDown();
                for (final Future<CliExecutionResult> pending : results) {
                    final CliExecutionResult result = pending.get(30, SECONDS);
                    assertThat(result.exitCode()).as(result.stderr()).isZero();
                    assertThat(result.stdout()).contains("--email:fixingEnvelopeId");
                }
            } finally {
                start.countDown();
                workers.shutdownNow();
                assertThat(workers.awaitTermination(10, SECONDS)).isTrue();
                cache.clear();
                cache.putAll(original);
            }
        }
    }

    private static CliExecutionResult help(final CliExecutionEnvironment environment) {
        return CliSupport.execute(new String[]{"send", "--help"}, Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
    }
}
