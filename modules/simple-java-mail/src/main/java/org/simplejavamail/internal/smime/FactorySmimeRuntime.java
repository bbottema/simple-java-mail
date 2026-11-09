package org.simplejavamail.internal.smime;

import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.converter.internal.ConfiguredEmailConverterImpl;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.SMIMEModule;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** One factory's lazy private-key runtime; neither configuration snapshots nor Email values own this state. */
@ApiStatus.Internal
public final class FactorySmimeRuntime {
    private final int capacity;
    private final Supplier<SMIMEModule> conventionalModule;
    private volatile SMIMEModule module;

    public FactorySmimeRuntime(final SimpleJavaMailConfig configuration) {
        final Integer configured = requireNonNull(configuration, "configuration").get(ConfigKeys.SMIME_KEY_CACHE_CAPACITY);
        capacity = configured != null ? configured : ConfigLoader.DEFAULT_SMIME_KEY_CACHE_CAPACITY;
        conventionalModule = null;
    }

    private FactorySmimeRuntime(final Supplier<SMIMEModule> conventionalModule) {
        capacity = ConfigLoader.DEFAULT_SMIME_KEY_CACHE_CAPACITY;
        this.conventionalModule = conventionalModule;
    }

    /** Optional-feature construction and capacity semantics are deferred until a private-key operation needs them. */
    public SMIMEModule module() {
        if (conventionalModule != null) return conventionalModule.get();
        final SMIMEModule entry = ModuleLoader.loadSmimeModule();
        SMIMEModule selected = module;
        if (selected == null) {
            synchronized (this) {
                selected = module;
                if (selected == null) module = selected = requireNonNull(entry.createRuntime(capacity), "S/MIME runtime");
            }
        }
        return selected;
    }

    /** Static application conveniences use the conventional default factory rather than a module-global cache. */
    public static FactorySmimeRuntime conventional() {
        return new FactorySmimeRuntime(() ->
                ((ConfiguredEmailConverterImpl) SimpleJavaMail.fromDefaults().converter()).smimeRuntime().module());
    }

    /** A direct internal construction route has its own explicit owner, without reopening conventional process sources. */
    public static FactorySmimeRuntime isolated() {
        return new FactorySmimeRuntime(ConfigLoader.builder().load());
    }
}
