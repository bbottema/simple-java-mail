package testutil.configcontracts;

import org.junit.jupiter.api.Test;
import testutil.configcontracts.ConfigurationContractVerifier.Obligation;
import testutil.configcontracts.ConfigurationContractVerifier.Proof;
import testutil.configcontracts.ConfigurationContractVerifier.Result;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationContractVerifierTest {
    private static final String FINGERPRINT = "a".repeat(64);
    private static final String MAIN = "simple-java-mail";
    private static final String PROVIDER = "angus-mail-provider-module";

    @Test
    void completeBoundSuccessfulProofsCoverTheirExactKindsIdsAndModules() {
        final List<Obligation> required = List.of(obligation("api", "withSubject", MAIN), obligation("field", "email.subject", MAIN),
                obligation("alias", "mail.smtp.timeout", PROVIDER));
        final List<Proof> proofs = List.of(proof("api", "withSubject", MAIN), proof("field", "email.subject", MAIN),
                proof("alias", "mail.smtp.timeout", PROVIDER));
        final Result result = ConfigurationContractVerifier.verify(required, proofs, FINGERPRINT);
        assertThat(result.getRequiredCount()).isEqualTo(3);
        assertThat(result.getCoveredCount()).isEqualTo(3);
    }

    @Test
    void missingRuleCannotBeSatisfiedByAnApiCall() {
        final List<Obligation> required = List.of(obligation("api", "withSubject", MAIN), obligation("rule", "template.precedence", MAIN));
        assertThatThrownBy(() -> ConfigurationContractVerifier.verify(required, List.of(proof("api", "withSubject", MAIN)), FINGERPRINT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("missing obligation rule:template.precedence");
    }

    @Test
    void missingFieldCannotBeSatisfiedByAnotherFieldsRuleProof() {
        final List<Obligation> required = List.of(obligation("rule", "template.precedence", MAIN), obligation("field", "email.subject", MAIN));
        assertThatThrownBy(() -> ConfigurationContractVerifier.verify(required, List.of(proof("rule", "template.precedence", MAIN)), FINGERPRINT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("missing obligation field:email.subject");
    }

    @Test
    void oneOverloadNeverCoversAnotherErasedSignature() {
        final String first = "Builder#withHeader(java.lang.String,java.lang.Object):Builder";
        final String second = "Builder#withHeader(java.lang.String,java.lang.Object,boolean):Builder";
        assertThatThrownBy(() -> ConfigurationContractVerifier.verify(List.of(obligation("api", first, MAIN), obligation("api", second, MAIN)),
                List.of(proof("api", first, MAIN)), FINGERPRINT)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing obligation api:" + second);
    }

    @Test
    void earlierInventoryFingerprintCannotSatisfyTheCurrentInventory() {
        assertThatThrownBy(() -> ConfigurationContractVerifier.verify(List.of(obligation("rule", "selection", MAIN)),
                List.of(proof("rule", "selection", MAIN, true, "b".repeat(64))), FINGERPRINT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("inventory fingerprint mismatch")
                .hasMessageContaining("missing obligation rule:selection");
    }

    @Test
    void unboundStandaloneEvidenceCannotSatisfyABoundInventory() {
        assertThatThrownBy(() -> ConfigurationContractVerifier.verify(List.of(obligation("rule", "selection", MAIN)),
                List.of(proof("rule", "selection", MAIN, true, "unbound")), FINGERPRINT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("inventory fingerprint mismatch");
    }

    @Test
    void aKnownIdWithTheWrongKindIsRejected() {
        assertThatThrownBy(() -> ConfigurationContractVerifier.verify(List.of(obligation("field", "email.subject", MAIN)),
                List.of(proof("rule", "email.subject", MAIN)), FINGERPRINT)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wrong kind").hasMessageContaining("missing obligation field:email.subject");
    }

    @Test
    void aProofFromAnotherModuleCannotSatisfyTheDeclaredOwner() {
        assertThatThrownBy(() -> ConfigurationContractVerifier.verify(List.of(obligation("alias", "mail.smtp.timeout", PROVIDER)),
                List.of(proof("alias", "mail.smtp.timeout", MAIN)), FINGERPRINT)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wrong module").hasMessageContaining(PROVIDER).hasMessageContaining(MAIN);
    }

    @Test
    void duplicateObligationsAreRejectedEvenWhenTheyNameTheSameModule() {
        final Obligation required = obligation("rule", "selection", MAIN);
        assertThatThrownBy(() -> ConfigurationContractVerifier.verify(List.of(required, required), List.of(proof("rule", "selection", MAIN)), FINGERPRINT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate obligation rule:selection");
    }

    @Test
    void changingTheModuleDoesNotMakeADuplicateObligationDistinct() {
        assertThatThrownBy(() -> ConfigurationContractVerifier.verify(List.of(obligation("rule", "selection", MAIN), obligation("rule", "selection", PROVIDER)),
                List.of(proof("rule", "selection", MAIN)), FINGERPRINT)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate obligation rule:selection");
    }

    @Test
    void duplicateProofsCountOnceAndCannotInflateTheDenominator() {
        final Proof proof = proof("rule", "selection", MAIN);
        final Result result = ConfigurationContractVerifier.verify(List.of(obligation("rule", "selection", MAIN)), List.of(proof, proof, proof), FINGERPRINT);
        assertThat(result.getRequiredCount()).isEqualTo(1);
        assertThat(result.getCoveredCount()).isEqualTo(1);
    }

    @Test
    void duplicateApiProofsCannotConcealAnUncoveredField() {
        final Proof proof = proof("api", "withSubject", MAIN);
        assertThatThrownBy(() -> ConfigurationContractVerifier.verify(List.of(obligation("api", "withSubject", MAIN), obligation("field", "email.subject", MAIN)),
                List.of(proof, proof, proof), FINGERPRINT)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing obligation field:email.subject");
    }

    @Test
    void failedTestProofIsRejected() { rejectsUnsuccessfulProof("Fixture#failed"); }

    @Test
    void skippedTestProofIsRejected() { rejectsUnsuccessfulProof("Fixture#skipped"); }

    @Test
    void unexecutedOrEarlierRunTestProofIsRejected() { rejectsUnsuccessfulProof("Fixture#notExecutedInThisRun"); }

    @Test
    void aValidDuplicateCannotHideAnInvalidWitnessForTheSameObligation() {
        assertThatThrownBy(() -> ConfigurationContractVerifier.verify(List.of(obligation("rule", "selection", MAIN)),
                List.of(proof("rule", "selection", MAIN), proof("rule", "selection", MAIN, false, FINGERPRINT)), FINGERPRINT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not from a current successful JUnit invocation");
    }

    @Test
    void unknownProofIsRejectedEvenWhenEveryRequiredObligationIsOtherwiseCovered() {
        assertThatThrownBy(() -> ConfigurationContractVerifier.verify(List.of(obligation("rule", "selection", MAIN)),
                List.of(proof("rule", "selection", MAIN), proof("rule", "invented", MAIN)), FINGERPRINT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unknown proof rule:invented");
    }

    @Test
    void emptyInventoryCannotReportCompleteCoverage() {
        assertThatThrownBy(() -> ConfigurationContractVerifier.verify(List.of(), List.of(), FINGERPRINT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("obligation inventory is empty");
    }

    @Test
    void anUnboundExpectedFingerprintCannotEnableStandaloneCompleteness() {
        assertThatThrownBy(() -> ConfigurationContractVerifier.verify(List.of(obligation("rule", "selection", MAIN)),
                List.of(proof("rule", "selection", MAIN, true, "unbound")), "unbound"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bound SHA-256 inventory fingerprint");
    }

    private static void rejectsUnsuccessfulProof(final String test) {
        final ConfigurationContractEvidence.Entry entry = new ConfigurationContractEvidence.Entry("rule", "selection", test, "invocation", FINGERPRINT);
        assertThatThrownBy(() -> ConfigurationContractVerifier.verify(List.of(obligation("rule", "selection", MAIN)),
                List.of(new Proof(MAIN, entry, false)), FINGERPRINT)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not from a current successful JUnit invocation").hasMessageContaining(test);
    }

    private static Obligation obligation(final String kind, final String id, final String module) { return new Obligation(kind, id, module); }
    private static Proof proof(final String kind, final String id, final String module) { return proof(kind, id, module, true, FINGERPRINT); }
    private static Proof proof(final String kind, final String id, final String module, final boolean successful, final String fingerprint) {
        return new Proof(module, new ConfigurationContractEvidence.Entry(kind, id, "Fixture#asserted", "invocation", fingerprint), successful);
    }
}
