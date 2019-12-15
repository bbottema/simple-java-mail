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
package org.simplejavamail.internal.outlooksupport.converter;

import org.simplejavamail.MailException;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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