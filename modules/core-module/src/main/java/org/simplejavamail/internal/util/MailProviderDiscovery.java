package org.simplejavamail.internal.util;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.util.stream.Collectors.toUnmodifiableList;

/**
 * Discovers adapter factories once per thread-context class loader, without sharing adapter instances between operations.
 * The provider registrations are fixed for that loader's lifetime; a replacement application loader gets its own discovery.
 * No transport selection, abort action or submission state belongs in this cache.
 */
public final class MailProviderDiscovery {

    private static final ClassValue<ProviderFactories> FACTORIES = new ClassValue<ProviderFactories>() {
        @Override
        protected ProviderFactories computeValue(final Class<?> loaderAnchor) {
            return new ProviderFactories();
        }
    };

    private MailProviderDiscovery() {
    }

    /**
     * Returns fresh, lazily constructed adapters using the same context-loader lookup as {@link ServiceLoader#load(Class)}.
     * Failed discovery is not cached. Provider construction remains outside the cache lock and is retried on each operation.
     * Only the mail adapter SPIs declared in this module's {@code uses} directives belong here.
     */
    @NotNull
    public static <T> Iterable<T> newProviders(@NotNull final Class<T> service) {
        final ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        final ClassLoader lookupLoader = contextLoader != null ? contextLoader : ClassLoader.getSystemClassLoader();
        final Class<?> anchor;
        try {
            anchor = loaderAnchor(lookupLoader, service);
        } catch (IllegalArgumentException | SecurityException unavailableAnchor) {
            // An unusual context loader may not see this SPI, or may forbid proxy classes. Keep normal ServiceLoader behavior in that case.
            return ServiceLoader.load(service, lookupLoader);
        }
        final List<ServiceLoader.Provider<T>> factories = FACTORIES.get(anchor).forService(service, lookupLoader);
        return () -> factories.stream().map(ServiceLoader.Provider::get).iterator();
    }

    @SuppressWarnings("deprecation")
    private static Class<?> loaderAnchor(final ClassLoader loader, final Class<?> service) {
        // A provider factory retains its defining loader, so even weak map keys would keep application loaders alive.
        // The JDK caches this class in the requested loader. ClassValue keeps our factories with that class, not in a global loader map.
        // Using the SPI also checks that this loader can see our service type; a parent that cannot see it must not retain our cache.
        // We only need the class as an identity/lifetime anchor; no proxy instance or reflective constructor access is involved.
        return Proxy.getProxyClass(loader, service);
    }

    private static final class ProviderFactories {
        private final ConcurrentMap<Class<?>, List<?>> byService = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        private <T> List<ServiceLoader.Provider<T>> forService(final Class<T> service, final ClassLoader loader) {
            // Cache only completed discovery. computeIfAbsent also prevents duplicate scans during concurrent first use.
            return (List<ServiceLoader.Provider<T>>) byService.computeIfAbsent(service,
                    ignored -> ServiceLoader.load(service, loader).stream().collect(toUnmodifiableList()));
        }
    }
}
