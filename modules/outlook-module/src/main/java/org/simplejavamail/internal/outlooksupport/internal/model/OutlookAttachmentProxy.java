package org.simplejavamail.internal.outlooksupport.internal.model;

import org.simplejavamail.api.internal.outlooksupport.model.OutlookAttachment;

/**
 * @see org.simplejavamail.api.internal.outlooksupport.model.OutlookMessage
 */
class OutlookAttachmentProxy implements OutlookAttachment {
	@SuppressWarnings("FieldCanBeLocal")
	private final org.simplejavamail.outlookmessageparser.model.OutlookAttachment delegate;

	OutlookAttachmentProxy(final org.simplejavamail.outlookmessageparser.model.OutlookAttachment delegate) {
		this.delegate = delegate;
	}

	@Override
	public org.simplejavamail.api.internal.outlooksupport.model.OutlookMessage getEmbeddedMessage() {
		return delegate instanceof org.simplejavamail.outlookmessageparser.model.OutlookMsgAttachment
				? new OutlookMessageProxy(((org.simplejavamail.outlookmessageparser.model.OutlookMsgAttachment) delegate).getOutlookMessage())
				: null;
	}
}
