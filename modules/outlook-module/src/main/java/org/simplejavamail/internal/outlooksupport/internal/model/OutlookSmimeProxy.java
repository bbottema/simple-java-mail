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

import org.simplejavamail.api.internal.outlooksupport.model.OutlookSmime;

import org.jetbrains.annotations.Nullable;

public abstract class OutlookSmimeProxy<T extends org.simplejavamail.outlookmessageparser.model.OutlookSmime> implements OutlookSmime {

	final T delegate;

	OutlookSmimeProxy(T delegate) {
		this.delegate = delegate;
	}

	public static class OutlookSmimeApplicationSmimeProxy
			extends OutlookSmimeProxy<org.simplejavamail.outlookmessageparser.model.OutlookSmime.OutlookSmimeApplicationSmime>
			implements OutlookSmimeApplicationSmime {
		public OutlookSmimeApplicationSmimeProxy(org.simplejavamail.outlookmessageparser.model.OutlookSmime.OutlookSmimeApplicationSmime delegate) {
			super(delegate);
		}
		@Nullable public String getSmimeMime() { return delegate.getSmimeMime(); }
		@Nullable public String getSmimeType() { return delegate.getSmimeType(); }
		@Nullable public String getSmimeName() { return delegate.getSmimeName(); }
	}

	public static class OutlookSmimeMultipartSignedProxy
			extends OutlookSmimeProxy<org.simplejavamail.outlookmessageparser.model.OutlookSmime.OutlookSmimeMultipartSigned>
			implements OutlookSmimeMultipartSigned {
		protected OutlookSmimeMultipartSignedProxy(final org.simplejavamail.outlookmessageparser.model.OutlookSmime.OutlookSmimeMultipartSigned delegate) {
			super(delegate);
		}
		@Nullable public String getSmimeMime() { return delegate.getSmimeMime(); }
		@Nullable public String getSmimeProtocol() { return delegate.getSmimeProtocol(); }
		@Nullable public String getSmimeMicalg() { return delegate.getSmimeMicalg(); }
	}

	public static class OutlookSmimeApplicationOctetStreamProxy
			extends OutlookSmimeProxy<org.simplejavamail.outlookmessageparser.model.OutlookSmime.OutlookSmimeApplicationOctetStream>
			implements OutlookSmimeApplicationOctetStream {
		protected OutlookSmimeApplicationOctetStreamProxy(final org.simplejavamail.outlookmessageparser.model.OutlookSmime.OutlookSmimeApplicationOctetStream delegate) {
			super(delegate);
		}
	}
}