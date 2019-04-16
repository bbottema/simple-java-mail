package org.simplejavamail.internal.outlooksupport.internal.model;

import org.simplejavamail.api.internal.outlooksupport.model.OutlookAttachment;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookFileAttachment;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookMessage;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookRecipient;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class OutlookMessageProxy implements OutlookMessage {

	private final org.simplejavamail.outlookmessageparser.model.OutlookMessage delegate;

	public OutlookMessageProxy(org.simplejavamail.outlookmessageparser.model.OutlookMessage delegate) {
		this.delegate = delegate;
	}

	@Override
	public Map<String, OutlookFileAttachment> fetchCIDMap() {
		final Map<String, OutlookFileAttachment> wrappedCIDMap = new HashMap<>();
		for (Map.Entry<String, org.simplejavamail.outlookmessageparser.model.OutlookFileAttachment> cid : delegate.fetchCIDMap().entrySet()) {
			wrappedCIDMap.put(cid.getKey(), new OutlookFileAttachmentProxy(cid.getValue()));
		}
		return wrappedCIDMap;
	}

	@Override
	public List<OutlookFileAttachment> fetchTrueAttachments() {
		final List<OutlookFileAttachment> wrappedTrueAttachments = new ArrayList<>();
		for (org.simplejavamail.outlookmessageparser.model.OutlookFileAttachment trueAttachment : delegate.fetchTrueAttachments()) {
			wrappedTrueAttachments.add(new OutlookFileAttachmentProxy(trueAttachment));
		}
		return wrappedTrueAttachments;
	}

	@Override
	public String toLongString() {
		return delegate.toLongString();
	}

	@Override
	public List<OutlookAttachment> getOutlookAttachments() {
		final List<OutlookAttachment> wrappedAttachments = new ArrayList<>();
		for (org.simplejavamail.outlookmessageparser.model.OutlookAttachment attachment : delegate.getOutlookAttachments()) {
			wrappedAttachments.add(new OutlookAttachmentProxy(attachment));
		}
		return wrappedAttachments;
	}

	@Override
	public List<OutlookRecipient> getRecipients() {
		return wrapRecipients(delegate.getRecipients());
	}

	@Override
	public String getFromEmail() {
		return delegate.getFromEmail();
	}

	@Override
	public String getFromName() {
		return delegate.getFromName();
	}

	@Override
	public String getDisplayTo() {
		return delegate.getDisplayTo();
	}

	@Override
	public String getDisplayCc() {
		return delegate.getDisplayCc();
	}

	@Override
	public String getDisplayBcc() {
		return delegate.getDisplayBcc();
	}

	@Override
	public String getMessageClass() {
		return delegate.getMessageClass();
	}

	@Override
	public String getMessageId() {
		return delegate.getMessageId();
	}

	@Override
	public String getSubject() {
		return delegate.getSubject();
	}

	@Override
	public String getToEmail() {
		return delegate.getToEmail();
	}

	@Override
	public String getToName() {
		return delegate.getToName();
	}

	@Override
	public OutlookRecipient getToRecipient() {
		return new OutlookRecipientProxy(delegate.getToRecipient());
	}

	@Override
	public List<OutlookRecipient> getCcRecipients() {
		return wrapRecipients(delegate.getCcRecipients());
	}

	@Override
	public List<OutlookRecipient> getBccRecipients() {
		return wrapRecipients(delegate.getBccRecipients());
	}

	@Override
	public String getBodyText() {
		return delegate.getBodyText();
	}

	@Override
	public String getBodyRTF() {
		return delegate.getBodyRTF();
	}

	@Override
	public String getBodyHTML() {
		return delegate.getBodyHTML();
	}

	@Override
	public String getConvertedBodyHTML() {
		return delegate.getConvertedBodyHTML();
	}

	@Override
	public String getHeaders() {
		return delegate.getHeaders();
	}

	@Override
	public Date getDate() {
		return delegate.getDate();
	}

	@Override
	public Date getClientSubmitTime() {
		return delegate.getClientSubmitTime();
	}

	@Override
	public Date getCreationDate() {
		return delegate.getCreationDate();
	}

	@Override
	public Date getLastModificationDate() {
		return delegate.getLastModificationDate();
	}

	@Override
	public Set<String> getPropertiesAsHex() {
		return delegate.getPropertiesAsHex();
	}

	@Override
	public Set<Integer> getPropertyCodes() {
		return delegate.getPropertyCodes();
	}

	@Override
	public String getPropertyListing() {
		return delegate.getPropertyListing();
	}

	@Override
	public String getReplyToEmail() {
		return delegate.getReplyToEmail();
	}

	@Override
	public String getReplyToName() {
		return delegate.getReplyToName();
	}

	@Override
	public String getSmimeMime() {
		return delegate.getSmimeMime();
	}

	@Override
	public String getSmimeType() {
		return delegate.getSmimeType();
	}

	@Override
	public String getSmimeName() {
		return delegate.getSmimeName();
	}

	@Nonnull
	private static List<OutlookRecipient> wrapRecipients(List<org.simplejavamail.outlookmessageparser.model.OutlookRecipient> recipients) {
		final List<OutlookRecipient> wrappedRecipient = new ArrayList<>();
		for (org.simplejavamail.outlookmessageparser.model.OutlookRecipient recipient : recipients) {
			wrappedRecipient.add(new OutlookRecipientProxy(recipient));
		}
		return wrappedRecipient;
	}
}
