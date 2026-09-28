package org.simplejavamail.internal.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailProviderDiscoveryTest {
    @TempDir
    Path directory;

    @Test
    void scansOnceButConstructsFreshProvidersForEveryIteration() throws Exception {
        final DiscoveryLoader loader = loader(FirstProvider.class);
        inContext(loader, () -> {
            final Iterable<Runnable> providers = MailProviderDiscovery.newProviders(Runnable.class);
            final Runnable first = providers.iterator().next();
            final Runnable nextIteration = providers.iterator().next();
            final Runnable nextOperation = MailProviderDiscovery.newProviders(Runnable.class).iterator().next();
            assertThat(first).isInstanceOf(FirstProvider.class).isNotSameAs(nextIteration).isNotSameAs(nextOperation);
            assertThat(nextIteration).isNotSameAs(nextOperation);
            return null;
        });
        assertThat(loader.scans).hasValue(1);
    }

    @Test
    void contextLoadersKeepIndependentRegistrationsIncludingEmptyDiscovery() throws Exception {
        final DiscoveryLoader first = loader(FirstProvider.class);
        final DiscoveryLoader second = loader(SecondProvider.class);
        final DiscoveryLoader empty = loader();
        for (int repetition = 0; repetition < 3; repetition++) {
            assertThat(inContext(first, MailProviderDiscoveryTest::providerTypes)).containsExactly(FirstProvider.class);
            assertThat(inContext(second, MailProviderDiscoveryTest::providerTypes)).containsExactly(SecondProvider.class);
            assertThat(inContext(empty, MailProviderDiscoveryTest::providerTypes)).isEmpty();
        }
        assertThat(first.scans).hasValue(1);
        assertThat(second.scans).hasValue(1);
        assertThat(empty.scans).hasValue(1);
    }

    @Test
    void identicalProviderNamesLoadedByDifferentApplicationsRemainIsolated() throws Exception {
        final Path registration = Files.createTempFile(directory, "isolated-", ".txt");
        Files.writeString(registration, FirstProvider.class.getName());
        final ClassLoader first = isolatedProviderLoader(registration);
        final ClassLoader second = isolatedProviderLoader(registration);
        final Runnable firstProvider = inContext(first, () -> MailProviderDiscovery.newProviders(Runnable.class).iterator().next());
        final Runnable secondProvider = inContext(second, () -> MailProviderDiscovery.newProviders(Runnable.class).iterator().next());
        assertThat(firstProvider.getClass().getName()).isEqualTo(secondProvider.getClass().getName());
        assertThat(firstProvider.getClass()).isNotEqualTo(secondProvider.getClass());
        assertThat(firstProvider.getClass().getClassLoader()).isSameAs(first);
        assertThat(secondProvider.getClass().getClassLoader()).isSameAs(second);
    }

    @Test
    void retainsRegistrationOrderAndStandardDuplicateElimination() throws Exception {
        final DiscoveryLoader loader = loader(SecondProvider.class, FirstProvider.class, SecondProvider.class);
        assertThat(inContext(loader, MailProviderDiscoveryTest::providerTypes)).containsExactly(SecondProvider.class, FirstProvider.class);
    }

    @Test
    void concurrentFirstUseScansOnceAndDoesNotShareInstances() throws Exception {
        final DiscoveryLoader loader = loader(FirstProvider.class);
        final ExecutorService workers = Executors.newFixedThreadPool(8);
        final CountDownLatch start = new CountDownLatch(1);
        try {
            final List<Future<Runnable>> requests = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                requests.add(workers.submit(() -> {
                    assertThat(start.await(5, SECONDS)).isTrue();
                    return inContext(loader, () -> MailProviderDiscovery.newProviders(Runnable.class).iterator().next());
                }));
            }
            start.countDown();
            final List<Runnable> instances = new ArrayList<>();
            for (final Future<Runnable> request : requests) {
                instances.add(request.get(5, SECONDS));
            }
            assertThat(instances).hasSize(32).doesNotHaveDuplicates().allMatch(FirstProvider.class::isInstance);
            assertThat(loader.scans).hasValue(1);
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    @Test
    void failedDiscoveryIsRetriedInsteadOfPublishingPartialResults() throws Exception {
        final DiscoveryLoader loader = loader(FirstProvider.class);
        Files.writeString(loader.registration, FirstProvider.class.getName() + "\nmissing.Adapter\n");
        inContext(loader, () -> {
            assertThatThrownBy(() -> MailProviderDiscovery.newProviders(Runnable.class)).isInstanceOf(ServiceConfigurationError.class);
            return null;
        });
        Files.writeString(loader.registration, SecondProvider.class.getName());
        assertThat(inContext(loader, MailProviderDiscoveryTest::providerTypes)).containsExactly(SecondProvider.class);
        assertThat(loader.scans).hasValue(2);
    }

    @Test
    void constructorFailuresRemainPerOperationAndDoNotPoisonDiscovery() throws Exception {
        final DiscoveryLoader loader = loader(FailingProvider.class);
        final int attemptsBefore = FailingProvider.attempts.get();
        inContext(loader, () -> {
            for (int attempt = 0; attempt < 2; attempt++) {
                final Iterable<Runnable> providers = MailProviderDiscovery.newProviders(Runnable.class);
                assertThatThrownBy(() -> providers.iterator().next()).isInstanceOf(ServiceConfigurationError.class)
                        .hasRootCauseMessage("provider construction failed");
            }
            return null;
        });
        assertThat(FailingProvider.attempts).hasValue(attemptsBefore + 2);
        assertThat(loader.scans).hasValue(1);
    }

    @Test
    void nullContextLoaderUsesTheSameSystemLookupAsServiceLoader() {
        final List<Class<?>> expected = ServiceLoader.load(Runnable.class, ClassLoader.getSystemClassLoader()).stream()
                .map(ServiceLoader.Provider::type).collect(toList());
        assertThat(inContext(null, MailProviderDiscoveryTest::providerTypes)).isEqualTo(expected);
    }

    @Test
    void aLoaderThatCannotSeeTheSpiKeepsUncachedServiceLoaderBehavior() {
        final AtomicInteger scans = new AtomicInteger();
        final ClassLoader isolated = new ClassLoader(null) {
            @Override
            public Enumeration<URL> getResources(final String name) {
                scans.incrementAndGet();
                return Collections.emptyEnumeration();
            }
        };
        inContext(isolated, () -> {
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThat(MailProviderDiscovery.newProviders(PrivateApplicationService.class)).isEmpty();
            }
            return null;
        });
        assertThat(scans).hasValue(2);
    }

    private DiscoveryLoader loader(final Class<?>... providers) throws IOException {
        final Path registration = Files.createTempFile(directory, "providers-", ".txt");
        final List<String> names = new ArrayList<>();
        for (final Class<?> provider : providers) {
            names.add(provider.getName());
        }
        Files.write(registration, names);
        return new DiscoveryLoader(registration);
    }

    private static ClassLoader isolatedProviderLoader(final Path registration) {
        return new DiscoveryLoader(registration) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                if (!name.equals(FirstProvider.class.getName())) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> provider = findLoadedClass(name);
                    if (provider == null) {
                        try (InputStream source = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            final byte[] bytes = source.readAllBytes();
                            provider = defineClass(name, bytes, 0, bytes.length);
                        } catch (IOException failure) {
                            throw new ClassNotFoundException(name, failure);
                        }
                    }
                    if (resolve) {
                        resolveClass(provider);
                    }
                    return provider;
                }
            }
        };
    }

    private static List<Class<?>> providerTypes() {
        final List<Class<?>> types = new ArrayList<>();
        MailProviderDiscovery.newProviders(Runnable.class).forEach(provider -> types.add(provider.getClass()));
        return types;
    }

    private static <T> T inContext(final ClassLoader loader, final Supplier<T> operation) {
        final Thread caller = Thread.currentThread();
        final ClassLoader previous = caller.getContextClassLoader();
        try {
            caller.setContextClassLoader(loader);
            return operation.get();
        } finally {
            caller.setContextClassLoader(previous);
        }
    }

    private static class DiscoveryLoader extends ClassLoader {
        private final Path registration;
        private final AtomicInteger scans = new AtomicInteger();

        private DiscoveryLoader(final Path registration) {
            super(MailProviderDiscoveryTest.class.getClassLoader());
            this.registration = registration;
        }

        @Override
        public Enumeration<URL> getResources(final String name) throws IOException {
            if (name.equals("META-INF/services/" + Runnable.class.getName())) {
                scans.incrementAndGet();
                return Collections.enumeration(List.of(registration.toUri().toURL()));
            }
            return super.getResources(name);
        }
    }

    public static final class FirstProvider implements Runnable {
        @Override public void run() { }
    }

    public interface PrivateApplicationService { }

    public static final class SecondProvider implements Runnable {
        @Override public void run() { }
    }

    public static final class FailingProvider implements Runnable {
        private static final AtomicInteger attempts = new AtomicInteger();

        public FailingProvider() {
            attempts.incrementAndGet();
            throw new IllegalStateException("provider construction failed");
        }

        @Override public void run() { }
    }
}
