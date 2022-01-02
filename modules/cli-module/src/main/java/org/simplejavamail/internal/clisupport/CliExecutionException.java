package org.simplejavamail.internal.clisupport;

import org.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during the creation of an email.
 */
@SuppressWarnings("serial")
class CliExecutionException extends MailException {
	
	static final String WRONG_CURRENT_BUILDER = "Wrong argument(s) '%s' for '%s'.\nAlso, make sure you start with one of the following options:\n" +
			"\t\t--email:startingBlank\n" +
			"\t\t--email:copying message(=FILE)\n" +
			"\t\t--email:forwarding message(=FILE)\n" +
			"\t\t--email:replyingTo emailMessage(=FILE) repyToAll(=BOOL) htmlTemplate(=TEXT)\n" +
			"\t\t--email:replyingToSender message(=FILE) customQuotingTemplate(=TEXT)\n" +
			"\t\t--email:replyingToSenderWithDefaultQuoteMarkup message(=FILE)\n" +
			"\t\t--email:replyingToAll message(=FILE) customQuotingTemplate(=TEXT)\n" +
			"\t\t--email:replyingToAllWithDefaultQuoteMarkup message(=FILE)";
	static final String ERROR_INVOKING_BUILDER_API = "Got error while invoking Builder API with argument(s) '%s' for '%s'";
	
	CliExecutionException(String message, Exception cause) {
		super(message, cause);
	}
}