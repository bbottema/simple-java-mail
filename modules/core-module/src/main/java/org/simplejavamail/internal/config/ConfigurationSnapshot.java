package org.simplejavamail.internal.config;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.config.SimpleJavaMailConfig;

import java.util.Map;

/** Carries loader-owned configuration restrictions between library modules without exposing them on the application snapshot. */
@ApiStatus.Internal
public interface ConfigurationSnapshot extends SimpleJavaMailConfig {

    @NotNull ConfigurationLocks getLocks();

    @NotNull Map<String, Object> asSourceMap();

    /** Only snapshots produced by the shared loader have the provenance and lock data needed by the implementation. */
    @NotNull
    static ConfigurationSnapshot requireInternalConfig(@NotNull final SimpleJavaMailConfig config) {
        if (!(config instanceof ConfigurationSnapshot)) {
            throw new IllegalArgumentException("Use a configuration snapshot produced by ConfigLoader; custom snapshot implementations are not supported.");
        }
        return (ConfigurationSnapshot) config;
    }

    @NotNull
    static ConfigurationLocks locksOf(@NotNull final SimpleJavaMailConfig config) {
        return requireInternalConfig(config).getLocks();
    }
}
