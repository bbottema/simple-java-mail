package org.simplejavamail.internal.email;

import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.email.ProtectionCoverage;
import org.simplejavamail.api.email.SignatureInspection;
import org.simplejavamail.api.email.SignatureObservation;

import java.util.Objects;

/** Contextual coverage does not duplicate the underlying signature occurrence. */
@ApiStatus.Internal
@Getter
public final class SignatureInspectionImpl implements SignatureInspection {
    private static final long serialVersionUID = 1L;
    private final SignatureObservation observation;
    private final ProtectionCoverage coverage;

    @Builder(toBuilder = true)
    private SignatureInspectionImpl(final SignatureObservation observation, final ProtectionCoverage coverage) {
        this.observation = SignatureObservationImpl.copyOf(observation);
        this.coverage = coverage == null ? ProtectionCoverage.UNKNOWN : coverage;
    }

    public static SignatureInspection copyOf(final SignatureInspection source) {
        Objects.requireNonNull(source, "signature inspection");
        return source instanceof SignatureInspectionImpl ? source
                : builder().observation(source.getObservation()).coverage(source.getCoverage()).build();
    }
}
