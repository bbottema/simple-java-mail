/*
 * Copyright (C) 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.internal.outlooksupport.internal.model;

import org.simplejavamail.api.internal.outlooksupport.model.OutlookRecipient;

import java.util.Set;

/**
 * @see org.simplejavamail.api.internal.outlooksupport.model.OutlookMessage
 */
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