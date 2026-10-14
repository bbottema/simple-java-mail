package org.simplejavamail.internal.outlooksupport.model;

import lombok.Builder;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.api.outlook.OutlookMessageData;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * Result of converting an Outlook {@code .msg} message while retaining Outlook-specific source data.
 * <p>
 * The converted email builder intentionally contains only the headers that are safe and meaningful on a
 * Simple Java Mail {@link Email}. Headers that are structural to the source message, such as recipients,
 * dates and transport hops, are available through {@link #getOutlookMessageData()} instead.
 *
 * @see OutlookMessageData
 */
@ApiStatus.Internal
public final class OutlookEmailConversionResultImpl implements OutlookEmailConversionResult {

	private final EmailPopulatingBuilder emailBuilder;
	private final OutlookMessageData outlookMessageData;

	@Builder
	private OutlookEmailConversionResultImpl(@NotNull final EmailPopulatingBuilder emailBuilder, @NotNull final OutlookMessageData outlookMessageData) {
		this.emailBuilder = checkNonEmptyArgument(emailBuilder, "emailBuilder");
		this.outlookMessageData = checkNonEmptyArgument(outlookMessageData, "outlookMessageData");
	}

	/** @see OutlookEmailConversionResult#getEmailBuilder() */
	@Override
	@NotNull
	public EmailPopulatingBuilder getEmailBuilder() {
		return emailBuilder;
	}

	/** @see OutlookEmailConversionResult#getOutlookMessageData() */
	@Override
	@NotNull
	public OutlookMessageData getOutlookMessageData() {
		return outlookMessageData;
	}

	/** @see OutlookEmailConversionResult#buildEmail() */
	@Override
	@NotNull
	public Email buildEmail() {
		return emailBuilder.buildEmail();
	}
}
