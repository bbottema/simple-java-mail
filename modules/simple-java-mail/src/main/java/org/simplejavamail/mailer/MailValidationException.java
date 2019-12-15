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
package org.simplejavamail.mailer;

import org.simplejavamail.MailException;

class MailValidationException extends MailException {

	static final String INVALID_RECIPIENT = "Invalid TO address: %s";
	static final String INVALID_REPLYTO = "Invalid REPLY TO address: %s";
	static final String INVALID_BOUNCETO = "Invalid BOUNCE TO address: %s";
	static final String INVALID_SENDER = "Invalid FROM address: %s";
	static final String INVALID_DISPOSITIONNOTIFICATIONTO = "Invalid \"Disposition Notification To\" address: %s";
	static final String INVALID_RETURNRECEIPTTO = "Invalid \"Return Receipt To\" address: %s";
	static final String MISSING_SENDER = "Email is not valid: missing sender. Provide with emailBuilder.from(...)";
	static final String MISSING_RECIPIENT = "Email is not valid: missing recipients";
	static final String MISSING_DISPOSITIONNOTIFICATIONTO = "Email is not valid: it is set to use \"Disposition Notification To\", but the address is empty";
	static final String MISSING_RETURNRECEIPTTO = "Email is not valid: it is set to use \"Return Receipt To\", but the address is empty";
	static final String INJECTION_SUSPECTED = "Suspected of injection attack, field: %s with suspicious value: %s";


	MailValidationException(final String message) {
		super(message);
	}
}
