package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import testutil.configcontracts.ConfigurationContractCoverage;
import testutil.configcontracts.ConfigurationContractVerifier;
import testutil.configcontracts.ConfigurationContractVerifier.Obligation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationContractGateTest {
    private static final String MODULE = "simple-java-mail";
    private static final String CURRENT = "run-1761566400123";
    private static final String EARLIER = "run-1761566400000";
    private static final String FINGERPRINT = "a".repeat(64);
    private static final String TEST_CLASS = "org.simplejavamail.contractfixture.BehaviorFixture";
    private static final List<Obligation> REQUIRED = List.of(new Obligation("rule", "selection", MODULE));

    @TempDir Path root;

    @Test
    void currentSuccessfulSurefireResultAndBoundEvidenceSatisfyTheGate() throws Exception {
        report(CURRENT, testcase("select()", ""));
        evidence(CURRENT, "select", invocation("select()"), FINGERPRINT);
        final ConfigurationContractVerifier.Result result = verify();
        assertThat(result.getRequiredCount()).isEqualTo(1);
        assertThat(result.getCoveredCount()).isEqualTo(1);
    }

    @Test
    void earlierRunDirectoriesAndReportsCannotSatisfyTheCurrentRun() throws Exception {
        report(EARLIER, testcase("select()", ""));
        evidence(EARLIER, "select", invocation("select()"), FINGERPRINT);
        assertThatThrownBy(this::verify).isInstanceOf(IllegalStateException.class).hasMessageContaining("missing obligation rule:selection");
    }

    @Test
    void aCurrentEvidenceFileCannotBorrowASuccessFromEarlierSurefireXml() throws Exception {
        report(EARLIER, testcase("select()", ""));
        evidence(CURRENT, "select", invocation("select()"), FINGERPRINT);
        assertThatThrownBy(this::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not from a current successful JUnit invocation");
    }

    @Test
    void aFailedCurrentWitnessCannotCountEvenWhenItsEvidenceFileExists() throws Exception {
        report(CURRENT, testcase("select()", "<failure message=\"assertion failed\"/>"));
        evidence(CURRENT, "select", invocation("select()"), FINGERPRINT);
        assertThatThrownBy(this::verify).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSkippedCurrentWitnessCannotCountEvenWhenItsEvidenceFileExists() throws Exception {
        report(CURRENT, testcase("select()", "<skipped message=\"condition\"/>"));
        evidence(CURRENT, "select", invocation("select()"), FINGERPRINT);
        assertThatThrownBy(this::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not from a current successful JUnit invocation");
    }

    @Test
    void anUnexecutedMethodCannotBorrowAnotherMethodsSuccessfulResult() throws Exception {
        report(CURRENT, testcase("anotherMethod()", ""));
        evidence(CURRENT, "select", invocation("select()"), FINGERPRINT);
        assertThatThrownBy(this::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not from a current successful JUnit invocation");
    }

    @Test
    void aSuccessfulMethodWithAnEarlierInventoryFingerprintStillFails() throws Exception {
        report(CURRENT, testcase("select()", ""));
        evidence(CURRENT, "select", invocation("select()"), "b".repeat(64));
        assertThatThrownBy(this::verify).isInstanceOf(IllegalStateException.class).hasMessageContaining("inventory fingerprint mismatch");
    }

    @Test
    void unboundStandaloneEvidenceCannotSatisfyACurrentMavenInvocation() throws Exception {
        report(CURRENT, testcase("select()", ""));
        evidence(CURRENT, "select", invocation("select()"), "unbound");
        assertThatThrownBy(this::verify).isInstanceOf(IllegalStateException.class).hasMessageContaining("inventory fingerprint mismatch");
    }

    @Test
    void anotherParameterizedInvocationCannotSatisfyTheEvidenceInvocation() throws Exception {
        report(CURRENT, testcase("select(String)[1]", ""));
        evidence(CURRENT, "select", parameterInvocation(2), FINGERPRINT);
        assertThatThrownBy(this::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not from a current successful JUnit invocation");
    }

    @Test
    void theMatchingParameterizedInvocationCanSatisfyTheEvidenceInvocation() throws Exception {
        report(CURRENT, testcase("select(String)[1]", "") + testcase("select(String)[2]", ""));
        evidence(CURRENT, "select", parameterInvocation(2), FINGERPRINT);
        assertThat(verify().getCoveredCount()).isEqualTo(1);
    }

    @Test
    void aPassingSiblingCannotHideAFailedParameterizedInvocation() throws Exception {
        report(CURRENT, testcase("select(String)[1]", "") + testcase("select(String)[2]", "<error message=\"failed second invocation\"/>"));
        evidence(CURRENT, "select", parameterInvocation(2), FINGERPRINT);
        assertThatThrownBy(this::verify).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mainRejectsStandaloneRunNamesBeforeLookingForInventoryOrEvidence() {
        assertThatThrownBy(() -> ConfigurationContractGate.main(new String[] {root.toString(), "standalone-probe", "library"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("current Maven session");
    }

    private ConfigurationContractVerifier.Result verify() throws Exception {
        return ConfigurationContractGate.verify(root, CURRENT, "library", REQUIRED, FINGERPRINT);
    }

    private void report(final String run, final String cases) throws IOException {
        final Path directory = root.resolve("modules").resolve(MODULE).resolve("target/surefire-reports");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("TEST-BehaviorFixture.xml"), "<testsuite><properties><property name=\""
                + ConfigurationContractCoverage.RUN_PROPERTY + "\" value=\"" + run + "\"/></properties>" + cases + "</testsuite>", StandardCharsets.UTF_8);
    }

    private void evidence(final String run, final String method, final String invocation, final String fingerprint) throws IOException {
        final Path directory = root.resolve("modules").resolve(MODULE).resolve("target/configuration-contracts").resolve(run);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("invocation.tsv"), "rule\tselection\t" + TEST_CLASS + "#" + method + "\t"
                + invocation + "\t" + fingerprint + "\n", StandardCharsets.UTF_8);
    }

    private static String testcase(final String method, final String outcome) {
        return "<testcase classname=\"" + TEST_CLASS + "\" name=\"" + method + "\">" + outcome + "</testcase>";
    }

    private static String invocation(final String method) {
        return "[engine:junit-jupiter]/[class:" + TEST_CLASS + "]/[method:" + method + "][configuration-contract-invocation:fixture]";
    }

    private static String parameterInvocation(final int index) {
        return "[engine:junit-jupiter]/[class:" + TEST_CLASS + "]/[test-template:select(java.lang.String)]/[test-template-invocation:#"
                + index + "][configuration-contract-invocation:fixture]";
    }
}
