package org.simplejavamail.internal.email;

import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.email.DecryptionInspection;
import org.simplejavamail.api.email.EmailSourceElement;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.ProtectionCoverage;
import org.simplejavamail.api.email.SignatureInspection;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.SignatureVerification;
import org.simplejavamail.api.email.ValueAvailability;
import org.simplejavamail.api.email.ValueDerivation;
import org.simplejavamail.api.email.ValueInspection;
import org.simplejavamail.api.email.ValueOrigin;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Core snapshot of current-value associations, independent of the Email's historical original input. */
@ApiStatus.Internal
@Getter
public final class ValueInspectionImpl implements ValueInspection {
    private static final long serialVersionUID = 1L;
    private static final ValueInspection UNKNOWN = builder().build();
    private final ValueOrigin origin;
    private final ValueDerivation derivation;
    private final ValueAvailability availability;
    private final List<EmailSourceElement> sources;
    private final SignatureStatus signatureStatus;
    private final EncryptionStatus encryptionStatus;
    private final List<SignatureInspection> signatures;
    private final List<DecryptionInspection> decryptions;
    private final boolean signatureInspectionComplete;

    @Builder(toBuilder = true)
    private ValueInspectionImpl(final ValueOrigin origin, final ValueDerivation derivation, final ValueAvailability availability,
            final List<? extends EmailSourceElement> sources, final EncryptionStatus encryptionStatus,
            final List<? extends SignatureInspection> signatures, final List<? extends DecryptionInspection> decryptions,
            final boolean signatureInspectionComplete) {
        this.origin = origin == null ? ValueOrigin.UNKNOWN : origin;
        this.derivation = derivation == null ? ValueDerivation.UNKNOWN : derivation;
        this.availability = availability == null ? ValueAvailability.UNKNOWN : availability;
        this.sources = sources == null ? List.of() : EmailInspection.elements(sources);
        this.signatures = signatures == null ? List.of() : List.copyOf(signatures.stream().map(SignatureInspectionImpl::copyOf).collect(Collectors.toList()));
        this.decryptions = decryptions == null ? List.of() : List.copyOf(decryptions.stream().map(DecryptionInspectionImpl::copyOf).collect(Collectors.toList()));
        this.signatureInspectionComplete = signatureInspectionComplete;
        this.signatureStatus = summarizeSignatures(this.signatures, signatureInspectionComplete);
        this.encryptionStatus = encryptionStatus == null ? EncryptionStatus.UNKNOWN : encryptionStatus;
    }

    public static ValueInspection unknown() {
        return UNKNOWN;
    }

    public static ValueInspection copyOf(final ValueInspection source) {
        Objects.requireNonNull(source, "value inspection");
        if (source instanceof ValueInspectionImpl) return source;
        return builder().origin(source.getOrigin()).derivation(source.getDerivation()).availability(source.getAvailability()).sources(source.getSources())
                .encryptionStatus(source.getEncryptionStatus()).signatures(source.getSignatures())
                .decryptions(source.getDecryptions()).signatureInspectionComplete(source.isSignatureInspectionComplete()).build();
    }

    private static SignatureStatus summarizeSignatures(final List<SignatureInspection> signatures, final boolean complete) {
        if (signatures.isEmpty()) return complete ? SignatureStatus.UNSIGNED : SignatureStatus.UNKNOWN;
        final Set<SignatureVerification> outcomes = EnumSet.noneOf(SignatureVerification.class);
        boolean partial = false;
        boolean unknown = !complete;
        for (SignatureInspection signature : signatures) {
            outcomes.add(signature.getObservation().getStatus());
            partial |= signature.getCoverage() == ProtectionCoverage.PARTIAL;
            unknown |= signature.getCoverage() == ProtectionCoverage.UNKNOWN;
        }
        if (partial || outcomes.size() > 1) return SignatureStatus.MIXED;
        if (unknown) return SignatureStatus.UNKNOWN;
        switch (outcomes.iterator().next()) {
            case VERIFIED: return SignatureStatus.VERIFIED;
            case INVALID: return SignatureStatus.INVALID;
            default: return SignatureStatus.UNVERIFIED;
        }
    }
}
