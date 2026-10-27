package testutil.configcontracts;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Auto-detected test support: publish only after the method, lifecycle callbacks and other extensions have succeeded. */
public final class ConfigurationContractExtension implements BeforeEachCallback, AfterEachCallback, TestWatcher {
    // Jupiter closes its ExtensionContext.Store before notifying TestWatchers.
    private final ConcurrentMap<String, ConfigurationContractCoverage.Scope> scopes = new ConcurrentHashMap<>();

    @Override public void beforeEach(final ExtensionContext context) {
        final String module = context.getConfigurationParameter(ConfigurationContractCoverage.MODULE_PROPERTY).orElse(null);
        final String run = context.getConfigurationParameter(ConfigurationContractCoverage.RUN_PROPERTY).orElse(null);
        final String inventory = context.getConfigurationParameter(ConfigurationContractCoverage.INVENTORY_PROPERTY).orElse(null);
        final String test = context.getRequiredTestClass().getName() + "#" + context.getRequiredTestMethod().getName();
        final ConfigurationContractCoverage.Scope scope = ConfigurationContractCoverage.begin(module, run, inventory, test, context.getUniqueId());
        if (scopes.putIfAbsent(context.getUniqueId(), scope) != null) {
            scope.detach();
            throw new IllegalStateException("Configuration-contract invocation scope is already active");
        }
    }

    @Override public void afterEach(final ExtensionContext context) {
        final ConfigurationContractCoverage.Scope scope = scopes.get(context.getUniqueId());
        if (scope != null) { scope.detach(); }
    }

    @Override public void testSuccessful(final ExtensionContext context) {
        final ConfigurationContractCoverage.Scope scope = finish(context);
        if (scope != null) { scope.publish(); }
    }

    @Override public void testAborted(final ExtensionContext context, final Throwable cause) { discard(context); }
    @Override public void testFailed(final ExtensionContext context, final Throwable cause) { discard(context); }

    private void discard(final ExtensionContext context) { finish(context); }

    private ConfigurationContractCoverage.Scope finish(final ExtensionContext context) {
        final ConfigurationContractCoverage.Scope scope = scopes.remove(context.getUniqueId());
        if (scope != null) { scope.detach(); }
        return scope;
    }
}
