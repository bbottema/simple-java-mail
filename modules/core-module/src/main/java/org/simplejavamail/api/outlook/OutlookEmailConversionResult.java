package org.simplejavamail.api.outlook;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;

/**
 * Result of converting an Outlook {@code .msg} message while retaining Outlook-specific source data.
 * <p>
 * The converted email builder intentionally contains only the headers that are safe and meaningful on a
 * Simple Java Mail {@link Email}. Headers that are structural to the source message, such as recipients,
 * dates and transport hops, are available through {@link #getOutlookMessageData()} instead.
 *
 * @see OutlookMessageData
 */
public interface OutlookEmailConversionResult {

	/**
	 * @return The converted email builder.
	 */
	@NotNull
	EmailPopulatingBuilder getEmailBuilder();

	/**
	 * @return Outlook-specific source metadata and raw headers from the converted {@code .msg} message.
	 */
	@NotNull
	OutlookMessageData getOutlookMessageData();

	/**
	 * Convenience method for {@code getEmailBuilder().buildEmail()}.
	 */
	@NotNull
	Email buildEmail();
}
