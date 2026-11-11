package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

/** A shared signature observation and its contextual coverage of one selected value. */
public interface SignatureInspection extends Serializable {
    /** Shared immutable facts for the same captured signature occurrence. */
    @NotNull SignatureObservation getObservation();
    /** Coverage of this selected value, independently of the observation's verification result. */
    @NotNull ProtectionCoverage getCoverage();
}
