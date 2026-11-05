package org.simplejavamail.internal.outlooksupport.model;

import lombok.Builder;
import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.internal.outlooksupport.model.EmailFromOutlookMessage;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookMessage;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.api.outlook.OutlookMessageData;
import jakarta.activation.DataSource;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Wrapper class that can hold both the resulting Email (builder) and the source OutlookMessage.
 * <br>
 * Useful when data is needed which didn't convert directly into the Email (builder) instance.
 *
 * Prefer {@link OutlookEmailConversionResult} for public conversion APIs.
 */
@ApiStatus.Internal
public final class EmailFromOutlookMessageImpl implements EmailFromOutlookMessage {
	private final EmailPopulatingBuilder emailBuilder;
	private final OutlookMessage outlookMessage;
	private final OutlookMessageData outlookMessageData;
	private final Map<DataSource, Integer> attachmentSourceIndices;

	@Builder
	private EmailFromOutlookMessageImpl(final EmailPopulatingBuilder emailBuilder, final OutlookMessage outlookMessage,
			final OutlookMessageData outlookMessageData, final Map<DataSource, Integer> attachmentSourceIndices) {
		this.emailBuilder = emailBuilder;
		this.outlookMessage = outlookMessage;
		this.outlookMessageData = outlookMessageData;
		this.attachmentSourceIndices = Collections.unmodifiableMap(attachmentSourceIndices == null
				? new IdentityHashMap<>() : new IdentityHashMap<>(attachmentSourceIndices));
	}

	/** @see EmailFromOutlookMessage#getEmailBuilder() */
	@Override
	public EmailPopulatingBuilder getEmailBuilder() {
		return emailBuilder;
	}

	/** @see EmailFromOutlookMessage#getOutlookMessage() */
	@Override
	public OutlookMessage getOutlookMessage() {
		return outlookMessage;
	}

	/** @see EmailFromOutlookMessage#getOutlookMessageData() */
	@Override
	public OutlookMessageData getOutlookMessageData() {
		return outlookMessageData;
	}

	@Override
	public Map<DataSource, Integer> getAttachmentSourceIndices() {
		return attachmentSourceIndices;
	}

	/** @see EmailFromOutlookMessage#toOutlookEmailConversionResult() */
	@Override
	public OutlookEmailConversionResult toOutlookEmailConversionResult() {
		return OutlookEmailConversionResultImpl.builder()
			.emailBuilder(emailBuilder)
			.outlookMessageData(outlookMessageData)
			.build();
	}
}
