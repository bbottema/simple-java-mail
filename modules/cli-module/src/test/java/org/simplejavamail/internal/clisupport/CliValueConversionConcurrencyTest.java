package org.simplejavamail.internal.clisupport;

import org.bbottema.javareflection.valueconverter.ValueConversionHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
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
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

class CliValueConversionConcurrencyTest {
    @Test
    @Timeout(15)
    void conversionRemainsCorrectWhileTheConverterGraphIsRebuilt() throws Throwable {
        runIsolated("exercise");
    }

    @Test
    @Timeout(20)
    void blockedFileLookupDoesNotBlockAnUnrelatedConversion() throws Throwable {
        runIsolated("blockedFileDoesNotBlockUnrelatedConversion");
    }

    private static void runIsolated(final String method) throws Throwable {
        final String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        final List<URL> urls = new ArrayList<>();
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) urls.add(new File(entry).toURI().toURL());
        try (URLClassLoader runtime = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            final Thread thread = Thread.currentThread();
            final ClassLoader previous = thread.getContextClassLoader();
            try {
                thread.setContextClassLoader(runtime);
                runtime.loadClass(Driver.class.getName()).getMethod(method).invoke(null);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            } finally {
                thread.setContextClassLoader(previous);
            }
        }
    }

    /** Runs all helper state and the actual CLI conversion in the isolated application loader. */
    public static final class Driver {
        public static void exercise() throws Exception {
            final Method builderMethod = MailerGenericBuilder.class.getMethod("withConnectionConflictPolicy", ConnectionConflictPolicy.class);
            assertThat(convert(builderMethod)).isEqualTo(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
            final Field graphField = ValueConversionHelper.class.getDeclaredField("converterGraph");
            graphField.setAccessible(true);
            @SuppressWarnings("unchecked") final Map<Object, Object> graph = (Map<Object, Object>) graphField.get(null);
            final Map<Object, Object> saved = new HashMap<>(graph);
            final CountDownLatch empty = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final CountDownLatch entered = new CountDownLatch(1);
            final ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                final Future<?> writer = workers.submit(() -> {
                    synchronized (ValueConversionHelper.class) {
                        graph.clear();
                        empty.countDown();
                        try {
                            assertThat(release.await(5, SECONDS)).isTrue();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        } finally {
                            graph.putAll(saved);
                        }
                    }
                });
                try {
                    assertThat(empty.await(5, SECONDS)).isTrue();
                    final Future<Object> reader = workers.submit(() -> {
                        entered.countDown();
                        return convert(builderMethod);
                    });
                    assertThat(entered.await(5, SECONDS)).isTrue();
                    try {
                        // A correct implementation may finish immediately or wait until rebuilding completes.
                        assertThat(reader.get(250, MILLISECONDS)).isEqualTo(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
                    } catch (TimeoutException waitingForRebuild) {
                        // The functional result is checked after the graph is restored.
                    }
                    release.countDown();
                    assertThat(reader.get(5, SECONDS)).isEqualTo(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
                } finally {
                    release.countDown();
                    writer.get(5, SECONDS);
                }
            } finally {
                release.countDown();
                workers.shutdownNow();
                assertThat(workers.awaitTermination(5, SECONDS)).isTrue();
            }
        }

        public static void blockedFileDoesNotBlockUnrelatedConversion() throws Exception {
            final Method enumMethod = MailerGenericBuilder.class.getMethod("withConnectionConflictPolicy", ConnectionConflictPolicy.class);
            assertThat(convert(enumMethod)).isEqualTo(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final File blocked = new File("controlled-file") {
                @Override public boolean exists() {
                    entered.countDown();
                    try {
                        assertThat(release.await(10, SECONDS)).isTrue();
                        return true;
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }
            };
            final Path cwd = (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[]{Path.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "toAbsolutePath": case "normalize": case "resolve": return proxy;
                            case "toFile": return blocked;
                            case "toString": return "controlled-working-directory";
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == args[0];
                            default: throw new AssertionError("Unexpected path operation: " + method.getName());
                        }
                    });
            final Method fileMethod = Driver.class.getMethod("acceptFile", File.class);
            final ExecutorService workers = Executors.newFixedThreadPool(2);
            Future<Object> file = null;
            try {
                file = workers.submit(() -> {
                    try (CliRequestContext.Scope ignored = CliRequestContext.install(new CliRequestContext(UUID.randomUUID(), cwd))) {
                        return CliCommandLineConsumer.convertProvidedOptionValues(new ArrayList<>(List.of("controlled-file")), fileMethod).get(0);
                    }
                });
                assertThat(entered.await(5, SECONDS)).isTrue();
                final Future<Object> other = workers.submit(() -> convert(enumMethod));
                assertThat(other.get(5, SECONDS)).isEqualTo(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
                assertThat(release.getCount()).isEqualTo(1);
            } finally {
                release.countDown();
                try {
                    if (file != null) assertThat(file.get(5, SECONDS)).isSameAs(blocked);
                } finally {
                    workers.shutdownNow();
                    assertThat(workers.awaitTermination(5, SECONDS)).isTrue();
                }
            }
        }

        public static void acceptFile(final File file) { }

        private static Object convert(final Method builderMethod) {
            return CliCommandLineConsumer.convertProvidedOptionValues(
                    new ArrayList<>(List.of("ALLOW_EXPLICIT_OVERRIDES")), builderMethod).get(0);
        }
    }
}
