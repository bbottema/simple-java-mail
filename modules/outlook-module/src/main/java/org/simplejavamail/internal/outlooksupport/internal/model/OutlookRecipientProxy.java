package org.simplejavamail.internal.outlooksupport.internal.model;

import org.simplejavamail.api.internal.outlooksupport.model.OutlookRecipient;

import java.util.Set;

class OutlookRecipientProxy implements OutlookRecipient {
	private final org.simplejavamail.outlookmessageparser.model.OutlookRecipient delegate;

	OutlookRecipientProxy(final org.simplejavamail.outlookmessageparser.model.OutlookRecipient delegate) {
		this.delegate = delegate;
	}

	@Override
	public Set<Integer> getPropertyCodes() {
		return delegate.getPropertyCodes();
	}

	@Override
	public String getAddress() {
		return delegate.getAddress();
	}

	@Override
	public String getName() {
		return delegate.getName();
	}
}