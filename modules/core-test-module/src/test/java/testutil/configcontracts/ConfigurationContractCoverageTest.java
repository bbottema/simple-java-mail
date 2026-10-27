package testutil.configcontracts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class ConfigurationContractCoverageTest {
    @TempDir Path module;

    @Test
    void successfulInvocationPublishesItsRealMethodAndBehaviorWitnesses() throws Exception {
        final TestExecutionSummary summary = execute("1001", SuccessfulInvocation.class);
        assertThat(summary.getTestsSucceededCount()).isEqualTo(1);
        assertThat(summary.getFailures()).isEmpty();
        final List<ConfigurationContractEvidence.Entry> rows = ConfigurationContractEvidence.read(directory("1001"));
        assertThat(rows).extracting(ConfigurationContractEvidence.Entry::getKind).containsExactly("api", "rule");
        assertThat(rows.get(0).getId()).isEqualTo(ConfigurationContractCoverage.signature(PublicContract.class.getMethod("choose", String.class)));
        assertThat(rows.get(1).getId()).isEqualTo("selection.asserted");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getTest()).isEqualTo(SuccessfulInvocation.class.getName() + "#witness");
            assertThat(row.getInvocationId()).contains("[engine:junit-jupiter]", "[method:witness()]", "[configuration-contract-invocation:");
        });
        assertThat(files(directory("1001"))).hasSize(1).allSatisfy(path -> assertThat(path.getFileName().toString()).endsWith(".tsv"));
        assertThatThrownBy(() -> rows.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void failedAbortedDisabledAndLifecycleFailuresNeverPublishPendingClaims() throws Exception {
        final TestExecutionSummary summary = execute("1002", FailedAfterCall.class, AbortedInvocation.class, DisabledInvocation.class,
                FailedBeforeEach.class, FailedAfterEach.class);
        assertThat(summary.getTestsFailedCount()).isEqualTo(3);
        assertThat(summary.getTestsAbortedCount()).isEqualTo(1);
        assertThat(summary.getTestsSkippedCount()).isEqualTo(1);
        assertThat(ConfigurationContractEvidence.read(directory("1002"))).isEmpty();
        assertThat(Files.exists(directory("1002"))).isFalse();
    }

    @Test
    void anInvocationWithNoClaimsCreatesNoEvidenceFile() throws Exception {
        assertThat(execute("1003", NoClaims.class).getTestsSucceededCount()).isEqualTo(1);
        assertThat(Files.exists(directory("1003"))).isFalse();
    }

    @Test
    void mavenRunPrefixRemainsAStringAndKeepsEvidenceInItsExactRunDirectory() throws Exception {
        final String run = "run-1761566400123";
        assertThat(execute(run, DuplicateClaims.class).getTestsSucceededCount()).isEqualTo(1);
        assertThat(ConfigurationContractEvidence.read(directory(run))).hasSize(2);
        assertThat(ConfigurationContractEvidence.read(directory("1761566400123"))).isEmpty();
        assertThat(Files.exists(directory("1761566400123"))).isFalse();
    }

    @Test
    void pendingClaimsAreAtomicAndDuplicatesAreOnlyRemovedWithinTheirInvocation() throws Exception {
        assertThat(execute("1004", DuplicateClaims.class).getTestsSucceededCount()).isEqualTo(1);
        final List<ConfigurationContractEvidence.Entry> rows = ConfigurationContractEvidence.read(directory("1004"));
        assertThat(rows).extracting(ConfigurationContractEvidence.Entry::getKind).containsExactly("rule", "property");
        assertThat(rows).extracting(ConfigurationContractEvidence.Entry::getId).containsExactly("same-id", "same-id");

        assertThat(execute("1005", InvalidPendingClaim.class).getTestsFailedCount()).isEqualTo(1);
        assertThat(ConfigurationContractEvidence.read(directory("1005"))).isEmpty();
        assertThat(Files.exists(directory("1005"))).isFalse();
    }

    @Test
    void invocationFailuresKeepTheirOriginalCauseAndDoNotClaimSuccessfulApiCalls() throws Exception {
        assertThat(execute("1006", ExpectedInvocationFailure.class, NonPublicMethod.class).getTestsSucceededCount()).isEqualTo(2);
        final List<ConfigurationContractEvidence.Entry> rows = ConfigurationContractEvidence.read(directory("1006"));
        assertThat(rows).extracting(ConfigurationContractEvidence.Entry::getKind).containsOnly("rule");
        assertThat(rows).extracting(ConfigurationContractEvidence.Entry::getId)
                .containsExactlyInAnyOrder("original-failure.asserted", "nonpublic-call.rejected");
    }

    @Test
    void manualApiClaimsCannotBypassTheRealInvocationGuard() throws Exception {
        assertThat(execute("1016", ManualApiClaim.class).getTestsFailedCount()).isEqualTo(1);
        assertThat(ConfigurationContractEvidence.read(directory("1016"))).isEmpty();
    }

    @Test
    void freshRunsDoNotReadEarlierEvidenceAndPendingFilesAreInvisible() throws Exception {
        assertThat(execute("1007", DuplicateClaims.class).getTestsSucceededCount()).isEqualTo(1);
        final List<Path> oldFiles = files(directory("1007"));
        final String oldContents = Files.readString(oldFiles.get(0));
        assertThat(ConfigurationContractEvidence.read(directory("1008"))).isEmpty();
        Files.createDirectories(directory("1008"));
        Files.writeString(directory("1008").resolve("unfinished.pending"), "rule\tunfinished\tFixture#method\tuid\n");
        assertThat(ConfigurationContractEvidence.read(directory("1008"))).isEmpty();

        assertThat(execute("1008", DuplicateClaims.class).getTestsSucceededCount()).isEqualTo(1);
        assertThat(ConfigurationContractEvidence.read(directory("1008"))).hasSize(2);
        assertThat(files(directory("1007"))).containsExactlyElementsOf(oldFiles);
        assertThat(Files.readString(oldFiles.get(0))).isEqualTo(oldContents);
    }

    @Test
    void repeatedAndConcurrentInvocationsProduceSeparateImmutableFiles() throws Exception {
        assertThat(execute("1009", RepeatedInvocation.class).getTestsSucceededCount()).isEqualTo(2);
        final List<ConfigurationContractEvidence.Entry> repeated = ConfigurationContractEvidence.read(directory("1009"));
        assertThat(repeated).hasSize(2);
        assertThat(repeated).extracting(ConfigurationContractEvidence.Entry::getInvocationId).doesNotHaveDuplicates();
        assertThat(files(directory("1009"))).hasSize(2);

        final ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            final List<Callable<TestExecutionSummary>> executions = List.of(() -> execute("1010", DuplicateClaims.class),
                    () -> execute("1010", DuplicateClaims.class));
            for (var result : workers.invokeAll(executions)) {
                assertThat(result.get().getFailures()).isEmpty();
            }
        } finally {
            workers.shutdownNow();
        }
        final List<ConfigurationContractEvidence.Entry> concurrent = ConfigurationContractEvidence.read(directory("1010"));
        assertThat(concurrent).hasSize(4);
        assertThat(concurrent.stream().map(ConfigurationContractEvidence.Entry::getInvocationId).distinct()).hasSize(2);
        assertThat(files(directory("1010"))).hasSize(2);
    }

    @Test
    void claimsOutsideTheJUnitThreadScopeFailBeforeCallingAnApi() throws Exception {
        final Api api = new Api();
        final Method method = PublicContract.class.getMethod("choose", String.class);
        final AtomicReference<Throwable> recordFailure = new AtomicReference<>();
        final AtomicReference<Throwable> invocationFailure = new AtomicReference<>();
        final Thread detached = new Thread(() -> {
            try { ConfigurationContractCoverage.record("rule", "outside"); }
            catch (Throwable failure) { recordFailure.set(failure); }
            try { ConfigurationContractCoverage.invoke(api, method, "outside"); }
            catch (Throwable failure) { invocationFailure.set(failure); }
        });
        detached.start();
        detached.join();
        assertThat(recordFailure.get()).isInstanceOf(IllegalStateException.class).hasMessageContaining("active JUnit invocation");
        assertThat(invocationFailure.get()).isInstanceOf(IllegalStateException.class).hasMessageContaining("active JUnit invocation");
        assertThat(api.calls).isZero();
    }

    @Test
    void malformedEvidenceIdentifiersCannotInjectRowsOrColumns() throws Exception {
        for (String invalid : new String[] {"", " ", "first\tsecond", "first\nsecond", "first\rsecond", "first\u0000second", "first\u0085second"}) {
            assertThatThrownBy(() -> ConfigurationContractEvidence.identifier(invalid, "id")).isInstanceOf(IllegalArgumentException.class);
        }
        Files.createDirectories(directory("1011"));
        Files.writeString(directory("1011").resolve("malformed.tsv"), "rule\tid\tFixture#method\tuid\tunbound\tforged\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ConfigurationContractEvidence.read(directory("1011"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void standaloneRunsHaveAnExplicitNameAndCannotFallIntoAMavenRunDirectory() throws Exception {
        final ConfigurationContractCoverage.Scope scope = ConfigurationContractCoverage.begin(module.toString(), null, "StandaloneProbe#run", "standalone-probe");
        try { ConfigurationContractCoverage.record("rule", "standalone.asserted"); }
        finally { ConfigurationContractCoverage.leave(scope); }
        scope.publish();
        final List<Path> runs = files(module.resolve("target/configuration-contracts"));
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).getFileName().toString()).startsWith("standalone-");
        assertThat(ConfigurationContractEvidence.read(runs.get(0))).hasSize(1)
                .allSatisfy(entry -> assertThat(entry.getInventoryFingerprint()).isEqualTo("unbound"));
        assertThat(ConfigurationContractEvidence.read(directory("1012"))).isEmpty();
    }

    @Test
    void fingerprintBindsAllThreeNamedInventoriesAndChangesWithTheirExactContents() throws Exception {
        final Path inventory = inventory();
        final String before = ConfigurationContractEvidence.fingerprint(inventory);
        assertThat(before).matches("[0-9a-f]{64}");
        assertThat(ConfigurationContractEvidence.fingerprint(inventory)).isEqualTo(before);
        for (String name : inventoryNames()) {
            final Path file = inventory.resolve(name);
            final String original = Files.readString(file);
            Files.writeString(file, original + "changed");
            assertThat(ConfigurationContractEvidence.fingerprint(inventory)).as(name).isNotEqualTo(before);
            Files.writeString(file, original);
            assertThat(ConfigurationContractEvidence.fingerprint(inventory)).isEqualTo(before);
        }
    }

    @Test
    void oneInvocationKeepsItsCapturedFingerprintWhenAnInventoryChangesBeforePublication() throws Exception {
        final Path inventory = inventory();
        final String before = ConfigurationContractEvidence.fingerprint(inventory);
        final ConfigurationContractCoverage.Scope scope = ConfigurationContractCoverage.begin(module.toString(), "1013", inventory.toString(),
                "SnapshotProbe#witness", "snapshot-probe");
        try {
            ConfigurationContractCoverage.record("field", "snapshot.first");
            Files.writeString(inventory.resolve("configuration-extra-api-routes.tsv"), "changed after capture");
            ConfigurationContractCoverage.record("rule", "snapshot.second");
        } finally {
            ConfigurationContractCoverage.leave(scope);
        }
        scope.publish();
        assertThat(ConfigurationContractEvidence.read(directory("1013"))).hasSize(2)
                .allSatisfy(entry -> assertThat(entry.getInventoryFingerprint()).isEqualTo(before));
        assertThat(ConfigurationContractEvidence.fingerprint(inventory)).isNotEqualTo(before);
    }

    @Test
    void aBoundScopeWithMissingInventoryFailsRatherThanPublishingUnboundEvidence() throws Exception {
        final Path missing = module.resolve("missing-inventory");
        final LauncherDiscoveryRequestBuilder request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(DuplicateClaims.class))
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                .configurationParameter(ConfigurationContractCoverage.MODULE_PROPERTY, module.toString())
                .configurationParameter(ConfigurationContractCoverage.RUN_PROPERTY, "1014")
                .configurationParameter(ConfigurationContractCoverage.INVENTORY_PROPERTY, missing.toString());
        final SummaryGeneratingListener listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request.build(), listener);
        assertThat(listener.getSummary().getTestsFailedCount()).isEqualTo(1);
        assertThat(ConfigurationContractEvidence.read(directory("1014"))).isEmpty();
    }

    @Test
    void fourColumnEvidenceCannotBeReadAsInventoryBoundProof() throws Exception {
        Files.createDirectories(directory("1015"));
        Files.writeString(directory("1015").resolve("legacy.tsv"), "rule\tid\tFixture#method\tuid\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ConfigurationContractEvidence.read(directory("1015"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signatureUsesDeclaringContractAndErasedArrayParameterAndReturnNames() throws Exception {
        final Method method = PublicContract.class.getMethod("choose", String[].class, int.class);
        assertThat(ConfigurationContractCoverage.signature(method)).isEqualTo(PublicContract.class.getName()
                + "#choose(java.lang.String[],int):java.lang.String[]");
    }

    private TestExecutionSummary execute(final String run, final Class<?>... fixtures) {
        final LauncherDiscoveryRequestBuilder request = LauncherDiscoveryRequestBuilder.request()
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                .configurationParameter(ConfigurationContractCoverage.MODULE_PROPERTY, module.toString())
                .configurationParameter(ConfigurationContractCoverage.RUN_PROPERTY, run);
        for (Class<?> fixture : fixtures) { request.selectors(selectClass(fixture)); }
        final SummaryGeneratingListener listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request.build(), listener);
        return listener.getSummary();
    }

    private Path directory(final String run) { return module.resolve("target/configuration-contracts").resolve(run); }

    private Path inventory() throws IOException {
        final Path inventory = Files.createDirectory(module.resolve("inventory"));
        for (String name : inventoryNames()) { Files.writeString(inventory.resolve(name), "contents for " + name); }
        return inventory;
    }

    private static List<String> inventoryNames() {
        return List.of("configuration-api-routes.tsv", "configuration-extra-api-routes.tsv", "configuration-contract-obligations.tsv");
    }

    private static List<Path> files(final Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) { return files.sorted().collect(Collectors.toList()); }
    }

    public interface PublicContract {
        String choose(String value);
        String[] choose(String[] values, int count);
        void fail() throws IOException;
    }

    public static final class Api implements PublicContract {
        private int calls;
        private final IOException failure = new IOException("original invocation failure");
        @Override public String choose(final String value) { calls++; return value; }
        @Override public String[] choose(final String[] values, final int count) { calls++; return values; }
        @Override public void fail() throws IOException { throw failure; }
        private void hidden() { calls++; }
    }

    static final class SuccessfulInvocation {
        @Test void witness() throws Exception {
            final Api api = new Api();
            assertThat(ConfigurationContractCoverage.invoke(api, PublicContract.class.getMethod("choose", String.class), "chosen")).isEqualTo("chosen");
            assertThat(api.calls).isEqualTo(1);
            ConfigurationContractCoverage.record("rule", "selection.asserted");
        }
    }

    static final class FailedAfterCall {
        @Test void witness() throws Exception {
            ConfigurationContractCoverage.invoke(new Api(), PublicContract.class.getMethod("choose", String.class), "chosen");
            ConfigurationContractCoverage.record("rule", "must-not-escape");
            assertThat(false).isTrue();
        }
    }

    static final class AbortedInvocation {
        @Test void witness() {
            ConfigurationContractCoverage.record("rule", "aborted");
            assumeTrue(false);
        }
    }

    static final class DisabledInvocation {
        @Disabled @Test void witness() { ConfigurationContractCoverage.record("rule", "disabled"); }
    }

    static final class FailedBeforeEach {
        @BeforeEach void setup() {
            ConfigurationContractCoverage.record("rule", "failed-setup");
            throw new IllegalStateException("setup failed");
        }
        @Test void witness() { throw new AssertionError("Must not execute"); }
    }

    static final class FailedAfterEach {
        @Test void witness() { ConfigurationContractCoverage.record("rule", "failed-cleanup"); }
        @AfterEach void cleanup() { throw new IllegalStateException("cleanup failed"); }
    }

    static final class NoClaims {
        @Test void witness() { assertThat(true).isTrue(); }
    }

    static final class DuplicateClaims {
        @Test void witness() {
            ConfigurationContractCoverage.record("rule", "same-id");
            ConfigurationContractCoverage.record("rule", "same-id");
            ConfigurationContractCoverage.record("property", "same-id");
        }
    }

    static final class InvalidPendingClaim {
        @Test void witness() {
            ConfigurationContractCoverage.record("rule", "first");
            ConfigurationContractCoverage.record("rule", "forged\nrow");
        }
    }

    static final class ManualApiClaim {
        @Test void witness() {
            ConfigurationContractCoverage.record("api", "Uncalled#method():void");
        }
    }

    static final class ExpectedInvocationFailure {
        @Test void witness() throws Exception {
            final Api api = new Api();
            final Method method = PublicContract.class.getMethod("fail");
            assertThatThrownBy(() -> ConfigurationContractCoverage.invoke(api, method)).isSameAs(api.failure);
            ConfigurationContractCoverage.record("rule", "original-failure.asserted");
        }
    }

    static final class NonPublicMethod {
        @Test void witness() throws Exception {
            final Api api = new Api();
            final Method method = Api.class.getDeclaredMethod("hidden");
            assertThatThrownBy(() -> ConfigurationContractCoverage.invoke(api, method)).isInstanceOf(IllegalArgumentException.class);
            assertThat(api.calls).isZero();
            ConfigurationContractCoverage.record("rule", "nonpublic-call.rejected");
        }
    }

    static final class RepeatedInvocation {
        @RepeatedTest(2) void witness() { ConfigurationContractCoverage.record("rule", "repeated"); }
    }
}
