package org.simplejavamail.internal.smimesupport.model;

import org.simplejavamail.api.internal.smimesupport.model.SmimeDetails;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class SmimeDetailsImpl implements SmimeDetails {
	@NotNull private final String smimeMime;
	@Nullable private final String signedBy;

	public SmimeDetailsImpl(@NotNull final String smimeMime, @Nullable final String signedBy) {
		this.smimeMime = smimeMime;
		this.signedBy = signedBy;
	}

	@NotNull
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
