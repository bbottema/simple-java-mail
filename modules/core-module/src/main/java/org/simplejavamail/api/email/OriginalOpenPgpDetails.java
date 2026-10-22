package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.List;

/**
 * Provider-neutral cryptographic result retained when converting an OpenPGP/MIME message.
 *
 * <p>These are historical results for the converted input, including any retained original protected bytes. Ordinary copying and supported
 * Java serialization preserve them. Defaults/overrides completion resolves outgoing content and settings without copying these results;
 * use the original converted Email for inspection. Plain/unsigned details on a composed Email do not establish the input's protection or
 * a verification result for outgoing MIME. Outgoing signing/encryption configuration is separate and takes effect during MIME preparation.</p>
 *
 * <p>Signature validity reports content integrity against the supplied key. It does not establish that the key belongs
 * to the address in the message's {@code From} header. Key ownership and trust remain application concerns.</p>
 *
 * <p>{@code UNKNOWN} means that inspection did not establish a fact, for example because the optional implementation is absent
 * or an encrypted layer could not be opened. Recognizable outer headers can still establish a signed or encrypted mode.
 * Missing keys and actual verification or decryption failures retain their separate results.</p>
 */
public interface OriginalOpenPgpDetails extends Serializable {

    enum OpenPgpMode {
        PLAIN, SIGNED, ENCRYPTED, SIGNED_ENCRYPTED,
        /** The MIME structure could not be inspected sufficiently to establish its protection mode. */
        UNKNOWN
    }

    enum SignatureStatus {
        NOT_PRESENT, VALID, INVALID, KEY_MISSING, ERROR,
        /** Signature presence or verification could not be inspected, including inside unopened ciphertext. */
        UNKNOWN
    }

    enum DecryptionStatus {
        NOT_ENCRYPTED, DECRYPTED, KEY_MISSING, FAILED,
        /** Encryption or its decryption outcome could not be inspected. */
        UNKNOWN
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
    /** Explains incomplete inspection or failed cryptographic processing, when available. */
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
