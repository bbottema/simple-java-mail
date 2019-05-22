package org.simplejavamail.api.internal.outlooksupport.model;

import javax.annotation.Nullable;

/**
 * @see OutlookMessage
 */
public interface OutlookSmime {

	interface OutlookSmimeApplicationSmime extends OutlookSmime {
		@Nullable String getSmimeMime();
		@Nullable String getSmimeType();
		@Nullable String getSmimeName();
	}

	interface OutlookSmimeMultipartSigned extends OutlookSmime {
		@Nullable String getSmimeMime();
		@Nullable String getSmimeProtocol();
		@Nullable String getSmimeMicalg();
	}

	interface OutlookSmimeApplicationOctetStream extends OutlookSmime {

	}
}