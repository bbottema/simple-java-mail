package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.List;

/**
 * Provider-neutral cryptographic result retained when converting an OpenPGP/MIME message.
 *
 * <p>Signature validity reports content integrity against the supplied key. It does not establish that the key belongs
 * to the address in the message's {@code From} header. Key ownership and trust remain application concerns.</p>
 */
public interface OriginalOpenPgpDetails extends Serializable {

    enum OpenPgpMode {
        PLAIN, SIGNED, ENCRYPTED, SIGNED_ENCRYPTED
    }

    enum SignatureStatus {
        NOT_PRESENT, VALID, INVALID, KEY_MISSING, ERROR
    }

    enum DecryptionStatus {
        NOT_ENCRYPTED, DECRYPTED, KEY_MISSING, FAILED
    }

    @NotNull OpenPgpMode getOpenPgpMode();
    @NotNull SignatureStatus getSignatureStatus();
    @NotNull DecryptionStatus getDecryptionStatus();
    @Nullable String getSignerKeyId();
    @Nullable String getSignerFingerprint();
    @Nullable String getSignatureAlgorithm();
    @Nullable String getHashAlgorithm();
    @Nullable String getEncryptionAlgorithm();
    @NotNull List<String> getRecipientKeyIds();
    @Nullable String getFailureReason();

    /**
     * Returns a defensive copy of the exact outer protected EML bytes as received, or {@code null} for an ordinary
     * message.
     *
     * <p>The bytes are retained in memory by the converted {@link Email} and are included when that email is Java
     * serialized. For a large protected message, account for roughly another full-message byte array while keeping the
     * converted email. Simple Java Mail keeps this representation exact so failed or unavailable verification and
     * decryption never discard the original protected input.</p>
     */
    byte @Nullable [] getOriginalProtectedMessage();
}
