package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

/** Captured processing of recognized encrypted input, with the coverage relevant to the exposing view. */
public interface DecryptionInspection extends Serializable {
    /** Stable diagnostic reference for this encrypted-layer observation. */
    @NotNull UUID getReference();
    /** Protection family that processed or recognized the encrypted input. */
    @NotNull ProtectionFamily getFamily();
    /** Actual selected work, including deferred/unavailable processing distinct from established failure. */
    @NotNull DecryptionStatus getStatus();
    /** Established association with the selected content, rather than an encrypted sibling. */
    @NotNull ProtectionCoverage getCoverage();
    /** Observed encryption algorithm, or null when unavailable. */
    @Nullable String getEncryptionAlgorithm();
    /** Reported encryption-recipient key IDs, when captured; identifiers do not approve signing keys or establish recipient identity. */
    @NotNull List<String> getRecipientKeyIds();
    /** Explanation of encountered processing limitations or failure, when available. */
    @Nullable String getFailureReason();
    /** Captured encrypted-layer occurrences; no private key material is retained. */
    @NotNull List<EmailSourceElement> getSources();
}
