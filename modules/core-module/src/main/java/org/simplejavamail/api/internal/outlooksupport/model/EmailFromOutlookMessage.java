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
package org.simplejavamail.api.internal.outlooksupport.model;

import org.simplejavamail.api.email.EmailPopulatingBuilder;

/**
 * Wrapper class that can hold both the resulting Email (builder) and the source OutlookMessage.
 * <br>
 * Useful when data is needed which didn't convert directly into the Email (builder) instance.
 */
public class EmailFromOutlookMessage {
	private final EmailPopulatingBuilder emailBuilder;
	private final OutlookMessage outlookMessage;

	public EmailFromOutlookMessage(final EmailPopulatingBuilder emailBuilder, final OutlookMessage outlookMessage) {
		this.emailBuilder = emailBuilder;
		this.outlookMessage = outlookMessage;
	}

	public EmailPopulatingBuilder getEmailBuilder() {
		return emailBuilder;
	}

	public OutlookMessage getOutlookMessage() {
		return outlookMessage;
	}
}
