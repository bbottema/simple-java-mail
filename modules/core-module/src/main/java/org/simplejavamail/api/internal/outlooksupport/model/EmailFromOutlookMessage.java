package org.simplejavamail.api.internal.outlooksupport.model;

import org.simplejavamail.api.email.EmailPopulatingBuilder;

public class EmailFromOutlookMessage {
	private final EmailPopulatingBuilder emailBuilder;
	private final OutlookMessage outlookMessage;

	public EmailFromOutlookMessage(final EmailPopulatingBuilder emailBuilder, final OutlookMessage outlookMessage) {
		this.emailBuilder = emailBuilder;
		this.outlookMessage = outlookMessage;
	}

	public EmailPopulatingBuilder getEmailBuilder() {
		return emailBuilder;
	}

	public OutlookMessage getOutlookMessage() {
		return outlookMessage;
	}
}
