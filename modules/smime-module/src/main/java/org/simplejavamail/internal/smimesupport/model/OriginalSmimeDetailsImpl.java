package org.simplejavamail.internal.smimesupport.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.internal.smimesupport.SmimeRecognitionUtil;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Arrays;
import java.util.Objects;

import static java.util.Optional.ofNullable;

/**
 * @see OriginalSmimeDetails
 */
public class OriginalSmimeDetailsImpl implements OriginalSmimeDetails {

	private static final long serialVersionUID = 1234567L;

	@Nullable private SmimeMode smimeMode;
	@Nullable private String smimeMime;
	@Nullable private String smimeType;
	@Nullable private String smimeName;
	@Nullable private String smimeProtocol;
	@Nullable private String smimeMicalg;
	@Nullable private String smimeSignedBy;
	@Nullable private Boolean smimeSignatureValid;
	@NotNull private VerificationStatus verificationStatus;
	@NotNull private DecryptionStatus decryptionStatus;
	@Nullable private String failureReason;
	@Nullable private byte[] originalProtectedMessage;

	@java.beans.ConstructorProperties({ "smimeMode", "smimeMime", "smimeType", "smimeName", "smimeProtocol", "smimeMicalg", "smimeSignedBy", "smimeSignatureValid", "verificationStatus", "decryptionStatus", "failureReason", "originalProtectedMessage" })
	private OriginalSmimeDetailsImpl(@Nullable SmimeMode smimeMode, @Nullable String smimeMime, @Nullable String smimeType, @Nullable String smimeName, @Nullable String smimeProtocol,
			@Nullable String smimeMicalg,
			@Nullable String smimeSignedBy, @Nullable Boolean smimeSignatureValid,
			@Nullable VerificationStatus verificationStatus, @Nullable DecryptionStatus decryptionStatus,
			@Nullable String failureReason, final byte @Nullable [] originalProtectedMessage) {
		this.smimeMime = smimeMime;
		this.smimeType = smimeType;
		this.smimeName = smimeName;
		this.smimeProtocol = smimeProtocol;
		this.smimeMicalg = smimeMicalg;
		this.smimeSignedBy = smimeSignedBy;
		this.verificationStatus = mergeVerificationStatus(
				statusFromLegacyBoolean(smimeSignatureValid),
				verificationStatus != null ? verificationStatus : VerificationStatus.NOT_SIGNED);
		this.smimeSignatureValid = legacyBooleanFromStatus(this.verificationStatus);
		this.decryptionStatus = decryptionStatus != null ? decryptionStatus : DecryptionStatus.NOT_ENCRYPTED;
		this.failureReason = failureReason;
		this.originalProtectedMessage = originalProtectedMessage == null ? null : originalProtectedMessage.clone();

		this.smimeMode = determineSmode(smimeMode);
	}

	private void readObject(final ObjectInputStream input) throws IOException, ClassNotFoundException {
		input.defaultReadObject();
		verificationStatus = mergeVerificationStatus(
				statusFromLegacyBoolean(smimeSignatureValid),
				verificationStatus != null ? verificationStatus : VerificationStatus.NOT_SIGNED);
		smimeSignatureValid = legacyBooleanFromStatus(verificationStatus);
		if (decryptionStatus == null) {
			decryptionStatus = DecryptionStatus.NOT_ENCRYPTED;
		}
	}

	public static OriginalSmimeDetailsBuilder builder() {
		return new OriginalSmimeDetailsBuilder();
	}

	/**
	 * Used to combine S/MIME details from several sources (OutlookMessage root level, and S/MIME signed attachment).
	 */
	public void completeWith(@NotNull final OriginalSmimeDetails attachmentSmimeDetails) {
		this.smimeMime = ofNullable(smimeMime).orElse(attachmentSmimeDetails.getSmimeMime());
		this.smimeType = ofNullable(smimeType).orElse(attachmentSmimeDetails.getSmimeType());
		this.smimeName = ofNullable(smimeName).orElse(attachmentSmimeDetails.getSmimeName());
		this.smimeProtocol = ofNullable(smimeProtocol).orElse(attachmentSmimeDetails.getSmimeProtocol());
		this.smimeMicalg = ofNullable(smimeMicalg).orElse(attachmentSmimeDetails.getSmimeMicalg());
		this.smimeSignedBy = ofNullable(smimeSignedBy).orElse(attachmentSmimeDetails.getSmimeSignedBy());
		final VerificationStatus additionalVerification = attachmentSmimeDetails.getVerificationStatus();
		final DecryptionStatus additionalDecryption = attachmentSmimeDetails.getDecryptionStatus();
		final boolean additionalResultIsMoreSevere = verificationSeverity(additionalVerification) > verificationSeverity(verificationStatus)
				|| decryptionSeverity(additionalDecryption) > decryptionSeverity(decryptionStatus);
		this.verificationStatus = mergeVerificationStatus(verificationStatus, additionalVerification);
		this.smimeSignatureValid = legacyBooleanFromStatus(verificationStatus);
		this.decryptionStatus = mergeDecryptionStatus(decryptionStatus, additionalDecryption);
		if (failureReason == null || additionalResultIsMoreSevere && attachmentSmimeDetails.getFailureReason() != null) {
			this.failureReason = attachmentSmimeDetails.getFailureReason();
		}
		final byte[] additionalOriginal = attachmentSmimeDetails.getOriginalProtectedMessage();
		if (originalProtectedMessage == null && additionalOriginal != null) {
			this.originalProtectedMessage = additionalOriginal;
		}
		this.smimeMode = determineSmode(ofNullable(this.smimeMode).orElse(attachmentSmimeDetails.getSmimeMode()));
	}

	@Nullable
	private SmimeMode determineSmode(@Nullable final SmimeMode smimeMode) {
		return smimeMode == SmimeMode.PLAIN
				? SmimeRecognitionUtil.determineSmimeMode(this)
				: smimeMode;
	}

	public void completeWithSmimeSignedBy(@Nullable final String smimeSignedBy) {
		this.smimeSignedBy = ofNullable(this.smimeSignedBy).orElse(smimeSignedBy);
	}

	public void completeWithSmimeSignatureValid(final boolean signatureValid) {
		this.verificationStatus = mergeVerificationStatus(this.verificationStatus,
				signatureValid ? VerificationStatus.VALID : VerificationStatus.INVALID);
		this.smimeSignatureValid = legacyBooleanFromStatus(this.verificationStatus);
	}

	@NotNull
	private static VerificationStatus statusFromLegacyBoolean(@Nullable final Boolean signatureValid) {
		return signatureValid == null ? VerificationStatus.NOT_SIGNED
				: signatureValid ? VerificationStatus.VALID : VerificationStatus.INVALID;
	}

	@Nullable
	private static Boolean legacyBooleanFromStatus(@NotNull final VerificationStatus status) {
		return status == VerificationStatus.NOT_SIGNED ? null : status == VerificationStatus.VALID;
	}

	@NotNull
	private static VerificationStatus mergeVerificationStatus(@NotNull final VerificationStatus current,
			@NotNull final VerificationStatus additional) {
		return verificationSeverity(additional) > verificationSeverity(current) ? additional : current;
	}

	private static int verificationSeverity(@NotNull final VerificationStatus status) {
		switch (status) {
			case NOT_SIGNED:
				return 0;
			case VALID:
				return 1;
			case INVALID:
				return 2;
			case ERROR:
				return 3;
			default:
				throw new AssertionError("Unsupported verification status: " + status);
		}
	}

	@NotNull
	private static DecryptionStatus mergeDecryptionStatus(@NotNull final DecryptionStatus current,
			@NotNull final DecryptionStatus additional) {
		return decryptionSeverity(additional) > decryptionSeverity(current) ? additional : current;
	}

	private static int decryptionSeverity(@NotNull final DecryptionStatus status) {
		switch (status) {
			case NOT_ENCRYPTED:
				return 0;
			case DECRYPTED:
				return 1;
			case KEY_MISSING:
				return 2;
			case FAILED:
				return 3;
			default:
				throw new AssertionError("Unsupported decryption status: " + status);
		}
	}

	public void completeWithSmimeMode(final SmimeMode smimeMode) {
		this.smimeMode = determineSmode(smimeMode);
	}

	@Override
	public boolean equals(@Nullable final Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		final OriginalSmimeDetailsImpl that = (OriginalSmimeDetailsImpl) o;
		return Objects.equals(smimeMode, that.smimeMode) &&
				Objects.equals(smimeMime, that.smimeMime) &&
				Objects.equals(smimeType, that.smimeType) &&
				Objects.equals(smimeName, that.smimeName) &&
				Objects.equals(smimeProtocol, that.smimeProtocol) &&
				Objects.equals(smimeMicalg, that.smimeMicalg) &&
				Objects.equals(smimeSignedBy, that.smimeSignedBy) &&
				Objects.equals(smimeSignatureValid, that.smimeSignatureValid) &&
				verificationStatus == that.verificationStatus &&
				decryptionStatus == that.decryptionStatus &&
				Objects.equals(failureReason, that.failureReason) &&
				Arrays.equals(originalProtectedMessage, that.originalProtectedMessage);
	}

	@Override
	public int hashCode() {
		return 31 * Objects.hash(smimeMode, smimeMime, smimeType, smimeName, smimeProtocol, smimeMicalg,
				smimeSignedBy, smimeSignatureValid, verificationStatus, decryptionStatus, failureReason)
				+ Arrays.hashCode(originalProtectedMessage);
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("OriginalSmimeDetails{");
		return toString(sb, smimeMode, smimeMime, smimeType, smimeName, smimeProtocol, smimeMicalg,
				smimeSignedBy, smimeSignatureValid, verificationStatus, decryptionStatus, failureReason,
				originalProtectedMessage);
	}

	@Override
	@Nullable
	public SmimeMode getSmimeMode() {
		return this.smimeMode;
	}

	@Override
	@Nullable
	public String getSmimeMime() {
		return this.smimeMime;
	}

	@Override
	@Nullable
	public String getSmimeType() {
		return this.smimeType;
	}

	@Override
	@Nullable
	public String getSmimeName() {
		return this.smimeName;
	}

	@Override
	@Nullable
	public String getSmimeProtocol() {
		return this.smimeProtocol;
	}

	@Override
	@Nullable
	public String getSmimeMicalg() {
		return this.smimeMicalg;
	}

	@Override
	@Nullable
	public String getSmimeSignedBy() {
		return this.smimeSignedBy;
	}

	@Override
	@Nullable
	public Boolean getSmimeSignatureValid() {
		return this.smimeSignatureValid;
	}

	@NotNull
	@Override
	public VerificationStatus getVerificationStatus() {
		return verificationStatus;
	}

	@NotNull
	@Override
	public DecryptionStatus getDecryptionStatus() {
		return decryptionStatus;
	}

	@Nullable
	@Override
	public String getFailureReason() {
		return failureReason;
	}

	@Override
	public byte @Nullable [] getOriginalProtectedMessage() {
		return originalProtectedMessage == null ? null : originalProtectedMessage.clone();
	}

	@SuppressWarnings("unused")
	public static class OriginalSmimeDetailsBuilder {
		@Nullable private SmimeMode smimeMode = SmimeMode.PLAIN;
		@Nullable private String smimeMime;
		@Nullable private String smimeType;
		@Nullable private String smimeName;
		@Nullable private String smimeProtocol;
		@Nullable private String smimeMicalg;
		@Nullable private String smimeSignedBy;
		@Nullable private Boolean smimeSignatureValid;
		@Nullable private VerificationStatus verificationStatus;
		@Nullable private DecryptionStatus decryptionStatus;
		@Nullable private String failureReason;
		@Nullable private byte[] originalProtectedMessage;

		OriginalSmimeDetailsBuilder() {
		}

		public OriginalSmimeDetailsImpl.OriginalSmimeDetailsBuilder smimeMode(@Nullable SmimeMode smimeMode) {
			this.smimeMode = smimeMode;
			return this;
		}

		public OriginalSmimeDetailsImpl.OriginalSmimeDetailsBuilder smimeMime(@Nullable String smimeMime) {
			this.smimeMime = smimeMime;
			return this;
		}

		public OriginalSmimeDetailsImpl.OriginalSmimeDetailsBuilder smimeType(@Nullable String smimeType) {
			this.smimeType = smimeType;
			return this;
		}

		public OriginalSmimeDetailsImpl.OriginalSmimeDetailsBuilder smimeName(@Nullable String smimeName) {
			this.smimeName = smimeName;
			return this;
		}

		public OriginalSmimeDetailsImpl.OriginalSmimeDetailsBuilder smimeProtocol(@Nullable String smimeProtocol) {
			this.smimeProtocol = smimeProtocol;
			return this;
		}

		public OriginalSmimeDetailsImpl.OriginalSmimeDetailsBuilder smimeMicalg(@Nullable String smimeMicalg) {
			this.smimeMicalg = smimeMicalg;
			return this;
		}

		public OriginalSmimeDetailsImpl.OriginalSmimeDetailsBuilder smimeSignedBy(@Nullable String smimeSignedBy) {
			this.smimeSignedBy = smimeSignedBy;
			return this;
		}

		public OriginalSmimeDetailsImpl.OriginalSmimeDetailsBuilder smimeSignatureValid(@Nullable Boolean smimeSignatureValid) {
			this.smimeSignatureValid = smimeSignatureValid;
			return this;
		}

		public OriginalSmimeDetailsImpl.OriginalSmimeDetailsBuilder verificationStatus(@Nullable VerificationStatus verificationStatus) {
			this.verificationStatus = verificationStatus;
			return this;
		}

		public OriginalSmimeDetailsImpl.OriginalSmimeDetailsBuilder decryptionStatus(@Nullable DecryptionStatus decryptionStatus) {
			this.decryptionStatus = decryptionStatus;
			return this;
		}

		public OriginalSmimeDetailsImpl.OriginalSmimeDetailsBuilder failureReason(@Nullable String failureReason) {
			this.failureReason = failureReason;
			return this;
		}

		public OriginalSmimeDetailsImpl.OriginalSmimeDetailsBuilder originalProtectedMessage(final byte @Nullable [] originalProtectedMessage) {
			this.originalProtectedMessage = originalProtectedMessage == null ? null : originalProtectedMessage.clone();
			return this;
		}

		public OriginalSmimeDetailsImpl build() {
			return new OriginalSmimeDetailsImpl(smimeMode, smimeMime, smimeType, smimeName, smimeProtocol, smimeMicalg,
					smimeSignedBy, smimeSignatureValid, verificationStatus, decryptionStatus, failureReason, originalProtectedMessage);
		}

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder("OriginalSmimeDetailsBuilder{");
			return OriginalSmimeDetailsImpl.toString(sb, smimeMode, smimeMime, smimeType, smimeName, smimeProtocol,
					smimeMicalg, smimeSignedBy, smimeSignatureValid, verificationStatus, decryptionStatus,
					failureReason, originalProtectedMessage);
		}
	}

	@NotNull
	private static String toString(final StringBuilder sb, @Nullable final SmimeMode smimeMode,
			@Nullable final String smimeMime, @Nullable final String smimeType, @Nullable final String smimeName,
			@Nullable final String smimeProtocol, @Nullable final String smimeMicalg,
			@Nullable final String smimeSignedBy, @Nullable final Boolean smimeSignatureValid,
			@Nullable final VerificationStatus verificationStatus, @Nullable final DecryptionStatus decryptionStatus,
			@Nullable final String failureReason, final byte @Nullable [] originalProtectedMessage) {
		sb.append("smimeMode=").append(smimeMode);
		sb.append(", smimeMime='").append(smimeMime).append('\'');
		sb.append(", smimeType='").append(smimeType).append('\'');
		sb.append(", smimeName='").append(smimeName).append('\'');
		sb.append(", smimeProtocol='").append(smimeProtocol).append('\'');
		sb.append(", smimeMicalg='").append(smimeMicalg).append('\'');
		sb.append(", smimeSignedBy='").append(smimeSignedBy).append('\'');
		sb.append(", smimeSignatureValid=").append(smimeSignatureValid);
		sb.append(", verificationStatus=").append(verificationStatus);
		sb.append(", decryptionStatus=").append(decryptionStatus);
		sb.append(", failureReason='").append(failureReason).append('\'');
		sb.append(", originalProtectedMessage=").append(originalProtectedMessage == null ? "none" : "preserved");
		sb.append('}');
		return sb.toString();
	}
}
