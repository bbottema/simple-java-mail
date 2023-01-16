package org.simplejavamail.internal.outlooksupport.converter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * This exception is used to communicate errors during parsing of a MsgParser {@link org.simplejavamail.outlookmessageparser.model.OutlookMessage} of
 * Outlook .msg data.
 */
@SuppressWarnings("serial")
class OutlookMessageException extends MailException {

	static final String ERROR_PARSING_OUTLOOK_MSG = "Unable to parse Outlook message";

	OutlookMessageException(@SuppressWarnings("SameParameterValue") @NotNull final String message, @Nullable final Exception cause) {
		super(checkNonEmptyArgument(message, "message"), cause);
	}
}