package org.simplejavamail.internal.email;

import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.email.DecryptionInspection;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.EmailSourceElement;
import org.simplejavamail.api.email.ProtectionCoverage;
import org.simplejavamail.api.email.ProtectionFamily;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Core-owned completed/deferred/unavailable/failed processing of a recognized encrypted component. */
@ApiStatus.Internal
@Getter
public final class DecryptionInspectionImpl implements DecryptionInspection {
    private static final long serialVersionUID = 1L;
    private final UUID reference;
    private final ProtectionFamily family;
    private final DecryptionStatus status;
    private final ProtectionCoverage coverage;
    private final String encryptionAlgorithm;
    private final List<String> recipientKeyIds;
    private final String failureReason;
    private final List<EmailSourceElement> sources;

    @Builder(toBuilder = true)
    private DecryptionInspectionImpl(final UUID reference, final ProtectionFamily family, final DecryptionStatus status,
            final ProtectionCoverage coverage, final String encryptionAlgorithm, final List<String> recipientKeyIds, final String failureReason,
            final List<? extends EmailSourceElement> sources) {
        this.reference = reference == null ? UUID.randomUUID() : reference;
        this.family = Objects.requireNonNull(family, "family");
        this.status = Objects.requireNonNull(status, "status");
        this.coverage = coverage == null ? ProtectionCoverage.UNKNOWN : coverage;
        this.encryptionAlgorithm = encryptionAlgorithm;
        this.recipientKeyIds = recipientKeyIds == null ? List.of() : List.copyOf(recipientKeyIds);
        this.failureReason = failureReason;
        this.sources = sources == null ? List.of() : EmailInspection.elements(sources);
    }

    public static DecryptionInspection copyOf(final DecryptionInspection source) {
        Objects.requireNonNull(source, "decryption");
        if (source instanceof DecryptionInspectionImpl) return source;
        return builder().reference(source.getReference()).family(source.getFamily()).status(source.getStatus()).coverage(source.getCoverage())
                .encryptionAlgorithm(source.getEncryptionAlgorithm()).recipientKeyIds(source.getRecipientKeyIds())
                .failureReason(source.getFailureReason()).sources(source.getSources()).build();
    }
}
