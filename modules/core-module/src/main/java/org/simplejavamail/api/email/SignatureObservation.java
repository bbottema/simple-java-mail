package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

/**
 * Captured result for one signature occurrence. Several field views may refer to this same observation, with different coverage.
 * Verification establishes cryptographic consistency, not sender identity, certificate validity, revocation or application approval.
 */
public interface SignatureObservation extends Serializable {
    /** Stable diagnostic reference for this occurrence, including repeated signatures made by the same key. */
    @NotNull UUID getReference();
    /** Content-protection family that exposed this signature. */
    @NotNull ProtectionFamily getFamily();
    /** Mathematical verification result; UNVERIFIED does not claim invalidity. */
    @NotNull SignatureVerification getStatus();
    /** Reported signer text, when available; it is not an approved identity. */
    @Nullable String getSignerName();
    /**
     * Reported signer selector, when available. S/MIME uses uppercase subject-key-identifier hexadecimal text when
     * supplied, otherwise the CMS issuer name followed by {@code ;serial=} and its hexadecimal serial number.
     * OpenPGP uses the observed hexadecimal key ID. Application approval matches {@link #getSigningKeyFingerprint()}
     * together with verification and coverage.
     */
    @Nullable String getSignerKeyId();
    /**
     * Identity of the actual signing key, when established: normalized S/MIME public-key SHA-256 identity or the full
     * OpenPGP signing-key fingerprint. A primary-key fingerprint does not implicitly identify all its signing subkeys.
     */
    @Nullable String getSigningKeyFingerprint();
    /** Observed signature algorithm, or null when unavailable. */
    @Nullable String getSignatureAlgorithm();
    /** Observed digest algorithm, or null when unavailable. */
    @Nullable String getHashAlgorithm();
    /** Explanation of unavailable verification or an established failure, when available. */
    @Nullable String getFailureReason();
    /** Captured signature/source occurrences, without live provider state. */
    @NotNull List<EmailSourceElement> getSources();
}
