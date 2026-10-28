package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;

/**
 * Immutable facts for one represented incoming OpenPGP signature occurrence.
 * Obtain these values from {@link OriginalOpenPgpDetails#getSignatures()}; result construction belongs to the library.
 * Repeated signatures by the same key remain separate occurrences. Reading a result never loads a protection module,
 * reads message content or performs cryptography. Key ownership and trust remain application concerns.
 */
public interface OpenPgpSignatureResult extends Serializable {
    /** The result for this occurrence; {@code NOT_PRESENT} belongs only to an empty, completely inspected message. */
    @NotNull OriginalOpenPgpDetails.SignatureStatus getStatus();
    /** The reported signer key ID, when available; its presence does not establish key ownership or signature validity. */
    @Nullable String getSignerKeyId();
    /** The fingerprint of the identified signing key, when available. */
    @Nullable String getSignerFingerprint();
    @Nullable String getSignatureAlgorithm();
    @Nullable String getHashAlgorithm();
    /** Explains this occurrence's missing key, invalid signature or processing problem, when available. */
    @Nullable String getFailureReason();
}
