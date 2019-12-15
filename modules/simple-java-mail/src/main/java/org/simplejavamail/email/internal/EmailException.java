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
package org.simplejavamail.email.internal;

import org.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during the creation of an email.
 */
@SuppressWarnings("serial")
class EmailException extends MailException {
	
	static final String NAME_MISSING_FOR_EMBEDDED_IMAGE = "No name given for embedded image nor passed inside the data source";
	static final String ERROR_READING_FROM_FILE = "Error reading from file: %s";
	static final String ERROR_READING_FROM_PEM_INPUTSTREAM = "Was unable to convert PEM data to X509 certificate";
	static final String ERROR_LOADING_PROVIDER_FOR_SMIME_SUPPORT = "Unable to load certificate (missing bouncy castle), is the S/MIME module on the class path?";

	EmailException(@SuppressWarnings("SameParameterValue") final String message) {
		super(message);
	}
	
	EmailException(@SuppressWarnings("SameParameterValue") final String message, final Exception cause) {
		super(message, cause);
	}
}