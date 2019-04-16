package org.simplejavamail.internal.outlooksupport.internal.model;

import org.simplejavamail.api.internal.outlooksupport.model.OutlookFileAttachment;

/**
 * @see org.simplejavamail.api.internal.outlooksupport.model.OutlookMessage
 */
class OutlookFileAttachmentProxy implements OutlookFileAttachment {
	private final org.simplejavamail.outlookmessageparser.model.OutlookFileAttachment delegate;
	
	OutlookFileAttachmentProxy(final org.simplejavamail.outlookmessageparser.model.OutlookFileAttachment delegate) {
		this.delegate = delegate;
	}

	@Override
	public String getExtension() {
		return delegate.getExtension();
	}

	@Override
	public String getFilename() {
		return delegate.getFilename();
	}

	@Override
	public String getLongFilename() {
		return delegate.getLongFilename();
	}

	@Override
	public String getMimeTag() {
		return delegate.getMimeTag();
	}

	@Override
	public byte[] getData() {
		return delegate.getData();
	}

	@Override
	public long getSize() {
		return delegate.getSize();
	}
}
