package org.simplejavamail.internal.outlooksupport.internal.model;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookSmime;

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