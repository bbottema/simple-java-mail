package org.simplejavamail.internal.email;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;

import java.util.List;
import java.util.Objects;

/** Shared result assembly for core snapshot restoration, the main converter and the optional OpenPGP implementation. */
@ApiStatus.Internal
@Getter
@EqualsAndHashCode
@Builder
public final class OpenPgpDetailsImpl implements OriginalOpenPgpDetails {

	private static final long serialVersionUID = 1L;

	@Builder.Default private final OpenPgpMode openPgpMode = OpenPgpMode.PLAIN;
	@Builder.Default private final SignatureStatus signatureStatus = SignatureStatus.NOT_PRESENT;
	@Builder.Default private final DecryptionStatus decryptionStatus = DecryptionStatus.NOT_ENCRYPTED;
	@Nullable private final String signerKeyId;
	@Nullable private final String signerFingerprint;
	@Nullable private final String signatureAlgorithm;
	@Nullable private final String hashAlgorithm;
	@Nullable private final String encryptionAlgorithm;
	@Builder.Default private final List<String> recipientKeyIds = List.of();
	@Nullable private final String failureReason;
	@Getter(AccessLevel.NONE)
	private final byte @Nullable [] originalProtectedMessage;

	private OpenPgpDetailsImpl(final OpenPgpMode openPgpMode, final SignatureStatus signatureStatus,
			final DecryptionStatus decryptionStatus, @Nullable final String signerKeyId, @Nullable final String signerFingerprint,
			@Nullable final String signatureAlgorithm, @Nullable final String hashAlgorithm, @Nullable final String encryptionAlgorithm,
			final List<String> recipientKeyIds, @Nullable final String failureReason, final byte @Nullable [] originalProtectedMessage) {
		this.openPgpMode = Objects.requireNonNull(openPgpMode, "openPgpMode");
		this.signatureStatus = Objects.requireNonNull(signatureStatus, "signatureStatus");
		this.decryptionStatus = Objects.requireNonNull(decryptionStatus, "decryptionStatus");
		this.signerKeyId = signerKeyId;
		this.signerFingerprint = signerFingerprint;
		this.signatureAlgorithm = signatureAlgorithm;
		this.hashAlgorithm = hashAlgorithm;
		this.encryptionAlgorithm = encryptionAlgorithm;
		this.recipientKeyIds = List.copyOf(recipientKeyIds);
		this.failureReason = failureReason;
		this.originalProtectedMessage = originalProtectedMessage == null ? null : originalProtectedMessage.clone();
	}

	@NotNull
	public static OpenPgpDetailsImpl plain() {
		return builder().build();
	}

	@Override
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
}
