package org.simplejavamail.api.internal.outlooksupport.model;

/**
 * @see OutlookMessage
 */
public interface OutlookSmime {

	interface OutlookSmimeApplicationSmime extends OutlookSmime {
		String getSmimeMime();
		String getSmimeType();
		String getSmimeName();
	}

	interface OutlookSmimeMultipartSigned extends OutlookSmime {
		String getSmimeMime();
		String getSmimeProtocol();
		String getSmimeMicalg();
	}

	interface OutlookSmimeApplicationOctetStream extends OutlookSmime {

	}
}