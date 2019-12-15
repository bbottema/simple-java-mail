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

import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.OperationalConfig;

import org.jetbrains.annotations.NotNull;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;

/**
 * By default, Simple Java Mail handles the ultimate connection and sending of emails. However, it is possible to replace this last step
 * by a custom implementation.
 * <p>
 * The benefit of this is that Simple Java Mail acts as an accelarator, providing thread pool, applying email content-validation, address validations,
 * configuring a {@code Session} instance, producing a {@code MimeMessage}, all with full S/MIME, DKIM support and everything else.
 * <p>
 * <strong>Note:</strong> in this mode, proxy support is turned off assuming it is handled by the custom mailer as well.
 *
 * @see MailerGenericBuilder#withCustomMailer(CustomMailer)
 */
public interface CustomMailer {
	void testConnection(@NotNull OperationalConfig operationalConfig, @NotNull Session session);
	void sendMessage(@NotNull OperationalConfig operationalConfig, @NotNull Session session, final Email email, @NotNull MimeMessage message);
}