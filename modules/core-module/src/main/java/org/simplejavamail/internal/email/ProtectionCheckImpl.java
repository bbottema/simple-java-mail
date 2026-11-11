package org.simplejavamail.internal.email;

import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.email.ProtectionCheck;
import org.simplejavamail.api.email.ProtectionOutcome;
import org.simplejavamail.api.email.ProtectionReason;

import java.util.List;
import java.util.Objects;

/** Completed assessment result, owned by core and safe to inspect without optional modules. */
@ApiStatus.Internal
@Getter
public final class ProtectionCheckImpl implements ProtectionCheck {
    private static final long serialVersionUID = 1L;
    private final ProtectionOutcome outcome;
    private final List<ProtectionReason> reasons;

    @Builder
    private ProtectionCheckImpl(final ProtectionOutcome outcome, final List<ProtectionReason> reasons) {
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    @Override public boolean isSatisfied() { return outcome == ProtectionOutcome.SATISFIED; }
}
