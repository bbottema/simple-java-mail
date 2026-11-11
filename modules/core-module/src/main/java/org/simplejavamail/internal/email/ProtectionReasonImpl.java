package org.simplejavamail.internal.email;

import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.email.EmailSourceElement;
import org.simplejavamail.api.email.ProtectionReason;
import org.simplejavamail.api.email.ProtectionReasonCode;

import java.util.List;
import java.util.Objects;

/** Immutable application-assessment explanation, separate from input-processing Problems. */
@ApiStatus.Internal
@Getter
public final class ProtectionReasonImpl implements ProtectionReason {
    private static final long serialVersionUID = 1L;
    private final ProtectionReasonCode code;
    private final String message;
    private final List<EmailSourceElement> sources;

    @Builder
    private ProtectionReasonImpl(final ProtectionReasonCode code, final String message, final List<? extends EmailSourceElement> sources) {
        this.code = Objects.requireNonNull(code, "code");
        this.message = Objects.requireNonNull(message, "message");
        this.sources = sources == null ? List.of() : EmailInspection.elements(sources);
    }
}
