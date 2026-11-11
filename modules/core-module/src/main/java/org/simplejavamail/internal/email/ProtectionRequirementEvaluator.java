package org.simplejavamail.internal.email;

import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.email.ApprovedSigners;
import org.simplejavamail.api.email.EmailSourceElement;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.ProtectionCheck;
import org.simplejavamail.api.email.ProtectionCoverage;
import org.simplejavamail.api.email.ProtectionFamily;
import org.simplejavamail.api.email.ProtectionOutcome;
import org.simplejavamail.api.email.ProtectionReason;
import org.simplejavamail.api.email.ProtectionReasonCode;
import org.simplejavamail.api.email.ProtectionRequirements;
import org.simplejavamail.api.email.SignatureInspection;
import org.simplejavamail.api.email.SignatureObservation;
import org.simplejavamail.api.email.SignatureVerification;
import org.simplejavamail.api.email.ValueAvailability;
import org.simplejavamail.api.email.ValueInspection;

import java.util.ArrayList;
import java.util.List;

/** Pure evaluation over captured read contracts. Key normalization happens before this boundary. */
@ApiStatus.Internal
public final class ProtectionRequirementEvaluator {
    private ProtectionRequirementEvaluator() { }

    public static ProtectionCheck check(final ProtectionRequirements requirements, final ValueInspection value) {
        final Evaluation evaluation = new Evaluation();
        if (value.getAvailability() == ValueAvailability.ABSENT) {
            evaluation.unmet(ProtectionReasonCode.VALUE_ABSENT, "The selected value is established absent", value.getSources());
            return evaluation.result();
        }
        if (value.getAvailability() != ValueAvailability.PRESENT) {
            evaluation.uncertain(ProtectionReasonCode.EVIDENCE_UNAVAILABLE, "The selected value is unavailable or was not inspected", value.getSources());
        }
        if (requirements.getApprovedSigners() != null) inspectApprovedSignature(requirements.getApprovedSigners(), value, evaluation);
        if (requirements.isDecryptedContentRequired()) inspectDecryption(value, evaluation);
        if (requirements.isAllSignaturesVerifiedRequired()) inspectAllSignatures(value, evaluation);
        return evaluation.result();
    }

    private static void inspectApprovedSignature(final ApprovedSigners approved, final ValueInspection value, final Evaluation evaluation) {
        boolean possibleWitness = !value.isSignatureInspectionComplete();
        for (SignatureInspection signature : value.getSignatures()) {
            final SignatureObservation observation = signature.getObservation();
            final boolean approvedKey = approvedKey(approved, observation);
            if (signature.getCoverage() == ProtectionCoverage.FULL && observation.getStatus() == SignatureVerification.VERIFIED && approvedKey) return;
            if (signature.getCoverage() != ProtectionCoverage.PARTIAL && observation.getStatus() != SignatureVerification.INVALID
                    && (approvedKey || observation.getSigningKeyFingerprint() == null)) possibleWitness = true;
        }
        if (value.getSignatures().isEmpty()) {
            if (possibleWitness) evaluation.uncertain(ProtectionReasonCode.EVIDENCE_UNAVAILABLE, "Signature inspection is incomplete", value.getSources());
            else evaluation.unmet(ProtectionReasonCode.SIGNATURE_REQUIRED, "No signature covers the selected value", value.getSources());
            return;
        }
        final List<ProtectionReason> reasons = new ArrayList<>();
        for (SignatureInspection signature : value.getSignatures()) reasons.add(signatureReason(approved, value, signature));
        evaluation.signatureFailure(possibleWitness, reasons);
        if (!value.isSignatureInspectionComplete()) {
            evaluation.uncertain(ProtectionReasonCode.EVIDENCE_UNAVAILABLE, "Additional relevant signature evidence was not fully inspected", value.getSources());
        }
    }

    private static ProtectionReason signatureReason(final ApprovedSigners approved, final ValueInspection value, final SignatureInspection signature) {
        final SignatureObservation observation = signature.getObservation();
        final List<EmailSourceElement> sources = observation.getSources().isEmpty() ? value.getSources() : observation.getSources();
        if (signature.getCoverage() != ProtectionCoverage.FULL) {
            return reason(ProtectionReasonCode.COVERAGE_INCOMPLETE, "No full coverage is established for this signature", sources);
        }
        if (observation.getStatus() == SignatureVerification.INVALID) {
            return reason(ProtectionReasonCode.SIGNATURE_INVALID, "This signature is invalid", sources);
        }
        if (observation.getSigningKeyFingerprint() == null) {
            return reason(ProtectionReasonCode.EVIDENCE_UNAVAILABLE, "The actual signing key could not be established", sources);
        }
        if (!approvedKey(approved, observation)) {
            return reason(ProtectionReasonCode.SIGNER_NOT_APPROVED, "The actual signing key is not approved", sources);
        }
        return reason(ProtectionReasonCode.SIGNATURE_UNVERIFIED, "This approved signature has not been verified", sources);
    }

    private static boolean approvedKey(final ApprovedSigners approved, final SignatureObservation observation) {
        final String fingerprint = observation.getSigningKeyFingerprint();
        return fingerprint != null && (observation.getFamily() == ProtectionFamily.SMIME
                ? approved.getSmimeSigningKeyFingerprints().contains(fingerprint)
                : approved.getOpenPgpSigningKeyFingerprints().contains(fingerprint));
    }

    private static void inspectDecryption(final ValueInspection value, final Evaluation evaluation) {
        if (value.getEncryptionStatus() == EncryptionStatus.DECRYPTED) {
            // The summary establishes recovery of every contribution, including unions of independently encrypted fragments.
            // A shared fully covering witness belongs to the signature requirement, not to decryption.
            return;
        } else if (value.getEncryptionStatus() == EncryptionStatus.UNKNOWN) {
            evaluation.uncertain(ProtectionReasonCode.DECRYPTION_UNAVAILABLE, "Decryption coverage is unknown", value.getSources());
        } else {
            evaluation.unmet(ProtectionReasonCode.DECRYPTION_REQUIRED, value.getEncryptionStatus() == EncryptionStatus.ENCRYPTED
                    ? "The selected payload remains encrypted rather than recovered" : "The selected content was not recovered from encrypted input", value.getSources());
        }
    }

    private static void inspectAllSignatures(final ValueInspection value, final Evaluation evaluation) {
        for (SignatureInspection signature : value.getSignatures()) {
            final SignatureObservation observation = signature.getObservation();
            final List<EmailSourceElement> sources = observation.getSources().isEmpty() ? value.getSources() : observation.getSources();
            if (observation.getStatus() == SignatureVerification.INVALID) {
                evaluation.unmet(ProtectionReasonCode.STRICT_SIGNATURE_INVALID, "A relevant signature is invalid under the strict requirement", sources);
            } else if (observation.getStatus() != SignatureVerification.VERIFIED) {
                evaluation.uncertain(ProtectionReasonCode.STRICT_SIGNATURE_UNVERIFIED, "A relevant signature is unverified under the strict requirement", sources);
            }
        }
        if (!value.isSignatureInspectionComplete()) {
            evaluation.uncertain(ProtectionReasonCode.EVIDENCE_UNAVAILABLE, "Strict verification requires complete relevant signature inspection", value.getSources());
        }
    }

    private static ProtectionReason reason(final ProtectionReasonCode code, final String message, final List<EmailSourceElement> sources) {
        return ProtectionReasonImpl.builder().code(code).message(message).sources(sources).build();
    }

    private static final class Evaluation {
        private boolean unmet;
        private boolean uncertain;
        private final List<ProtectionReason> reasons = new ArrayList<>();

        private void unmet(final ProtectionReasonCode code, final String message, final List<EmailSourceElement> sources) {
            unmet = true;
            reasons.add(reason(code, message, sources));
        }

        private void uncertain(final ProtectionReasonCode code, final String message, final List<EmailSourceElement> sources) {
            uncertain = true;
            reasons.add(reason(code, message, sources));
        }

        private void signatureFailure(final boolean possibleWitness, final List<ProtectionReason> explanations) {
            uncertain |= possibleWitness;
            unmet |= !possibleWitness;
            reasons.addAll(explanations);
        }

        private ProtectionCheck result() {
            return ProtectionCheckImpl.builder().outcome(unmet ? ProtectionOutcome.NOT_SATISFIED
                    : uncertain ? ProtectionOutcome.INDETERMINATE : ProtectionOutcome.SATISFIED).reasons(reasons).build();
        }
    }
}
