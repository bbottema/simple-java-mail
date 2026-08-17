package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable implementation of {@link OriginalOpenPgpDetails}. */
public final class OpenPgpDetails implements OriginalOpenPgpDetails {

    private static final long serialVersionUID = 1L;

    private final OpenPgpMode openPgpMode;
    private final SignatureStatus signatureStatus;
    private final DecryptionStatus decryptionStatus;
    private final String signerKeyId;
    private final String signerFingerprint;
    private final String signatureAlgorithm;
    private final String hashAlgorithm;
    private final String encryptionAlgorithm;
    private final List<String> recipientKeyIds;
    private final String failureReason;
    private final byte[] originalProtectedMessage;

    private OpenPgpDetails(final Builder builder) {
        openPgpMode = Objects.requireNonNull(builder.openPgpMode, "openPgpMode");
        signatureStatus = Objects.requireNonNull(builder.signatureStatus, "signatureStatus");
        decryptionStatus = Objects.requireNonNull(builder.decryptionStatus, "decryptionStatus");
        signerKeyId = builder.signerKeyId;
        signerFingerprint = builder.signerFingerprint;
        signatureAlgorithm = builder.signatureAlgorithm;
        hashAlgorithm = builder.hashAlgorithm;
        encryptionAlgorithm = builder.encryptionAlgorithm;
        recipientKeyIds = Collections.unmodifiableList(new ArrayList<>(builder.recipientKeyIds));
        failureReason = builder.failureReason;
        originalProtectedMessage = builder.originalProtectedMessage == null ? null : builder.originalProtectedMessage.clone();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static OpenPgpDetails plain() {
        return builder().build();
    }

    @NotNull public OpenPgpMode getOpenPgpMode() { return openPgpMode; }
    @NotNull public SignatureStatus getSignatureStatus() { return signatureStatus; }
    @NotNull public DecryptionStatus getDecryptionStatus() { return decryptionStatus; }
    @Nullable public String getSignerKeyId() { return signerKeyId; }
    @Nullable public String getSignerFingerprint() { return signerFingerprint; }
    @Nullable public String getSignatureAlgorithm() { return signatureAlgorithm; }
    @Nullable public String getHashAlgorithm() { return hashAlgorithm; }
    @Nullable public String getEncryptionAlgorithm() { return encryptionAlgorithm; }
    @NotNull public List<String> getRecipientKeyIds() { return recipientKeyIds; }
    @Nullable public String getFailureReason() { return failureReason; }
    public byte @Nullable [] getOriginalProtectedMessage() {
        return originalProtectedMessage == null ? null : originalProtectedMessage.clone();
    }

    @Override
    public String toString() {
        return "OpenPgpDetails{openPgpMode=" + openPgpMode
                + ", signatureStatus=" + signatureStatus
                + ", decryptionStatus=" + decryptionStatus
                + ", signerKeyId='" + signerKeyId + '\''
                + ", signerFingerprint='" + signerFingerprint + '\''
                + ", signatureAlgorithm='" + signatureAlgorithm + '\''
                + ", hashAlgorithm='" + hashAlgorithm + '\''
                + ", encryptionAlgorithm='" + encryptionAlgorithm + '\''
                + ", recipientKeyIds=" + recipientKeyIds
                + ", failureReason='" + failureReason + '\''
                + ", originalProtectedMessage=" + (originalProtectedMessage == null ? "none" : "preserved") + '}';
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) return true;
        if (!(other instanceof OpenPgpDetails)) return false;
        final OpenPgpDetails that = (OpenPgpDetails) other;
        return openPgpMode == that.openPgpMode
                && signatureStatus == that.signatureStatus
                && decryptionStatus == that.decryptionStatus
                && Objects.equals(signerKeyId, that.signerKeyId)
                && Objects.equals(signerFingerprint, that.signerFingerprint)
                && Objects.equals(signatureAlgorithm, that.signatureAlgorithm)
                && Objects.equals(hashAlgorithm, that.hashAlgorithm)
                && Objects.equals(encryptionAlgorithm, that.encryptionAlgorithm)
                && Objects.equals(recipientKeyIds, that.recipientKeyIds)
                && Objects.equals(failureReason, that.failureReason)
                && java.util.Arrays.equals(originalProtectedMessage, that.originalProtectedMessage);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(openPgpMode, signatureStatus, decryptionStatus, signerKeyId,
                signerFingerprint, signatureAlgorithm, hashAlgorithm, encryptionAlgorithm,
                recipientKeyIds, failureReason) + java.util.Arrays.hashCode(originalProtectedMessage);
    }

    public static final class Builder {
        private OpenPgpMode openPgpMode = OpenPgpMode.PLAIN;
        private SignatureStatus signatureStatus = SignatureStatus.NOT_PRESENT;
        private DecryptionStatus decryptionStatus = DecryptionStatus.NOT_ENCRYPTED;
        private String signerKeyId;
        private String signerFingerprint;
        private String signatureAlgorithm;
        private String hashAlgorithm;
        private String encryptionAlgorithm;
        private List<String> recipientKeyIds = new ArrayList<>();
        private String failureReason;
        private byte[] originalProtectedMessage;

        public Builder openPgpMode(final OpenPgpMode value) { openPgpMode = value; return this; }
        public Builder signatureStatus(final SignatureStatus value) { signatureStatus = value; return this; }
        public Builder decryptionStatus(final DecryptionStatus value) { decryptionStatus = value; return this; }
        public Builder signerKeyId(@Nullable final String value) { signerKeyId = value; return this; }
        public Builder signerFingerprint(@Nullable final String value) { signerFingerprint = value; return this; }
        public Builder signatureAlgorithm(@Nullable final String value) { signatureAlgorithm = value; return this; }
        public Builder hashAlgorithm(@Nullable final String value) { hashAlgorithm = value; return this; }
        public Builder encryptionAlgorithm(@Nullable final String value) { encryptionAlgorithm = value; return this; }
        public Builder recipientKeyIds(final List<String> value) { recipientKeyIds = new ArrayList<>(value); return this; }
        public Builder failureReason(@Nullable final String value) { failureReason = value; return this; }
        public Builder originalProtectedMessage(final byte @Nullable [] value) {
            originalProtectedMessage = value == null ? null : value.clone();
            return this;
        }
        public OpenPgpDetails build() { return new OpenPgpDetails(this); }
    }
}
