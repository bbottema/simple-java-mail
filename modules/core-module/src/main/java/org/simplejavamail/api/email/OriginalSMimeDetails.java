package org.simplejavamail.api.email;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Objects;

import static org.simplejavamail.internal.util.SimpleOptional.ofNullable;

/**
 * Indicates S/MIME details about an email. Used to show how a converted message was signed / encrypted.
 *
 * @see EmailPopulatingBuilder#getOriginalSMimeDetails()
 */
public class OriginalSMimeDetails {
	@Nonnull
	private final String smimeMime;
	@Nullable
	private final String smimeType;
	@Nullable
	private final String smimeName;
	@Nullable
	private final String smimeSignedBy;

	/**
	 * @deprecated For internal use only. Do NOT use.
	 */
	@Deprecated
	public OriginalSMimeDetails(@Nonnull final String smimeMime, @Nullable final String smimeType, @Nullable final String smimeName, @Nullable final String smimeSignedBy) {
		this.smimeMime = smimeMime;
		this.smimeType = smimeType;
		this.smimeName = smimeName;
		this.smimeSignedBy = smimeSignedBy;
	}

	@Nonnull
	public OriginalSMimeDetails completeWith(@Nonnull final OriginalSMimeDetails attachmentSMimeDetails) {
		return new OriginalSMimeDetails(smimeMime,
				ofNullable(smimeType).orElse(attachmentSMimeDetails.smimeType),
				ofNullable(smimeName).orElse(attachmentSMimeDetails.smimeName),
				ofNullable(smimeSignedBy).orElse(attachmentSMimeDetails.smimeSignedBy));
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("SMimeDetails{");
		sb.append("smimeMime='").append(smimeMime).append('\'');
		sb.append(", smimeType='").append(smimeType).append('\'');
		sb.append(", smimeName='").append(smimeName).append('\'');
		sb.append(", smimeSignedBy='").append(smimeSignedBy).append('\'');
		sb.append('}');
		return sb.toString();
	}

	@Override
	public boolean equals(final Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		final OriginalSMimeDetails that = (OriginalSMimeDetails) o;
		return smimeMime.equals(that.smimeMime) &&
				Objects.equals(smimeType, that.smimeType) &&
				Objects.equals(smimeName, that.smimeName) &&
				Objects.equals(smimeSignedBy, that.smimeSignedBy);
	}

	@Override
	public int hashCode() {
		return Objects.hash(smimeMime, smimeType, smimeName, smimeSignedBy);
	}

	@Nonnull
	public String getSmimeMime() {
		return smimeMime;
	}

	@Nullable
	public String getSmimeType() {
		return smimeType;
	}

	@Nullable
	public String getSmimeName() {
		return smimeName;
	}

	@Nullable
	public String getSmimeSignedBy() {
		return smimeSignedBy;
	}
}