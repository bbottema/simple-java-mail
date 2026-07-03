package org.simplejavamail.api.internal.outlooksupport.model;

import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.api.outlook.OutlookMessageData;

/**
 * Wrapper class that can hold both the resulting Email (builder) and the source OutlookMessage.
 * <br>
 * Useful when data is needed which didn't convert directly into the Email (builder) instance.
 *
 * Prefer {@link OutlookEmailConversionResult} for public conversion APIs.
 */
public class EmailFromOutlookMessage {
	private final EmailPopulatingBuilder emailBuilder;
	private final OutlookMessage outlookMessage;
	private final OutlookMessageData outlookMessageData;

	public EmailFromOutlookMessage(final EmailPopulatingBuilder emailBuilder, final OutlookMessage outlookMessage) {
		this(emailBuilder, outlookMessage, new OutlookMessageData(null, outlookMessage.getHeaders(), outlookMessage.getMessageClass(),
				outlookMessage.getDisplayTo(), outlookMessage.getDisplayCc(), outlookMessage.getDisplayBcc(), outlookMessage.getDate(),
				outlookMessage.getClientSubmitTime(), outlookMessage.getCreationDate(), outlookMessage.getLastModificationDate(),
				outlookMessage.getPropertiesAsHex(), outlookMessage.getPropertyCodes(), outlookMessage.getPropertyListing()));
	}

	public EmailFromOutlookMessage(final EmailPopulatingBuilder emailBuilder, final OutlookMessage outlookMessage, final OutlookMessageData outlookMessageData) {
		this.emailBuilder = emailBuilder;
		this.outlookMessage = outlookMessage;
		this.outlookMessageData = outlookMessageData;
	}

	public EmailPopulatingBuilder getEmailBuilder() {
		return emailBuilder;
	}

	public OutlookMessage getOutlookMessage() {
		return outlookMessage;
	}

	public OutlookMessageData getOutlookMessageData() {
		return outlookMessageData;
	}

	public OutlookEmailConversionResult toOutlookEmailConversionResult() {
		return new OutlookEmailConversionResult(emailBuilder, outlookMessageData);
	}
}
