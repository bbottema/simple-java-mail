package org.simplejavamail.internal.email;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.OpenPgpSignatureResult;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.SignatureStatus;

import java.io.InvalidObjectException;
import java.io.ObjectStreamException;
import java.util.Objects;

/** Internal cross-module construction and snapshot boundary for one incoming signature. */
@ApiStatus.Internal
@Getter
@EqualsAndHashCode
public final class OpenPgpSignatureResultImpl implements OpenPgpSignatureResult {
    private static final long serialVersionUID = 1L;
    private final SignatureStatus status;
    @Nullable private final String signerKeyId;
    @Nullable private final String signerFingerprint;
    @Nullable private final String signatureAlgorithm;
    @Nullable private final String hashAlgorithm;
    @Nullable private final String failureReason;

    public OpenPgpSignatureResultImpl(final SignatureStatus status, @Nullable final String signerKeyId,
            @Nullable final String signerFingerprint, @Nullable final String signatureAlgorithm,
            @Nullable final String hashAlgorithm, @Nullable final String failureReason) {
        this.status = Objects.requireNonNull(status, "status");
        if (status == SignatureStatus.NOT_PRESENT) throw new IllegalArgumentException("A represented signature cannot be absent");
        this.signerKeyId = signerKeyId;
        this.signerFingerprint = signerFingerprint;
        this.signatureAlgorithm = signatureAlgorithm;
        this.hashAlgorithm = hashAlgorithm;
        this.failureReason = failureReason;
    }

    static OpenPgpSignatureResult copyOf(final OpenPgpSignatureResult source) {
        Objects.requireNonNull(source, "signature");
        if (source instanceof OpenPgpSignatureResultImpl) return source;
        return new OpenPgpSignatureResultImpl(source.getStatus(), source.getSignerKeyId(), source.getSignerFingerprint(),
                source.getSignatureAlgorithm(), source.getHashAlgorithm(), source.getFailureReason());
    }

    private Object readResolve() throws ObjectStreamException {
        try {
            return new OpenPgpSignatureResultImpl(status, signerKeyId, signerFingerprint, signatureAlgorithm, hashAlgorithm, failureReason);
        } catch (RuntimeException cause) {
            final InvalidObjectException failure = new InvalidObjectException("Invalid OpenPGP signature result");
            failure.initCause(cause);
            throw failure;
        }
    }

    @Override public String toString() {
        return "OpenPgpSignatureResult{status=" + status + ", signerKeyId='" + signerKeyId
                + "', signerFingerprint='" + signerFingerprint + "', signatureAlgorithm='" + signatureAlgorithm
                + "', hashAlgorithm='" + hashAlgorithm + "', failureReason='" + failureReason + "'}";
    }
}
