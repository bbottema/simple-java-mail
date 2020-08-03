package org.simplejavamail.converter.internal.mimemessage;

import org.simplejavamail.MailException;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * This exception is used to communicate errors during parsing of a {@link javax.mail.internet.MimeMessage}.
 */
@SuppressWarnings("serial")
class MimeMessageParseException extends MailException {

	static final String ERROR_PARSING_FROMADDRESS = "Error parsing from-address";
	static final String ERROR_PARSING_ADDRESS = "Error parsing [%s] address [%s]";
	static final String ERROR_PARSING_DISPOSITION = "Error parsing MimeMessage disposition";
	static final String ERROR_PARSING_CONTENT = "Error parsing MimeMessage Content";
	static final String ERROR_PARSING_CALENDAR_CONTENT = "Error parsing MimeMessage Calendar content";
	static final String ERROR_PARSING_MULTIPART_COUNT = "Error parsing MimeMessage multipart count";
	static final String ERROR_GETTING_BODYPART_AT_INDEX = "Error getting bodypart at index %s";
	static final String ERROR_GETTING_CONTENT_ID = "Error getting content ID";
	static final String ERROR_GETTING_CALENDAR_CONTENTTYPE = "Error getting content type from Calendar bodypart. Unable to determine Calendar METHOD";
	static final String ERROR_GETTING_FILENAME = "Error getting file name";
	static final String ERROR_GETTING_ALL_HEADERS = "Error getting all headers";
	static final String ERROR_GETTING_DATAHANDLER = "Error getting data handler";
	static final String ERROR_GETTING_CONTENT_TYPE = "Error getting content type";
	static final String ERROR_GETTING_INPUTSTREAM = "Error getting input stream";
	static final String ERROR_READING_CONTENT = "Error reading content";
	static final String ERROR_DECODING_TEXT = "Error decoding text";
	static final String ERROR_GETTING_RECIPIENTS = "Error getting [%s] recipient types";
	static final String ERROR_GETTING_SUBJECT = "Error getting subject";
	static final String ERROR_GETTING_MESSAGE_ID = "Error getting message ID";
	static final String ERROR_GETTING_SEND_DATE = "Error getting sent-date";
	static final String ERROR_PARSING_REPLY_TO_ADDRESSES = "Error parsing replyTo addresses";

	MimeMessageParseException(@NotNull final String message, @Nullable final Exception cause) {
		super(checkNonEmptyArgument(message, "message"), cause);
	}
}