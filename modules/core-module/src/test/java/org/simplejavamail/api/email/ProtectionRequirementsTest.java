package org.simplejavamail.api.email;

import org.junit.jupiter.api.Test;
import org.simplejavamail.internal.email.DecryptionInspectionImpl;
import org.simplejavamail.internal.email.ProtectionInspectionImpl;
import org.simplejavamail.internal.email.ReceivedEmailInspection;
import org.simplejavamail.internal.email.SignatureInspectionImpl;
import org.simplejavamail.internal.email.SignatureObservationImpl;
import org.simplejavamail.internal.email.SigningKeyFingerprint;
import org.simplejavamail.internal.email.ValueInspectionImpl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProtectionRequirementsTest {
    private static final String APPROVED = "0123456789ABCDEF0123456789ABCDEF01234567";
    private static final String OTHER = "FEDCBA9876543210FEDCBA9876543210FEDCBA98";
    private final ApprovedSigners approved = ApprovedSigners.builder().addOpenPgpSigningKeyFingerprint(APPROVED).build();
    private final ProtectionRequirements signature = ProtectionRequirements.builder().requireVerifiedSignatureFrom(approved).build();

    @Test
    void aMixedValueCanHaveOneFullyCoveringApprovedWitnessButStrictPolicyRefusesTheInvalidNeighbor() {
        final ValueInspection value = value(signature(APPROVED, SignatureVerification.VERIFIED, ProtectionCoverage.FULL),
                signature(OTHER, SignatureVerification.INVALID, ProtectionCoverage.FULL));
        assertThat(value.getSignatureStatus()).isEqualTo(SignatureStatus.MIXED);
        assertThat(signature.check(value).getOutcome()).isEqualTo(ProtectionOutcome.SATISFIED);
        assertThat(signature.check(value).getReasons()).isEmpty();
        final ProtectionCheck strict = signature.toBuilder().requireAllSignaturesVerified().build().check(value);
        assertThat(strict.getOutcome()).isEqualTo(ProtectionOutcome.NOT_SATISFIED);
        assertThat(strict.getReasons()).extracting(ProtectionReason::getCode).contains(ProtectionReasonCode.STRICT_SIGNATURE_INVALID);
    }

    @Test
    void approvalAndVerificationCannotBeBorrowedFromDifferentSignatures() {
        final ValueInspection value = value(signature(OTHER, SignatureVerification.VERIFIED, ProtectionCoverage.FULL),
                signature(APPROVED, SignatureVerification.INVALID, ProtectionCoverage.FULL));
        final ProtectionCheck result = signature.check(value);
        assertThat(result.getOutcome()).isEqualTo(ProtectionOutcome.NOT_SATISFIED);
        assertThat(result.getReasons()).extracting(ProtectionReason::getCode)
                .containsExactly(ProtectionReasonCode.SIGNER_NOT_APPROVED, ProtectionReasonCode.SIGNATURE_INVALID);
    }

    @Test
    void strictPolicyKeepsUnavailableVerificationIndeterminateDespiteAKnownApprovedWitness() {
        final ValueInspection mixed = value(signature(APPROVED, SignatureVerification.VERIFIED, ProtectionCoverage.FULL),
                signature(OTHER, SignatureVerification.UNVERIFIED, ProtectionCoverage.FULL));
        assertThat(signature.check(mixed).isSatisfied()).isTrue();
        final ProtectionCheck strict = signature.toBuilder().requireAllSignaturesVerified().build().check(mixed);
        assertThat(strict.getOutcome()).isEqualTo(ProtectionOutcome.INDETERMINATE);
        assertThat(strict.getReasons()).extracting(ProtectionReason::getCode).containsExactly(ProtectionReasonCode.STRICT_SIGNATURE_UNVERIFIED);
    }

    @Test
    void knownPartialCoverageFailsWhereUnknownCoverageCannotDecide() {
        final ValueInspection partial = value(signature(APPROVED, SignatureVerification.VERIFIED, ProtectionCoverage.PARTIAL));
        assertThat(partial.getSignatureStatus()).isEqualTo(SignatureStatus.MIXED);
        assertThat(signature.check(partial).getOutcome()).isEqualTo(ProtectionOutcome.NOT_SATISFIED);
        final ValueInspection unknown = value(signature(APPROVED, SignatureVerification.VERIFIED, ProtectionCoverage.UNKNOWN));
        assertThat(unknown.getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(signature.check(unknown).getOutcome()).isEqualTo(ProtectionOutcome.INDETERMINATE);
    }

    @Test
    void unavailableVerificationIsDifferentFromInvalidityAndAnUnapprovedKeyCannotBecomeApprovedByVerification() {
        assertThat(signature.check(value(signature(APPROVED, SignatureVerification.UNVERIFIED, ProtectionCoverage.FULL))).getOutcome())
                .isEqualTo(ProtectionOutcome.INDETERMINATE);
        assertThat(signature.check(value(signature(APPROVED, SignatureVerification.INVALID, ProtectionCoverage.FULL))).getOutcome())
                .isEqualTo(ProtectionOutcome.NOT_SATISFIED);
        assertThat(signature.check(value(signature(OTHER, SignatureVerification.UNVERIFIED, ProtectionCoverage.FULL))).getOutcome())
                .isEqualTo(ProtectionOutcome.NOT_SATISFIED);
    }

    @Test
    void incompleteInspectionMayHideAWitnessAndStrictPolicyRequiresCompleteInspectionEvenWithAKnownWitness() {
        final ValueInspection unknown = ValueInspectionImpl.builder().availability(ValueAvailability.PRESENT).build();
        assertThat(signature.check(unknown).getOutcome()).isEqualTo(ProtectionOutcome.INDETERMINATE);
        final ValueInspectionImpl known = (ValueInspectionImpl) value(signature(APPROVED, SignatureVerification.VERIFIED, ProtectionCoverage.FULL));
        final ValueInspection incomplete = known.toBuilder().signatureInspectionComplete(false).build();
        assertThat(incomplete.getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(signature.check(incomplete).isSatisfied()).isTrue();
        assertThat(signature.toBuilder().requireAllSignaturesVerified().build().check(incomplete).getOutcome())
                .isEqualTo(ProtectionOutcome.INDETERMINATE);
    }

    @Test
    void changingCapturedAssociationsCannotLeaveAnOldCachedSummaryOnTheNewSnapshot() {
        final ValueInspectionImpl verified = (ValueInspectionImpl) value(signature(APPROVED, SignatureVerification.VERIFIED, ProtectionCoverage.FULL));
        assertThat(verified.toBuilder().signatures(List.of()).build().getSignatureStatus()).isEqualTo(SignatureStatus.UNSIGNED);
        assertThat(verified.toBuilder().signatures(List.of(signature(APPROVED, SignatureVerification.INVALID, ProtectionCoverage.FULL)))
                .build().getSignatureStatus()).isEqualTo(SignatureStatus.INVALID);
        assertThat(verified.getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
    }

    @Test
    void emptyPresentContentCanPassButEstablishedAbsenceAndUnknownHistoryCannot() {
        final ValueInspectionImpl present = (ValueInspectionImpl) value(signature(APPROVED, SignatureVerification.VERIFIED, ProtectionCoverage.FULL));
        assertThat(signature.check(present).isSatisfied()).isTrue();
        assertThat(signature.check(present.toBuilder().availability(ValueAvailability.ABSENT).build()).getOutcome())
                .isEqualTo(ProtectionOutcome.NOT_SATISFIED);
        assertThat(signature.check(ValueInspectionImpl.unknown()).getOutcome()).isEqualTo(ProtectionOutcome.INDETERMINATE);
        final ValueInspection unsigned = ValueInspectionImpl.builder().availability(ValueAvailability.PRESENT).signatureInspectionComplete(true).build();
        assertThat(signature.check(unsigned).getOutcome()).isEqualTo(ProtectionOutcome.NOT_SATISFIED);
    }

    @Test
    void decryptionAcceptsCollectivelyRecoveredContentWithoutRequiringOneCommonEncryption() {
        final ProtectionRequirements decryption = ProtectionRequirements.builder().requireDecryptedContent().build();
        final DecryptionInspection firstFragment = DecryptionInspectionImpl.builder().family(ProtectionFamily.SMIME).status(DecryptionStatus.DECRYPTED)
                .coverage(ProtectionCoverage.PARTIAL).build();
        final DecryptionInspection secondFragment = DecryptionInspectionImpl.builder().family(ProtectionFamily.OPENPGP).status(DecryptionStatus.DECRYPTED)
                .coverage(ProtectionCoverage.PARTIAL).build();
        final ValueInspectionImpl recovered = ValueInspectionImpl.builder().availability(ValueAvailability.PRESENT)
                .encryptionStatus(EncryptionStatus.DECRYPTED).decryptions(List.of(firstFragment, secondFragment)).build();
        assertThat(decryption.check(recovered).isSatisfied()).isTrue();
        assertThat(recovered.getDecryptions()).extracting(DecryptionInspection::getCoverage).containsExactly(ProtectionCoverage.PARTIAL, ProtectionCoverage.PARTIAL);
        assertThat(decryption.check(recovered.toBuilder().encryptionStatus(EncryptionStatus.UNKNOWN)
                .decryptions(List.of(firstFragment)).build()).getOutcome()).isEqualTo(ProtectionOutcome.INDETERMINATE);
        assertThat(decryption.check(recovered.toBuilder().encryptionStatus(EncryptionStatus.ENCRYPTED).build()).getOutcome())
                .isEqualTo(ProtectionOutcome.NOT_SATISFIED);
        assertThat(decryption.check(recovered.toBuilder().encryptionStatus(EncryptionStatus.NOT_ENCRYPTED).build()).getOutcome())
                .isEqualTo(ProtectionOutcome.NOT_SATISFIED);
    }

    @Test
    void aKnownUnmetConditionWinsOverAnUncertainRequiredCondition() {
        final ValueInspectionImpl unverified = (ValueInspectionImpl) value(signature(APPROVED, SignatureVerification.UNVERIFIED, ProtectionCoverage.FULL));
        final ProtectionCheck result = signature.toBuilder().requireDecryptedContent().build().check(
                unverified.toBuilder().encryptionStatus(EncryptionStatus.NOT_ENCRYPTED).build());
        assertThat(result.getOutcome()).isEqualTo(ProtectionOutcome.NOT_SATISFIED);
        assertThat(result.getReasons()).extracting(ProtectionReason::getCode)
                .contains(ProtectionReasonCode.SIGNATURE_UNVERIFIED, ProtectionReasonCode.DECRYPTION_REQUIRED);
    }

    @Test
    void builderInspectionAllowsUnfinishedChoicesButBuildRejectsVacuousAndStrictOnlyPolicies() {
        final ProtectionRequirements.Builder unfinished = ProtectionRequirements.builder().requireAllSignaturesVerified();
        assertThat(unfinished.isAllSignaturesVerifiedRequired()).isTrue();
        assertThat(unfinished.getApprovedSigners()).isNull();
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(unfinished::build);
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> ProtectionRequirements.builder().build());
        final ProtectionRequirements.Builder configured = signature.toBuilder().requireDecryptedContent();
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> configured.requireVerifiedSignatureFrom(null));
        assertThat(configured.getApprovedSigners()).isSameAs(approved);
        configured.clearVerifiedSignatureRequirement();
        assertThat(configured.build().isDecryptedContentRequired()).isTrue();
        assertThat(signature.getApprovedSigners()).isSameAs(approved);
    }

    @Test
    void fullOpenPgpFingerprintsAreCapturedWithoutImplicitSubkeyApprovalAndShortIdsFailAtBuild() {
        final ApprovedSigners.Builder unfinished = ApprovedSigners.builder().addOpenPgpSigningKeyFingerprint("1234ABCD");
        assertThat(unfinished.getOpenPgpSigningKeyFingerprints()).containsExactly("1234ABCD");
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(unfinished::build);
        final ApprovedSigners normalized = ApprovedSigners.builder().addOpenPgpSigningKeyFingerprint(APPROVED.toLowerCase()).build();
        assertThat(normalized.getOpenPgpSigningKeyFingerprints()).containsExactly(APPROVED);
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> normalized.getOpenPgpSigningKeyFingerprints().clear());
        assertThat(signature.check(value(signature(OTHER, SignatureVerification.VERIFIED, ProtectionCoverage.FULL))).isSatisfied()).isFalse();
    }

    @Test
    void renewedCertificatesAndDifferentRsaProviderEncodingsApproveTheSameActualKey() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        final RSAPublicKey key = (RSAPublicKey) generator.generateKeyPair().getPublic();
        final RSAPublicKey alternate = mock(RSAPublicKey.class);
        when(alternate.getModulus()).thenReturn(key.getModulus());
        when(alternate.getPublicExponent()).thenReturn(key.getPublicExponent());
        when(alternate.getEncoded()).thenReturn(new byte[] {1, 2, 3});
        final ApprovedSigners pinned = ApprovedSigners.builder().addSmimeCertificate(certificate(key)).addSmimeCertificate(certificate(alternate)).build();
        assertThat(pinned.getSmimeSigningKeyFingerprints()).containsExactly(SigningKeyFingerprint.forSmime(key));
        final SignatureObservation observation = SignatureObservationImpl.builder().family(ProtectionFamily.SMIME)
                .status(SignatureVerification.VERIFIED).signingKeyFingerprint(SigningKeyFingerprint.forSmime(alternate)).build();
        final ProtectionRequirements policy = ProtectionRequirements.builder().requireVerifiedSignatureFrom(pinned).build();
        assertThat(policy.check(value(SignatureInspectionImpl.builder().observation(observation).coverage(ProtectionCoverage.FULL).build())).isSatisfied()).isTrue();
        final PublicKey replacement = generator.generateKeyPair().getPublic();
        assertThat(pinned.getSmimeSigningKeyFingerprints()).doesNotContain(SigningKeyFingerprint.forSmime(replacement));
    }

    @Test
    void observationsAreSharedAcrossViewsAndSnapshotsWithoutRetainingMutableListAliases() throws Exception {
        final SignatureInspection signature = signature(APPROVED, SignatureVerification.VERIFIED, ProtectionCoverage.FULL);
        final List<SignatureInspection> supplied = new ArrayList<>(List.of(signature));
        final ValueInspection field = ValueInspectionImpl.builder().availability(ValueAvailability.PRESENT)
                .signatureInspectionComplete(true).signatures(supplied).build();
        final ReceivedEmailInspection inspection = ReceivedEmailInspection.builder().plainText(field).htmlText(field)
                .attachments(List.of(field, ValueInspectionImpl.unknown())).smime(ProtectionInspectionImpl.builder().family(ProtectionFamily.SMIME).build())
                .openPgp(ProtectionInspectionImpl.builder().family(ProtectionFamily.OPENPGP).signatures(List.of(signature.getObservation())).build()).build();
        supplied.clear();
        assertThat(inspection.getPlainText().getSignatures()).hasSize(1);
        assertThat(inspection.getPlainText().getSignatures().get(0).getObservation()).isSameAs(inspection.getOpenPgp().getSignatures().get(0));
        assertThat(inspection.getOriginalAttachment(0)).isNull();
        assertThat(inspection.getAttachment(1).getAvailability()).isEqualTo(ValueAvailability.UNKNOWN);
        assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(() -> inspection.getAttachment(2));
        final ReceivedEmailInspection restored = roundTrip(inspection);
        assertThat(restored.getPlainText().getSignatures().get(0).getObservation()).isSameAs(restored.getOpenPgp().getSignatures().get(0));
        assertThat(signature.getObservation().getReference()).isEqualTo(restored.getOpenPgp().getSignatures().get(0).getReference());
        assertThat(roundTrip(this.signature).check(restored.getAttachment(0)).isSatisfied()).isTrue();
    }

    private static SignatureInspection signature(final String key, final SignatureVerification status, final ProtectionCoverage coverage) {
        return SignatureInspectionImpl.builder().observation(SignatureObservationImpl.builder().family(ProtectionFamily.OPENPGP)
                .status(status).signingKeyFingerprint(key).build()).coverage(coverage).build();
    }

    private static ValueInspection value(final SignatureInspection... signatures) {
        return ValueInspectionImpl.builder().availability(ValueAvailability.PRESENT).signatureInspectionComplete(true).signatures(List.of(signatures)).build();
    }

    private static X509Certificate certificate(final PublicKey key) {
        final X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getPublicKey()).thenReturn(key);
        return certificate;
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(final T value) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(value); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { return (T) input.readObject(); }
    }
}
