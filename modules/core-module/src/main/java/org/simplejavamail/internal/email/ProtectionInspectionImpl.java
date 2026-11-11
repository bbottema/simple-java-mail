package org.simplejavamail.internal.email;

import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.email.DecryptionInspection;
import org.simplejavamail.api.email.EmailSourceElement;
import org.simplejavamail.api.email.ProtectionFamily;
import org.simplejavamail.api.email.ProtectionInspection;
import org.simplejavamail.api.email.SignatureObservation;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Immutable observations scoped to one logical message and family; no whole-message coverage verdict. */
@ApiStatus.Internal
@Getter
public final class ProtectionInspectionImpl implements ProtectionInspection {
    private static final long serialVersionUID = 1L;
    private final ProtectionFamily family;
    private final boolean inspectionComplete;
    private final List<SignatureObservation> signatures;
    private final List<DecryptionInspection> decryptions;
    private final List<EmailSourceElement> sources;

    @Builder(toBuilder = true)
    private ProtectionInspectionImpl(final ProtectionFamily family, final boolean inspectionComplete,
            final List<? extends SignatureObservation> signatures, final List<? extends DecryptionInspection> decryptions,
            final List<? extends EmailSourceElement> sources) {
        this.family = Objects.requireNonNull(family, "family");
        this.inspectionComplete = inspectionComplete;
        this.signatures = signatures == null ? List.of() : List.copyOf(signatures.stream().map(SignatureObservationImpl::copyOf).collect(Collectors.toList()));
        this.decryptions = decryptions == null ? List.of() : List.copyOf(decryptions.stream().map(DecryptionInspectionImpl::copyOf).collect(Collectors.toList()));
        this.sources = sources == null ? List.of() : EmailInspection.elements(sources);
    }

    public static ProtectionInspection copyOf(final ProtectionInspection source) {
        Objects.requireNonNull(source, "protection inspection");
        if (source instanceof ProtectionInspectionImpl) return source;
        return builder().family(source.getFamily()).inspectionComplete(source.isInspectionComplete()).signatures(source.getSignatures())
                .decryptions(source.getDecryptions()).sources(source.getSources()).build();
    }
}
