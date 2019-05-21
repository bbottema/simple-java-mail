package org.simplejavamail.internal.outlooksupport.internal.model;

import org.simplejavamail.api.internal.outlooksupport.model.OutlookAttachment;

/**
 * @see org.simplejavamail.api.internal.outlooksupport.model.OutlookMessage
 */
class OutlookAttachmentProxy implements OutlookAttachment {
	private final org.simplejavamail.outlookmessageparser.model.OutlookAttachment delegate;

	OutlookAttachmentProxy(final org.simplejavamail.outlookmessageparser.model.OutlookAttachment delegate) {
		this.delegate = delegate;
	}
}