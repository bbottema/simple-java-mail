package org.simplejavamail.api.email;

import org.jetbrains.annotations.Nullable;

import java.io.Serializable;

/**
 * Indicates S/MIME details about an email. Used to show how a converted message was signed / encrypted and by whom.
 * <p>
 * Note: the difference between this and {@link org.simplejavamail.api.internal.smimesupport.model.SmimeDetails} is that
 * this class is intended for exposing S/MIME metadata to the end user, while the other class is for internal use
 * by the S/MIME module alone.
 *
 * @see EmailPopulatingBuilder#getOriginalSmimeDetails()
 */
public interface OriginalSmimeDetails extends Serializable {

	enum SmimeMode {
		PLAIN, SIGNED, ENCRYPTED, SIGNED_ENCRYPTED
	}

	@Nullable SmimeMode getSmimeMode();
	@Nullable String getSmimeMime();
	@Nullable String getSmimeType();
	@Nullable String getSmimeName();
	@Nullable String getSmimeProtocol();
	@Nullable String getSmimeMicalg();
	@Nullable String getSmimeSignedBy();
	@Nullable Boolean getSmimeSignatureValid();
}