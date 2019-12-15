/*
 * Copyright (C) 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.internal.clisupport;

import org.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during the creation of an email.
 */
@SuppressWarnings("serial")
class CliExecutionException extends MailException {
	
	static final String WRONG_CURRENT_BUILDER = "Wrong argument for the current builder API. Make sure you start with one of the following options:\n" +
			"\t\t--email:startingBlank\n" +
			"\t\t--email:copying message(=FILE)\n" +
			"\t\t--email:forwarding message(=FILE)\n" +
			"\t\t--email:replyingTo emailMessage(=FILE) repyToAll(=BOOL) htmlTemplate(=TEXT)\n" +
			"\t\t--email:replyingToSender message(=FILE) customQuotingTemplate(=TEXT)\n" +
			"\t\t--email:replyingToSenderWithDefaultQuoteMarkup message(=FILE)\n" +
			"\t\t--email:replyingToAll message(=FILE) customQuotingTemplate(=TEXT)\n" +
			"\t\t--email:replyingToAllWithDefaultQuoteMarkup message(=FILE)";
	static final String ERROR_INVOKING_BUILDER_API = "Got error while invoking Builder API";
	
	CliExecutionException(String message, Exception cause) {
		super(message, cause);
	}
}