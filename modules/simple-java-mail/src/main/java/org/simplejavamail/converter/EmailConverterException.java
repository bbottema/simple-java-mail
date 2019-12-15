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
package org.simplejavamail.converter;

import org.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during the creation of an email.
 */
@SuppressWarnings("serial")
class EmailConverterException extends MailException {
	
	static final String FILE_NOT_RECOGNIZED_AS_EML = "Eml file should have \".eml\" extension: %s";
	static final String FILE_NOT_RECOGNIZED_AS_OUTLOOK = "Outlook file should have \".msg\" extension: %s";
	static final String PARSE_ERROR_EML_FROM_FILE = "Error parsing EML data from file: %s";
	static final String PARSE_ERROR_EML_FROM_STREAM = "Error parsing EML data from input stream: %s";
	static final String ERROR_READING_EML_INPUTSTREAM = "Error reading EML string from given InputStream";

	EmailConverterException(final String message) {
		super(message);
	}

	EmailConverterException(final String message, final Exception cause) {
		super(message, cause);
	}
}