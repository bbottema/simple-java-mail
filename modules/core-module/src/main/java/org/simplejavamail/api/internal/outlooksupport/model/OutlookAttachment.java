package org.simplejavamail.api.internal.outlooksupport.model;

/**
 * @see OutlookMessage
 */
public interface OutlookAttachment {
	/** A nested message already materialized by the reader, or null for another attachment kind. */
	default OutlookMessage getEmbeddedMessage() {
		return null;
	}
}
