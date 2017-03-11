package org.simplejavamail.converter.internal.msgparser;

import org.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during parsing of a MsgParser {@link org.simplejavamail.outlookmessageparser.model.OutlookMessage} of Outlook
 * .msg data.
 *
 * @author Benny Bottema
 */
@SuppressWarnings("serial")
class OutlookMessageException extends MailException {

	static final String ERROR_PARSING_OUTLOOK_MSG = "Unable to parse Outlook message";

	OutlookMessageException(@SuppressWarnings("SameParameterValue") final String message, final Exception cause) {
		super(message, cause);
	}
}