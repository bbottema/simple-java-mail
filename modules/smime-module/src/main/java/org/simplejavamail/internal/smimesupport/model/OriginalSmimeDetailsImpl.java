package org.simplejavamail.internal.smimesupport.model;

import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.internal.smimesupport.SmimeRecognitionUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Objects;

import static java.lang.Boolean.TRUE;
import static org.simplejavamail.internal.util.SimpleOptional.ofNullable;

/**
 * @see OriginalSmimeDetails
 */
public class OriginalSmimeDetailsImpl implements OriginalSmimeDetails {

	@Nonnull private SmimeMode smimeMode;
	@Nullable private String smimeMime;
	@Nullable private String smimeType;
	@Nullable private String smimeName;
	@Nullable private String smimeProtocol;
	@Nullable private String smimeMicalg;
	@Nullable private String smimeSignedBy;
	@Nullable private Boolean smimeSignatureValid;

	@java.beans.ConstructorProperties({ "smimeMime", "smimeType", "smimeName", "smimeProtocol", "smimeMicalg", "smimeSignedBy", "smimeSignatureValid" })
	private OriginalSmimeDetailsImpl(@Nonnull SmimeMode smimeMode, @Nullable String smimeMime, @Nullable String smimeType, @Nullable String smimeName, @Nullable String smimeProtocol,
			@Nullable String smimeMicalg,
			@Nullable String smimeSignedBy, @Nullable Boolean smimeSignatureValid) {
		this.smimeMime = smimeMime;
		this.smimeType = smimeType;
		this.smimeName = smimeName;
		this.smimeProtocol = smimeProtocol;
		this.smimeMicalg = smimeMicalg;
		this.smimeSignedBy = smimeSignedBy;
		this.smimeSignatureValid = smimeSignatureValid;

		this.smimeMode = determineSmode(smimeMode);
	}

	public static OriginalSmimeDetailsBuilder builder() {
		return new OriginalSmimeDetailsBuilder();
	}

	/**
	 * Used to combine S/MIME details from several sources (OutlookMessage root level, and S/MIME signed attachment).
	 */
	public void completeWith(@Nonnull final OriginalSmimeDetailsImpl attachmentSmimeDetails) {
		this.smimeMime = ofNullable(smimeMime).orMaybe(attachmentSmimeDetails.smimeMime);
		this.smimeType = ofNullable(smimeType).orMaybe(attachmentSmimeDetails.smimeType);
		this.smimeName = ofNullable(smimeName).orMaybe(attachmentSmimeDetails.smimeName);
		this.smimeProtocol = ofNullable(smimeProtocol).orMaybe(attachmentSmimeDetails.smimeProtocol);
		this.smimeMicalg = ofNullable(smimeMicalg).orMaybe(attachmentSmimeDetails.smimeMicalg);
		this.smimeSignedBy = ofNullable(smimeSignedBy).orMaybe(attachmentSmimeDetails.smimeSignedBy);
		this.smimeSignatureValid = ofNullable(smimeSignatureValid).orMaybe(attachmentSmimeDetails.smimeSignatureValid);

		this.smimeMode = determineSmode(ofNullable(smimeMode).orElse(attachmentSmimeDetails.smimeMode));
	}

	private SmimeMode determineSmode(@Nonnull final SmimeMode smimeMode) {
		return smimeMode == SmimeMode.PLAIN
				? SmimeRecognitionUtil.determineSmimeMode(this)
				: smimeMode;
	}

	public void completeWithSmimeSignedBy(@Nullable final String smimeSignedBy) {
		this.smimeSignedBy = ofNullable(this.smimeSignedBy).orMaybe(smimeSignedBy);
	}

	public void completeWithSmimeSignatureValid(final boolean signatureValid) {
		this.smimeSignatureValid = TRUE.equals(this.smimeSignatureValid) || signatureValid;
	}

	public void completeWithSmimeMode(final SmimeMode smimeMode) {
		this.smimeMode = determineSmode(smimeMode);
	}

	@Override
	public boolean equals(final Object o) {
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
				Objects.equals(smimeSignatureValid, that.smimeSignatureValid);
	}

	@Override
	public int hashCode() {
		return Objects.hash(smimeMode, smimeMime, smimeType, smimeName, smimeProtocol, smimeMicalg, smimeSignedBy, smimeSignatureValid);
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("OriginalSmimeDetails{");
		return toString(sb, smimeMode, smimeMime, smimeType, smimeName, smimeProtocol, smimeMicalg, smimeSignedBy, smimeSignatureValid);
	}

	@Override
	@Nonnull
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

	@SuppressWarnings("unused")
	public static class OriginalSmimeDetailsBuilder {
		private SmimeMode smimeMode = SmimeMode.PLAIN;
		private String smimeMime;
		private String smimeType;
		private String smimeName;
		private String smimeProtocol;
		private String smimeMicalg;
		private String smimeSignedBy;
		private Boolean smimeSignatureValid;

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

		public OriginalSmimeDetailsImpl build() {
			return new OriginalSmimeDetailsImpl(smimeMode, smimeMime, smimeType, smimeName, smimeProtocol, smimeMicalg, smimeSignedBy, smimeSignatureValid);
		}

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder("OriginalSmimeDetailsBuilder{");
			return OriginalSmimeDetailsImpl.toString(sb, smimeMode, smimeMime, smimeType, smimeName, smimeProtocol, smimeMicalg, smimeSignedBy, smimeSignatureValid);
		}
	}

	@Nonnull
	private static String toString(final StringBuilder sb, final SmimeMode smimeMode, final String smimeMime, final String smimeType, final String smimeName, final String smimeProtocol,
			final String smimeMicalg, final String smimeSignedBy, final Boolean smimeSignatureValid) {
		sb.append("smimeMode=").append(smimeMode);
		sb.append(", smimeMime='").append(smimeMime).append('\'');
		sb.append(", smimeType='").append(smimeType).append('\'');
		sb.append(", smimeName='").append(smimeName).append('\'');
		sb.append(", smimeProtocol='").append(smimeProtocol).append('\'');
		sb.append(", smimeMicalg='").append(smimeMicalg).append('\'');
		sb.append(", smimeSignedBy='").append(smimeSignedBy).append('\'');
		sb.append(", smimeSignatureValid=").append(smimeSignatureValid);
		sb.append('}');
		return sb.toString();
	}
}