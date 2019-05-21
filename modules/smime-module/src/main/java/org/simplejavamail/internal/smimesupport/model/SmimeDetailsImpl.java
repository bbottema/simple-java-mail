package org.simplejavamail.internal.smimesupport.model;

import org.simplejavamail.api.internal.smimesupport.model.SmimeDetails;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SmimeDetailsImpl implements SmimeDetails {
	@Nonnull private final String smimeMime;
	@Nullable private final String signedBy;

	public SmimeDetailsImpl(@Nonnull final String smimeMime, @Nullable final String signedBy) {
		this.smimeMime = smimeMime;
		this.signedBy = signedBy;
	}

	@Nonnull
	@Override
	public String getSmimeMime() {
		return smimeMime;
	}

	@Nullable
	@Override
	public String getSignedBy() {
		return signedBy;
	}
}
