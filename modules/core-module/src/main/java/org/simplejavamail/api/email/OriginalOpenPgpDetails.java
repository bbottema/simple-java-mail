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
 * <p>Exact EML construction also populates these facts automatically with available incoming inspection, without replacing its raw parsed content
 * view or authoritative bytes. It supplies no receive keys; use ordinary conversion to select verification or decryption keys and obtain opened
 * content. Reading these getters does not perform further inspection.</p>
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
    /**
     * Conservative summary of all represented signatures and inspection completeness. {@code VALID} requires at least one
     * signature, complete inspection and every occurrence valid. Known problems take precedence in this order:
     * {@code INVALID}, {@code ERROR}, {@code KEY_MISSING}, {@code UNKNOWN}. Only complete inspection without signatures yields
     * {@code NOT_PRESENT}; an unopened layer, unavailable inspection or older selected-only snapshot cannot establish absence.
     */
    @NotNull SignatureStatus getSignatureStatus();
    @NotNull DecryptionStatus getDecryptionStatus();
    /**
     * Detached immutable results for every represented occurrence, including repeats by one signer. Results use a deterministic
     * metadata order: key ID, status (INVALID, ERROR, KEY_MISSING, UNKNOWN, VALID), fingerprint, signature/hash algorithms and
     * failure reason, with unavailable text after known text. Getters perform no content reads or cryptographic work.
     * An empty list does not establish signature absence; inspect {@link #getSignatureStatus()} as well.
     */
    @NotNull List<OpenPgpSignatureResult> getSignatures();
    /** Returns this fact only when exactly one signature occurrence is represented; otherwise {@code null}. */
    @Nullable String getSignerKeyId();
    /** Returns this fact only when exactly one signature occurrence is represented; otherwise {@code null}. */
    @Nullable String getSignerFingerprint();
    /** Returns this fact only when exactly one signature occurrence is represented; otherwise {@code null}. */
    @Nullable String getSignatureAlgorithm();
    /** Returns this fact only when exactly one signature occurrence is represented; otherwise {@code null}. */
    @Nullable String getHashAlgorithm();
    @Nullable String getEncryptionAlgorithm();
    @NotNull List<String> getRecipientKeyIds();
    /** Explains incomplete inspection or failed cryptographic processing, when available. */
    @Nullable String getFailureReason();

    /**
     * Returns a defensive copy of the captured outer protected EML representation, or {@code null} when complete bytes are unavailable.
     * Ordinary messages have no protected representation. For raw EML input, captured bytes preserve the exact received representation.
     *
     * <p>A limited import from a caller-supplied {@code MimeMessage} can leave this value unavailable when its permitted content could not
     * be completely visited. Complete raw EML bytes
     * already captured remain available even when later traversal stops; byte availability and verification/decryption facts are independent.</p>
     *
     * <p>When present, the bytes are retained in memory by the converted {@link Email} and are included when that email is Java
     * serialized. For a large protected message, account for roughly another full-message byte array while keeping the
     * converted email. Failed or unavailable verification/decryption does not discard a complete protected representation already captured.</p>
     */
    byte @Nullable [] getOriginalProtectedMessage();
}
