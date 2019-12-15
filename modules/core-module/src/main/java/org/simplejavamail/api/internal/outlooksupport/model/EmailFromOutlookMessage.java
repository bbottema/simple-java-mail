package org.simplejavamail.api.internal.outlooksupport.model;

import org.simplejavamail.api.email.EmailPopulatingBuilder;

/**
 * Wrapper class that can hold both the resulting Email (builder) and the source OutlookMessage.
 * <br>
 * Useful when data is needed which didn't convert directly into the Email (builder) instance.
 */
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
