package org.simplejavamail.converter.internal.mimemessage;

import org.simplejavamail.api.email.*;
import org.simplejavamail.internal.email.DecryptionInspectionImpl;
import org.simplejavamail.internal.email.SignatureInspectionImpl;
import org.simplejavamail.internal.email.SignatureObservationImpl;
import org.simplejavamail.internal.email.ValueInspectionImpl;
import org.simplejavamail.internal.protection.ProtectionLayerResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Transient covering-layer associations; only immutable value observations leave the reader. */
public final class ProtectionScope {
    private final List<SignatureObservation> signatures;
    private final List<DecryptionInspection> decryptions;
    private final boolean complete;
    private final int openedDepth;
    private final boolean retainedCiphertext;
    private final boolean encryptionCoverageUnknown;

    private ProtectionScope(final List<SignatureObservation> signatures, final List<DecryptionInspection> decryptions,
            final boolean complete, final int openedDepth, final boolean retainedCiphertext, final boolean encryptionCoverageUnknown) {
        this.signatures = List.copyOf(signatures);
        this.decryptions = List.copyOf(decryptions);
        this.complete = complete;
        this.openedDepth = openedDepth;
        this.retainedCiphertext = retainedCiphertext;
        this.encryptionCoverageUnknown = encryptionCoverageUnknown;
    }

    public static ProtectionScope plain() { return new ProtectionScope(List.of(), List.of(), true, 0, false, false); }
    public static ProtectionScope unknown() { return new ProtectionScope(List.of(), List.of(), false, 0, false, true); }
    public List<SignatureObservation> getSignatures() { return signatures; }
    public List<DecryptionInspection> getDecryptions() { return decryptions; }
    public boolean isComplete() { return complete; }
    public int getOpenedDepth() { return openedDepth; }
    public boolean hasRetainedCiphertext() { return retainedCiphertext; }
    public ProtectionScope incomplete() { return new ProtectionScope(signatures, decryptions, false, openedDepth, retainedCiphertext, true); }
    public ProtectionScope deferredSignature() {
        return new ProtectionScope(signatures, decryptions, false, openedDepth, retainedCiphertext, encryptionCoverageUnknown);
    }

    public ProtectionScope deferredDecryption(final ProtectionFamily family, final EmailSourceElement source) {
        final List<DecryptionInspection> attempts = new ArrayList<>(decryptions);
        attempts.add(DecryptionInspectionImpl.builder().family(family).status(DecryptionStatus.DEFERRED)
                .coverage(ProtectionCoverage.UNKNOWN).sources(List.of(source)).build());
        return new ProtectionScope(signatures, attempts, false, openedDepth, true, encryptionCoverageUnknown);
    }

    public ProtectionScope withLayer(final ProtectionLayerResult layer, final EmailSourceElement source) {
        final List<EmailSourceElement> sources = source == null ? List.of() : List.of(source);
        final List<SignatureObservation> signed = new ArrayList<>(signatures);
        for (final SignatureObservation observation : layer.getSignatures()) {
            signed.add(((SignatureObservationImpl) SignatureObservationImpl.copyOf(observation)).toBuilder().sources(sources).build());
        }
        final List<DecryptionInspection> decrypted = new ArrayList<>(decryptions);
        boolean establishedRecovery = false;
        for (final DecryptionInspection observation : layer.getDecryptions()) {
            decrypted.add(((DecryptionInspectionImpl) DecryptionInspectionImpl.copyOf(observation)).toBuilder().sources(sources)
                    .coverage(layer.isOpened() ? observation.getCoverage() : ProtectionCoverage.UNKNOWN).build());
            establishedRecovery |= layer.isOpened() && observation.getStatus() == DecryptionStatus.DECRYPTED && observation.getCoverage() == ProtectionCoverage.FULL;
        }
        return new ProtectionScope(signed, decrypted, complete && layer.isInspectionComplete(), openedDepth + (layer.isOpened() ? 1 : 0),
                retainedCiphertext || !layer.isOpened() && !layer.getDecryptions().isEmpty(),
                encryptionCoverageUnknown && !establishedRecovery || !layer.isOpened() && !layer.isSignatureEnvelope()
                        && layer.getSignatures().isEmpty() && layer.getDecryptions().isEmpty());
    }

    public ValueInspection inspect(final List<? extends EmailSourceElement> sources, final ValueAvailability availability) {
        return combine(List.of(this), sources, availability, ValueDerivation.NONE);
    }

    public static ValueInspection combine(final List<ProtectionScope> scopes, final List<? extends EmailSourceElement> sources,
            final ValueAvailability availability, final ValueDerivation derivation) {
        if (scopes.isEmpty()) return ValueInspectionImpl.builder().origin(ValueOrigin.RECEIVED).derivation(derivation)
                .availability(availability).sources(sources).signatureInspectionComplete(availability == ValueAvailability.ABSENT)
                .encryptionStatus(availability == ValueAvailability.ABSENT ? EncryptionStatus.NOT_ENCRYPTED : EncryptionStatus.UNKNOWN).build();
        final Map<UUID, SignatureObservation> observations = new LinkedHashMap<>();
        final Map<UUID, Integer> counts = new LinkedHashMap<>();
        final Map<UUID, DecryptionInspection> decryptions = new LinkedHashMap<>();
        final Map<UUID, Integer> decryptionCounts = new LinkedHashMap<>();
        boolean complete = true;
        boolean extracted = false;
        boolean allDecrypted = true;
        boolean anyEncrypted = false;
        boolean allEncrypted = true;
        boolean encryptionKnown = true;
        final boolean unavailableValue = availability == ValueAvailability.UNAVAILABLE || availability == ValueAvailability.UNINSPECTED
                || availability == ValueAvailability.UNKNOWN;
        for (final ProtectionScope scope : scopes) {
            complete &= scope.complete;
            extracted |= scope.openedDepth > 0;
            anyEncrypted |= scope.retainedCiphertext;
            allEncrypted &= scope.retainedCiphertext;
            encryptionKnown &= !scope.encryptionCoverageUnknown;
            boolean decrypted = false;
            for (final SignatureObservation signature : scope.signatures) {
                observations.put(signature.getReference(), signature);
                counts.merge(signature.getReference(), 1, Integer::sum);
            }
            for (final DecryptionInspection decryption : scope.decryptions) {
                decryptions.put(decryption.getReference(), decryption);
                decryptionCounts.merge(decryption.getReference(), 1, Integer::sum);
                decrypted |= decryption.getStatus() == DecryptionStatus.DECRYPTED && decryption.getCoverage() == ProtectionCoverage.FULL;
            }
            allDecrypted &= decrypted && !scope.retainedCiphertext && !scope.encryptionCoverageUnknown;
        }
        final List<SignatureInspection> signatures = new ArrayList<>();
        for (final SignatureObservation signature : observations.values()) {
            signatures.add(SignatureInspectionImpl.builder().observation(signature)
                    .coverage(unavailableValue ? ProtectionCoverage.UNKNOWN
                            : counts.get(signature.getReference()) == scopes.size() ? ProtectionCoverage.FULL : ProtectionCoverage.PARTIAL).build());
        }
        final List<DecryptionInspection> attempts = new ArrayList<>();
        for (final DecryptionInspection decryption : decryptions.values()) {
            attempts.add(((DecryptionInspectionImpl) DecryptionInspectionImpl.copyOf(decryption)).toBuilder()
                    .coverage(unavailableValue ? ProtectionCoverage.UNKNOWN
                            : decryptionCounts.get(decryption.getReference()) == scopes.size() ? decryption.getCoverage() : ProtectionCoverage.PARTIAL).build());
        }
        return ValueInspectionImpl.builder().origin(extracted ? ValueOrigin.EXTRACTED : ValueOrigin.RECEIVED).derivation(derivation)
                .availability(availability).sources(sources).signatures(signatures).decryptions(attempts).signatureInspectionComplete(complete && !unavailableValue)
                .encryptionStatus(anyEncrypted ? allEncrypted && availability == ValueAvailability.PRESENT ? EncryptionStatus.ENCRYPTED : EncryptionStatus.UNKNOWN
                        : allDecrypted && !unavailableValue ? EncryptionStatus.DECRYPTED
                        : encryptionKnown && attempts.isEmpty() ? EncryptionStatus.NOT_ENCRYPTED : EncryptionStatus.UNKNOWN).build();
    }
}
