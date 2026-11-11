package org.simplejavamail.internal.email;

import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.email.EmailSourceElement;
import org.simplejavamail.api.email.ProtectionFamily;
import org.simplejavamail.api.email.SignatureObservation;
import org.simplejavamail.api.email.SignatureVerification;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Core-owned immutable signature facts shared by all contextual field views. */
@ApiStatus.Internal
@Getter
public final class SignatureObservationImpl implements SignatureObservation {
    private static final long serialVersionUID = 1L;
    private final UUID reference;
    private final ProtectionFamily family;
    private final SignatureVerification status;
    private final String signerName;
    private final String signerKeyId;
    private final String signingKeyFingerprint;
    private final String signatureAlgorithm;
    private final String hashAlgorithm;
    private final String failureReason;
    private final List<EmailSourceElement> sources;

    @Builder(toBuilder = true)
    private SignatureObservationImpl(final UUID reference, final ProtectionFamily family, final SignatureVerification status,
            final String signerName, final String signerKeyId, final String signingKeyFingerprint, final String signatureAlgorithm,
            final String hashAlgorithm, final String failureReason, final List<? extends EmailSourceElement> sources) {
        this.reference = reference == null ? UUID.randomUUID() : reference;
        this.family = Objects.requireNonNull(family, "family");
        this.status = Objects.requireNonNull(status, "status");
        this.signerName = signerName;
        this.signerKeyId = signerKeyId;
        this.signingKeyFingerprint = signingKeyFingerprint;
        this.signatureAlgorithm = signatureAlgorithm;
        this.hashAlgorithm = hashAlgorithm;
        this.failureReason = failureReason;
        this.sources = sources == null ? List.of() : EmailInspection.elements(sources);
    }

    public static SignatureObservation copyOf(final SignatureObservation source) {
        Objects.requireNonNull(source, "signature");
        if (source instanceof SignatureObservationImpl) return source;
        return builder().reference(source.getReference()).family(source.getFamily()).status(source.getStatus())
                .signerName(source.getSignerName()).signerKeyId(source.getSignerKeyId())
                .signingKeyFingerprint(source.getSigningKeyFingerprint()).signatureAlgorithm(source.getSignatureAlgorithm())
                .hashAlgorithm(source.getHashAlgorithm()).failureReason(source.getFailureReason()).sources(source.getSources()).build();
    }
}
