package org.simplejavamail.api.outlook;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;

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
public class OutlookEmailConversionResult {

	private final EmailPopulatingBuilder emailBuilder;
	private final OutlookMessageData outlookMessageData;

	public OutlookEmailConversionResult(@NotNull final EmailPopulatingBuilder emailBuilder, @NotNull final OutlookMessageData outlookMessageData) {
		this.emailBuilder = checkNonEmptyArgument(emailBuilder, "emailBuilder");
		this.outlookMessageData = checkNonEmptyArgument(outlookMessageData, "outlookMessageData");
	}

	/**
	 * @return The converted email builder.
	 */
	@NotNull
	public EmailPopulatingBuilder getEmailBuilder() {
		return emailBuilder;
	}

	/**
	 * @return Outlook-specific source metadata and raw headers from the converted {@code .msg} message.
	 */
	@NotNull
	public OutlookMessageData getOutlookMessageData() {
		return outlookMessageData;
	}

	/**
	 * Convenience method for {@code getEmailBuilder().buildEmail()}.
	 */
	@NotNull
	public Email buildEmail() {
		return emailBuilder.buildEmail();
	}
}
