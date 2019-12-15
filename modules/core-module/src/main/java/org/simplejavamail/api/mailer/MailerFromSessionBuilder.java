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
package org.simplejavamail.api.mailer;

import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;

import org.jetbrains.annotations.NotNull;
import javax.mail.Message;
import javax.mail.Session;

/**
 * Builder that supports a fixed {@link Session} instance. Allows configuring all generic Mailer settings, but not SMTP and transport strategy
 * details.
 * <p>
 * <strong>Note:</strong> Any SMTP server properties that can be set on the {@link Session} object by are presumed to be already present in the passed
 * {@link Session} instance.
 *
 * @see org.simplejavamail.api.mailer.config.TransportStrategy
 */
@Cli.BuilderApiNode(builderApiType = CliBuilderApiType.MAILER)
public interface MailerFromSessionBuilder<T extends MailerFromSessionBuilder<?>> extends MailerGenericBuilder<T> {
	/**
	 * Only use this API if you <em>must</em> use your own {@link Session} instance. Assumes that all properties (except session timeout) used to make
	 * a connection are configured (host, port, authentication and transport protocol settings).
	 * <p>
	 * Only proxy can be configured optionally and general connection settings.
	 *
	 * @param session A mostly preconfigured mail {@link Session} object with which a {@link Message} can be produced.
	 */
	T usingSession(@NotNull Session session);
	
	/**
	 * Builds the actual {@link Mailer} instance with everything configured on this builder instance.
	 * <p>
	 * For all configurable values: if omitted, a default value will be attempted by looking at property files or manually defined defauls.
	 */
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	Mailer buildMailer();
	
	/**
	 * @see #usingSession(Session)
	 */
	Session getSession();
}
