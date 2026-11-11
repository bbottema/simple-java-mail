package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.internal.email.ProtectionRequirementEvaluator;

import java.io.Serializable;
import java.util.Objects;

/**
 * Reusable application-owned protection requirements for one selected value. Checking evaluates captured facts only:
 * no key loading, input reads, cryptography, trust discovery or Email mutation occurs.
 * A verified, fully covering signature and key approval must belong to the same signature occurrence.
 */
public final class ProtectionRequirements implements Serializable {
    private static final long serialVersionUID = 1L;
    private final ApprovedSigners approvedSigners;
    private final boolean decryptedContentRequired;
    private final boolean allSignaturesVerifiedRequired;

    private ProtectionRequirements(final Builder builder) {
        approvedSigners = builder.approvedSigners;
        decryptedContentRequired = builder.decryptedContentRequired;
        allSignaturesVerifiedRequired = builder.allSignaturesVerifiedRequired;
        if (approvedSigners == null && !decryptedContentRequired) {
            throw new IllegalArgumentException("At least one protection requirement is required");
        }
        if (allSignaturesVerifiedRequired && approvedSigners == null) {
            throw new IllegalArgumentException("All-signature verification requires an approved-signer requirement");
        }
    }

    @NotNull public static Builder builder() { return new Builder(); }

    /** Returns an independent builder preserving these supplied choices. */
    @NotNull public Builder toBuilder() {
        final Builder builder = new Builder();
        builder.approvedSigners = approvedSigners;
        builder.decryptedContentRequired = decryptedContentRequired;
        builder.allSignaturesVerifiedRequired = allSignaturesVerifiedRequired;
        return builder;
    }

    /**
     * The supplied approved set, or null when no signer requirement is selected.
     * @see Builder#requireVerifiedSignatureFrom(ApprovedSigners)
     */
    @Nullable public ApprovedSigners getApprovedSigners() { return approvedSigners; }

    /** @see Builder#requireDecryptedContent() */
    public boolean isDecryptedContentRequired() { return decryptedContentRequired; }

    /** @see Builder#requireAllSignaturesVerified() */
    public boolean isAllSignaturesVerifiedRequired() { return allSignaturesVerifiedRequired; }

    /**
     * Checks one selected field/resource's captured evidence. A present empty value can satisfy requirements; an established
     * absent value cannot. Unavailable evidence yields INDETERMINATE unless another required condition is positively unmet.
     * MIXED input may satisfy the base approved-signer condition through one verified fully covering approved signature.
     * @param value inspection of the exact value the application will consume
     * @return immutable outcome and stable explanatory reasons, without adding Email Problems
     * @throws NullPointerException if value is null
     */
    @NotNull public ProtectionCheck check(@NotNull final ValueInspection value) {
        return ProtectionRequirementEvaluator.check(this, Objects.requireNonNull(value, "value"));
    }

    /** Collects supplied requirements; getters inspect unfinished combinations without building them. */
    public static final class Builder {
        private ApprovedSigners approvedSigners;
        private boolean decryptedContentRequired;
        private boolean allSignaturesVerifiedRequired;

        private Builder() { }

        /**
         * Requires at least one VERIFIED, FULL-coverage signature from an explicitly approved actual signing key.
         * A later call replaces the approved set; success, coverage and approval must all belong to that same observation.
         * Other invalid/unverified signatures do not defeat this base rule; select {@link #requireAllSignaturesVerified()}
         * for the stricter condition. {@link #clearVerifiedSignatureRequirement()} removes only this requirement.
         * @param approvedSigners immutable application-approved signing keys
         * @throws NullPointerException if the approved set is null; existing choices are preserved
         */
        @NotNull public Builder requireVerifiedSignatureFrom(@NotNull final ApprovedSigners approvedSigners) {
            this.approvedSigners = Objects.requireNonNull(approvedSigners, "approvedSigners");
            return this;
        }

        /**
         * Requires every contribution to this selected readable content to be established as recovered from encrypted input.
         * Distinct contributions may have been recovered under different encryption layers; no single common encryption is required.
         * Plain content and unopened ciphertext do not satisfy it. This never attempts decryption or loads credentials.
         * The default is no decryption requirement; {@link #clearDecryptedContentRequirement()} restores that choice.
         */
        @NotNull public Builder requireDecryptedContent() { decryptedContentRequired = true; return this; }

        /**
         * Adds the stricter condition that all relevant associated signatures verify and inspection is complete, while
         * still requiring one fully covering approved witness. This does not require every signer to be approved and does
         * not include independent children. The default is this condition disabled. {@link #build()} rejects it without
         * {@link #requireVerifiedSignatureFrom(ApprovedSigners)}; {@link #clearAllSignaturesVerifiedRequirement()} removes it.
         */
        @NotNull public Builder requireAllSignaturesVerified() { allSignaturesVerifiedRequired = true; return this; }

        /**
         * Returns the supplied approved set, or null when not selected, without building or checking it.
         * @see #requireVerifiedSignatureFrom(ApprovedSigners)
         */
        @Nullable public ApprovedSigners getApprovedSigners() { return approvedSigners; }
        /** @see #requireDecryptedContent() */
        public boolean isDecryptedContentRequired() { return decryptedContentRequired; }
        /** @see #requireAllSignaturesVerified() */
        public boolean isAllSignaturesVerifiedRequired() { return allSignaturesVerifiedRequired; }
        /** Removes only the signer requirement; a still-selected strict condition remains inspectable and must be cleared before build.
         * @see #requireVerifiedSignatureFrom(ApprovedSigners)
         */
        @NotNull public Builder clearVerifiedSignatureRequirement() { approvedSigners = null; return this; }
        /** @see #requireDecryptedContent() */
        @NotNull public Builder clearDecryptedContentRequirement() { decryptedContentRequired = false; return this; }
        /** @see #requireAllSignaturesVerified() */
        @NotNull public Builder clearAllSignaturesVerifiedRequirement() { allSignaturesVerifiedRequired = false; return this; }

        /**
         * Creates an independent policy, requiring at least one base requirement and an approved-signer requirement when
         * strict verification is selected. A decryption-only policy is valid. Construction performs no cryptographic work.
         * @throws IllegalArgumentException if no base requirement is selected or strict verification lacks a signer requirement
         */
        @NotNull public ProtectionRequirements build() { return new ProtectionRequirements(this); }
    }
}
