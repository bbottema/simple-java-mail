package testutil.configcontracts;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/** Checks an explicitly selected obligation set against current successful, inventory-bound invocation evidence. */
public final class ConfigurationContractVerifier {
    private ConfigurationContractVerifier() { }

    /**
     * Accepts only a complete match. Unknown, misclassified, wrong-module, stale or unsuccessful proofs fail the whole check,
     * even if another proof covers the same obligation. Counts are returned only after every obligation and supplied proof is valid.
     */
    public static Result verify(final Collection<Obligation> obligations, final Collection<Proof> proofs, final String expectedFingerprint) {
        requireNonNull(obligations, "obligations");
        requireNonNull(proofs, "proofs");
        if (expectedFingerprint == null || !expectedFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Configuration-contract verification requires a bound SHA-256 inventory fingerprint");
        }
        if (obligations.isEmpty()) { throw new IllegalStateException("Configuration-contract obligation inventory is empty"); }

        final Map<Key, Obligation> required = new LinkedHashMap<>();
        final Set<String> problems = new LinkedHashSet<>();
        for (Obligation obligation : obligations) {
            final Key key = new Key(obligation.kind, obligation.id);
            if (required.putIfAbsent(key, obligation) != null) { problems.add("duplicate obligation " + key); }
        }
        final Set<Key> covered = new LinkedHashSet<>();
        for (Proof proof : proofs) {
            final Key key = new Key(proof.entry.getKind(), proof.entry.getId());
            final Obligation obligation = required.get(key);
            boolean valid = true;
            if (obligation == null) {
                final Set<String> kinds = required.keySet().stream().filter(candidate -> candidate.id.equals(key.id))
                        .map(candidate -> candidate.kind).collect(Collectors.toCollection(LinkedHashSet::new));
                problems.add(kinds.isEmpty() ? "unknown proof " + key : "wrong kind for proof " + key + "; expected " + kinds);
                valid = false;
            } else if (!obligation.module.equals(proof.module)) {
                problems.add("wrong module for proof " + key + "; expected " + obligation.module + ", received " + proof.module);
                valid = false;
            }
            if (!expectedFingerprint.equals(proof.entry.getInventoryFingerprint())) {
                problems.add("inventory fingerprint mismatch for proof " + key);
                valid = false;
            }
            if (!proof.currentSuccessfulTest) {
                problems.add("proof " + key + " is not from a current successful JUnit invocation: " + proof.entry.getTest());
                valid = false;
            }
            if (valid) { covered.add(key); }
        }
        for (Key key : required.keySet()) {
            if (!covered.contains(key)) { problems.add("missing obligation " + key); }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Configuration-contract verification failed:\n - " + String.join("\n - ", problems));
        }
        return new Result(required.size(), covered.size());
    }

    public static final class Obligation {
        private final String kind;
        private final String id;
        private final String module;

        public Obligation(final String kind, final String id, final String module) {
            this.kind = ConfigurationContractEvidence.identifier(kind, "kind");
            this.id = ConfigurationContractEvidence.identifier(id, "id");
            this.module = ConfigurationContractEvidence.identifier(module, "module");
        }

        public String getKind() { return kind; }
        public String getId() { return id; }
        public String getModule() { return module; }
    }

    /**
     * Evidence joined to its module and an already-authenticated current-success fact. The gate must establish that fact from the
     * current Maven run's Surefire results, matching the precise parameterized invocation where applicable; this value does not authenticate itself.
     */
    public static final class Proof {
        private final String module;
        private final ConfigurationContractEvidence.Entry entry;
        private final boolean currentSuccessfulTest;

        public Proof(final String module, final ConfigurationContractEvidence.Entry entry, final boolean currentSuccessfulTest) {
            this.module = ConfigurationContractEvidence.identifier(module, "module");
            this.entry = requireNonNull(entry, "entry");
            this.currentSuccessfulTest = currentSuccessfulTest;
        }

        public String getModule() { return module; }
        public ConfigurationContractEvidence.Entry getEntry() { return entry; }
        public boolean isCurrentSuccessfulTest() { return currentSuccessfulTest; }
    }

    public static final class Result {
        private final int requiredCount;
        private final int coveredCount;

        private Result(final int requiredCount, final int coveredCount) {
            this.requiredCount = requiredCount;
            this.coveredCount = coveredCount;
        }

        public int getRequiredCount() { return requiredCount; }
        public int getCoveredCount() { return coveredCount; }
    }

    private static final class Key {
        private final String kind;
        private final String id;

        private Key(final String kind, final String id) { this.kind = kind; this.id = id; }
        @Override public boolean equals(final Object other) {
            if (!(other instanceof Key)) { return false; }
            final Key candidate = (Key) other;
            return kind.equals(candidate.kind) && id.equals(candidate.id);
        }
        @Override public int hashCode() { return 31 * kind.hashCode() + id.hashCode(); }
        @Override public String toString() { return kind + ":" + id; }
    }
}
