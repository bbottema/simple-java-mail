package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.List;

/** Provider-neutral cryptographic result retained when converting an OpenPGP/MIME message. */
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

    /** The exact protected EML bytes as received, or {@code null} for an ordinary message. */
    byte @Nullable [] getOriginalProtectedMessage();
}
