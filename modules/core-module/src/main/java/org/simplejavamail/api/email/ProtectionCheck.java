package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.List;

/** A pure assessment of one selected value against application-supplied requirements. It does not mutate Email Problems. */
public interface ProtectionCheck extends Serializable {
    /** Whether the captured evidence establishes, disproves or cannot decide the selected requirements. */
    @NotNull ProtectionOutcome getOutcome();
    /** True only for {@link ProtectionOutcome#SATISFIED}. */
    boolean isSatisfied();
    /** Immutable explanations of unmet or indeterminate conditions; empty for a satisfied check. */
    @NotNull List<ProtectionReason> getReasons();
}
