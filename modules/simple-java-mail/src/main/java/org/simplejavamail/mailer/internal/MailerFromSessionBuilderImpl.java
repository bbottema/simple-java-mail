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

import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;

import org.jetbrains.annotations.NotNull;
import javax.mail.Session;

/**
 * @see MailerFromSessionBuilder
 */
public class MailerFromSessionBuilderImpl
		extends MailerGenericBuilderImpl<MailerFromSessionBuilderImpl>
		implements MailerFromSessionBuilder<MailerFromSessionBuilderImpl> {
	
	/**
	 * @see #usingSession(Session)
	 */
	private Session session;
	
	/**
	 * @deprecated Used internally. Don't use this. Instead use {@link org.simplejavamail.mailer.MailerBuilder#usingSession(Session)}.
	 */
	@Deprecated
	@SuppressWarnings("DeprecatedIsStillUsed")
	public MailerFromSessionBuilderImpl() {
	}
	
	/**
	 * @see MailerFromSessionBuilder#usingSession(Session)
	 */
	@Override
	public MailerFromSessionBuilderImpl usingSession(@NotNull final Session session) {
		this.session = session;
		return this;
	}
	
	/**
	 * @see MailerFromSessionBuilder#buildMailer()
	 */
	@Override
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	public MailerImpl buildMailer() {
		return new MailerImpl(this);
	}
	
	/**
	 * @see MailerFromSessionBuilder#getSession()
	 */
	@Override
	public Session getSession() {
		return session;
	}
}