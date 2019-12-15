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
package org.simplejavamail.mailer.internal;

import org.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during the sending of email.
 */
@SuppressWarnings("serial")
class MailerException extends MailException {

	static final String INVALID_PROXY_SLL_COMBINATION = "Proxy is not supported for SSL connections (this is a limitation by the underlying JavaMail framework)";
	static final String GENERIC_ERROR = "Third party error";
	static final String INVALID_ENCODING = "Encoding not accepted";
	static final String ERROR_CONNECTING_SMTP_SERVER = "Was unable to connect to SMTP server";

	MailerException(@SuppressWarnings("SameParameterValue") final String message) {
		super(message);
	}

	MailerException(final String message, final Exception cause) {
		super(message, cause);
	}
}