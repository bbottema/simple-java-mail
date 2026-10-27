package testutil.configcontracts;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/** Test-only witnesses remain pending until JUnit reports that their complete invocation succeeded. */
public final class ConfigurationContractCoverage {
    public static final String RUN_PROPERTY = "sjm.configuration.contract.run";
    public static final String MODULE_PROPERTY = "sjm.configuration.contract.module";
    public static final String INVENTORY_PROPERTY = "sjm.configuration.contract.inventory";

    private static final String STANDALONE_RUN = "standalone-" + UUID.randomUUID();
    private static final ThreadLocal<Deque<Scope>> SCOPES = ThreadLocal.withInitial(ArrayDeque::new);

    private ConfigurationContractCoverage() { }

    /**
     * Record a named behavioral witness after its assertions; failed, aborted and disabled tests cannot publish it.
     * API witnesses must come from {@link #invoke(Object, Method, Object...)}.
     */
    public static void record(final String kind, final String id) {
        final Scope scope = activeScope();
        if ("api".equals(kind)) { throw new IllegalArgumentException("API contract evidence must come from invoking its public method"); }
        scope.claims.add(new Claim(kind, id));
    }

    /**
     * Calls the actual public contract method and retains its erased signature only after the call returns successfully.
     * The original target exception is rethrown unchanged; no arguments, receivers or values are rendered for evidence.
     */
    public static Object invoke(final Object receiver, final Method publicContractMethod, final Object... arguments) {
        activeScope();
        requireNonNull(publicContractMethod, "publicContractMethod");
        if (!Modifier.isPublic(publicContractMethod.getModifiers())
                || !Modifier.isPublic(publicContractMethod.getDeclaringClass().getModifiers())) {
            throw new IllegalArgumentException("Configuration contract evidence requires a public contract method");
        }
        try {
            final Object result = publicContractMethod.invoke(receiver, arguments);
            activeScope().claims.add(new Claim("api", signature(publicContractMethod)));
            return result;
        } catch (InvocationTargetException failure) {
            return ConfigurationContractCoverage.<RuntimeException>rethrow(failure.getCause());
        } catch (IllegalAccessException failure) {
            return ConfigurationContractCoverage.<RuntimeException>rethrow(failure);
        }
    }

    public static String signature(final Method method) {
        requireNonNull(method, "method");
        return method.getDeclaringClass().getName() + "#" + method.getName() + "("
                + Arrays.stream(method.getParameterTypes()).map(Class::getTypeName).collect(Collectors.joining(","))
                + "):" + method.getReturnType().getTypeName();
    }

    static Scope begin(final String module, final String run, final String test, final String invocationId) {
        return begin(module, run, null, test, invocationId);
    }

    static Scope begin(final String module, final String run, final String inventoryDirectory, final String test, final String invocationId) {
        if (run != null && module == null) {
            throw new IllegalStateException("A configured configuration-contract run requires its module directory");
        }
        final String selectedRun = run == null ? STANDALONE_RUN : run;
        if (!selectedRun.matches("(?:run-)?[0-9]+|standalone-[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException("Configuration-contract run must be a millisecond identifier, run-<millis>, or an explicit standalone run");
        }
        final Path moduleDirectory = Path.of(module == null ? "" : module).toAbsolutePath().normalize();
        final String inventoryFingerprint;
        try {
            inventoryFingerprint = inventoryDirectory == null ? "unbound" : ConfigurationContractEvidence.fingerprint(Path.of(inventoryDirectory));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not bind configuration-contract evidence to its inventory", failure);
        }
        final Scope scope = new Scope(moduleDirectory.resolve("target/configuration-contracts").resolve(selectedRun), test, invocationId, inventoryFingerprint);
        SCOPES.get().push(scope);
        return scope;
    }

    static void leave(final Scope scope) {
        if (scope == null) { return; }
        final Deque<Scope> scopes = SCOPES.get();
        if (!scopes.removeFirstOccurrence(scope)) {
            throw new IllegalStateException("Configuration-contract invocation scope was not active on this thread");
        }
        if (scopes.isEmpty()) { SCOPES.remove(); }
    }

    private static Scope activeScope() {
        final Scope scope = SCOPES.get().peek();
        if (scope == null) {
            SCOPES.remove();
            throw new IllegalStateException("Configuration contract evidence requires an active JUnit invocation");
        }
        return scope;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> Object rethrow(final Throwable failure) throws E {
        throw (E) failure;
    }

    static final class Scope {
        private final Path directory;
        private final String test;
        private final String invocationId;
        private final String inventoryFingerprint;
        private final Set<Claim> claims = new LinkedHashSet<>();
        private boolean detached;

        private Scope(final Path directory, final String test, final String invocationId, final String inventoryFingerprint) {
            this.directory = directory;
            this.test = ConfigurationContractEvidence.identifier(test, "test");
            this.invocationId = ConfigurationContractEvidence.identifier(invocationId, "invocationId")
                    + "[configuration-contract-invocation:" + UUID.randomUUID() + "]";
            this.inventoryFingerprint = inventoryFingerprint;
        }

        void publish() {
            if (claims.isEmpty()) { return; }
            ConfigurationContractEvidence.publish(directory, claims.stream().map(claim ->
                    new ConfigurationContractEvidence.Entry(claim.kind, claim.id, test, invocationId, inventoryFingerprint)).collect(Collectors.toList()));
        }

        synchronized void detach() {
            if (!detached) {
                ConfigurationContractCoverage.leave(this);
                detached = true;
            }
        }
    }

    private static final class Claim {
        private final String kind;
        private final String id;

        private Claim(final String kind, final String id) {
            this.kind = ConfigurationContractEvidence.identifier(kind, "kind");
            this.id = ConfigurationContractEvidence.identifier(id, "id");
        }

        @Override public boolean equals(final Object other) {
            if (!(other instanceof Claim)) { return false; }
            final Claim candidate = (Claim) other;
            return kind.equals(candidate.kind) && id.equals(candidate.id);
        }

        @Override public int hashCode() { return 31 * kind.hashCode() + id.hashCode(); }
    }
}
