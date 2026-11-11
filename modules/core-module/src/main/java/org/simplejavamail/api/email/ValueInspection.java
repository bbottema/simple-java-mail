package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.List;

/**
 * Immutable metadata for one selected field or resource occurrence, including a field with no readable value.
 * A present empty value differs from established absence. Native origin alone neither establishes nor excludes protection.
 * Source links refer to captured occurrences, without duplicating content or starting work when read.
 */
public interface ValueInspection extends Serializable {
    /** How the currently selected value relates to its input, independently of protection. */
    @NotNull ValueOrigin getOrigin();
    /** Known native conversion/derivation, or UNKNOWN when history was not captured. */
    @NotNull ValueDerivation getDerivation();
    /** Present includes an empty value; null convenience getters alone do not establish absence. */
    @NotNull ValueAvailability getAvailability();
    /** Captured occurrences contributing to this value; empty does not prove complete inspection. */
    @NotNull List<EmailSourceElement> getSources();
    /** VERIFIED requires full coverage of every contribution; it does not establish signer approval. */
    @NotNull SignatureStatus getSignatureStatus();
    /**
     * Describes incoming coverage, independently of outgoing encryption configuration. DECRYPTED establishes that every
     * selected contribution was recovered from encrypted input; distinct contributions may have different encryption layers,
     * with individually PARTIAL contextual attempts. A decrypted/plain mixture remains UNKNOWN.
     */
    @NotNull EncryptionStatus getEncryptionStatus();
    /** Each entry binds one captured observation to its coverage of this selected value. */
    @NotNull List<SignatureInspection> getSignatures();
    /** Attempts associated with this selected content; independent child-body attempts are excluded. */
    @NotNull List<DecryptionInspection> getDecryptions();
    /** Whether every relevant signature scope was visited; an empty list alone never establishes absence. */
    boolean isSignatureInspectionComplete();
}
