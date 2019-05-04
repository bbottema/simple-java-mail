package org.simplejavamail.api.email;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Objects;

import static org.simplejavamail.internal.util.SimpleOptional.ofNullable;

/**
 * Indicates S/MIME details about an email. Used to show how a converted message was signed / encrypted and by whom.
 *
 * @see EmailPopulatingBuilder#getOriginalSmimeDetails()
 */
public class OriginalSmimeDetails {

	public static final OriginalSmimeDetails EMPTY = OriginalSmimeDetails.builder().build();

	@Nullable private final String smimeMime;
	@Nullable private final String smimeType;
	@Nullable private final String smimeName;
	@Nullable private final String smimeProtocol;
	@Nullable private final String smimeMicalg;
	@Nullable private final String smimeSignedBy;
	@Nullable private final Boolean smimeSignatureValid;

	@java.beans.ConstructorProperties({ "smimeMime", "smimeType", "smimeName", "smimeProtocol", "smimeMicalg", "smimeSignedBy", "smimeSignatureValid" })
	private OriginalSmimeDetails(@Nullable String smimeMime, @Nullable String smimeType, @Nullable String smimeName, @Nullable String smimeProtocol, @Nullable String smimeMicalg,
			@Nullable String smimeSignedBy, @Nullable Boolean smimeSignatureValid) {
		this.smimeMime = smimeMime;
		this.smimeType = smimeType;
		this.smimeName = smimeName;
		this.smimeProtocol = smimeProtocol;
		this.smimeMicalg = smimeMicalg;
		this.smimeSignedBy = smimeSignedBy;
		this.smimeSignatureValid = smimeSignatureValid;
	}

	/**
	 * For internal use only
	 * FIXME extract interface for public exposure, then hide this implementation
	 */
	@Deprecated
	public OriginalSmimeDetailsBuilder toBuilder() {
		return OriginalSmimeDetails.builder()
				.smimeMime(smimeMime)
				.smimeType(smimeType)
				.smimeName(smimeName)
				.smimeProtocol(smimeProtocol)
				.smimeMicalg(smimeMicalg)
				.smimeSignedBy(smimeSignedBy)
				.smimeSignatureValid(smimeSignatureValid);
	}

	/**
	 * For internal use only
	 * FIXME extract interface for public exposure, then hide this implementation
	 */
	@Deprecated
	public static OriginalSmimeDetailsBuilder builder() {
		return new OriginalSmimeDetailsBuilder();
	}

	/**
	 * Used to combine S/MIME details from several sources (OutlookMessage root level, and S/MIME signed attachment).
	 *
	 * @deprecated For internal use only. Do NOT use.
	 */
	@Deprecated
	@Nonnull
	public OriginalSmimeDetails completeWith(@Nonnull final OriginalSmimeDetails attachmentSmimeDetails) {
		return new OriginalSmimeDetails(
				ofNullable(smimeMime).orMaybe(attachmentSmimeDetails.smimeMime),
				ofNullable(smimeType).orMaybe(attachmentSmimeDetails.smimeType),
				ofNullable(smimeName).orMaybe(attachmentSmimeDetails.smimeName),
				ofNullable(smimeProtocol).orMaybe(attachmentSmimeDetails.smimeProtocol),
				ofNullable(smimeMicalg).orMaybe(attachmentSmimeDetails.smimeMicalg),
				ofNullable(smimeSignedBy).orMaybe(attachmentSmimeDetails.smimeSignedBy),
				ofNullable(smimeSignatureValid).orMaybe(attachmentSmimeDetails.smimeSignatureValid));
	}

	@Override
	public boolean equals(final Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		final OriginalSmimeDetails that = (OriginalSmimeDetails) o;
		return Objects.equals(smimeMime, that.smimeMime) &&
				Objects.equals(smimeType, that.smimeType) &&
				Objects.equals(smimeName, that.smimeName) &&
				Objects.equals(smimeProtocol, that.smimeProtocol) &&
				Objects.equals(smimeMicalg, that.smimeMicalg) &&
				Objects.equals(smimeSignedBy, that.smimeSignedBy) &&
				Objects.equals(smimeSignatureValid, that.smimeSignatureValid);
	}

	@Override
	public int hashCode() {
		return Objects.hash(smimeMime, smimeType, smimeName, smimeProtocol, smimeMicalg, smimeSignedBy, smimeSignatureValid);
	}

	public String toString() {
		return "OriginalSmimeDetails(smimeMime=" + this.getSmimeMime() + ", smimeType=" + this.getSmimeType() + ", smimeName=" + this.getSmimeName() + ", smimeProtocol=" + this.getSmimeProtocol()
				+ ", smimeMicalg=" + this.getSmimeMicalg() + ", smimeSignedBy=" + this.getSmimeSignedBy() + ", smimeSignatureValid=" + this.getSmimeSignatureValid() + ")";
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public String getSmimeMime() {
		return this.smimeMime;
	}

	@Nullable
	public String getSmimeType() {
		return this.smimeType;
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public String getSmimeName() {
		return this.smimeName;
	}

	@Nullable
	public String getSmimeProtocol() {
		return this.smimeProtocol;
	}

	@Nullable
	public String getSmimeMicalg() {
		return this.smimeMicalg;
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public String getSmimeSignedBy() {
		return this.smimeSignedBy;
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public Boolean getSmimeSignatureValid() {
		return this.smimeSignatureValid;
	}

	@SuppressWarnings("unused")
	public static class OriginalSmimeDetailsBuilder {
		private String smimeMime;
		private String smimeType;
		private String smimeName;
		private String smimeProtocol;
		private String smimeMicalg;
		private String smimeSignedBy;
		private Boolean smimeSignatureValid;

		OriginalSmimeDetailsBuilder() {
		}

		public OriginalSmimeDetails.OriginalSmimeDetailsBuilder smimeMime(@Nullable String smimeMime) {
			this.smimeMime = smimeMime;
			return this;
		}

		public OriginalSmimeDetails.OriginalSmimeDetailsBuilder smimeType(@Nullable String smimeType) {
			this.smimeType = smimeType;
			return this;
		}

		public OriginalSmimeDetails.OriginalSmimeDetailsBuilder smimeName(@Nullable String smimeName) {
			this.smimeName = smimeName;
			return this;
		}

		public OriginalSmimeDetails.OriginalSmimeDetailsBuilder smimeProtocol(@Nullable String smimeProtocol) {
			this.smimeProtocol = smimeProtocol;
			return this;
		}

		public OriginalSmimeDetails.OriginalSmimeDetailsBuilder smimeMicalg(@Nullable String smimeMicalg) {
			this.smimeMicalg = smimeMicalg;
			return this;
		}

		public OriginalSmimeDetails.OriginalSmimeDetailsBuilder smimeSignedBy(@Nullable String smimeSignedBy) {
			this.smimeSignedBy = smimeSignedBy;
			return this;
		}

		public OriginalSmimeDetails.OriginalSmimeDetailsBuilder smimeSignatureValid(@Nullable Boolean smimeSignatureValid) {
			this.smimeSignatureValid = smimeSignatureValid;
			return this;
		}

		public OriginalSmimeDetails build() {
			return new OriginalSmimeDetails(smimeMime, smimeType, smimeName, smimeProtocol, smimeMicalg, smimeSignedBy, smimeSignatureValid);
		}

		public String toString() {
			return "OriginalSmimeDetails.OriginalSmimeDetailsBuilder(smimeMime=" + this.smimeMime + ", smimeType=" + this.smimeType + ", smimeName=" + this.smimeName + ", smimeProtocol="
					+ this.smimeProtocol + ", smimeMicalg=" + this.smimeMicalg + ", smimeSignedBy=" + this.smimeSignedBy + ", smimeSignatureValid=" + this.smimeSignatureValid + ")";
		}
	}
}