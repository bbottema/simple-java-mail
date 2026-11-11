package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.List;

/**
 * Historical incoming observations for one protection family in this source, including directly opened attachment-payload
 * layers. Independent child Email body layers belong to that child and are excluded. Replacing/clearing a current value does
 * not erase historical observations. These layer facts are not a verdict that every current field is protected; inspect the
 * exact value consumed through {@link EmailInspection}. A current field and this history reuse the same captured signature observation.
 */
public interface ProtectionInspection extends Serializable {
    /** The family described by these observations. */
    @NotNull ProtectionFamily getFamily();
    /** Whether selected inspection completed for this source-scoped family history; it is not a current-content acceptance verdict. */
    boolean isInspectionComplete();
    /** Captured signatures over this source and its directly opened payload layers, excluding an independent child's internal body signatures. */
    @NotNull List<SignatureObservation> getSignatures();
    /** Captured payload-layer processing, including deferred/unavailable work; its layer coverage does not describe every current Email field. */
    @NotNull List<DecryptionInspection> getDecryptions();
    /** Captured occurrences identifying this family's actual scope. */
    @NotNull List<EmailSourceElement> getSources();
}
