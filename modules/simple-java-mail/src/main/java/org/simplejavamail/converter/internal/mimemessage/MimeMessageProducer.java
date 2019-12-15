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
package org.simplejavamail.converter.internal.mimemessage;

import org.simplejavamail.api.email.Email;
import org.simplejavamail.internal.modules.ModuleLoader;

import org.jetbrains.annotations.NotNull;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.util.Date;

import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.SimpleOptional.ofNullable;

/**
 * Helper class that produces and populates a mime messages. Deals with javax.mail RFC MimeMessage stuff, as well as
 * <a href="http://www.simplejavamail.org/#section-dkim">DKIM signing</a> and
 * <a href="http://www.simplejavamail.org/#section-sending-smime">S/MIME signing / encryption</a>.
 * <p>
 * Some more <a href="https://blogs.technet.microsoft.com/exchange/2011/04/21/mixed-ing-it-up-multipartmixed-messages-and-you/">helpful reading
 * material</a>.
 * <p>
 * One goal of these classes is to produce a Mime structure that exactly matches the email's need. Previously, Simple Java Mail
 * used a complex RFC-conforming structure that is compatible with all situations, but it seems
 * <a href="https://github.com/bbottema/simple-java-mail/issues/133">some email clients</a> would still get confused.
 * Due to this, we explicitely define all possible structures so we take the least complex one needed.
 *
 * @see <a href="https://github.com/bbottema/simple-java-mail/issues/144">#144: Simple Java Mail should tailor the MimeMessage structure to specific needs</a>
 */
public abstract class MimeMessageProducer {
	
	/**
	 * @return Whether this mimemessage producer exactly matches the needs of the given email.
	 */
	abstract boolean compatibleWithEmail(@NotNull Email email);
	
	final MimeMessage populateMimeMessage(@NotNull final Email email, @NotNull Session session)
			throws MessagingException, UnsupportedEncodingException {
		checkArgumentNotEmpty(email, "email is missing");
		checkArgumentNotEmpty(session, "session is needed, it cannot be attached later");

		MimeMessage message = new MimeMessage(session) {
			@Override
			protected void updateMessageID() throws MessagingException {
				if (valueNullOrEmpty(email.getId())) {
					super.updateMessageID();
				} else {
					setHeader("Message-ID", email.getId());
				}
			}
			
			@Override
			public String toString() {
				try {
					return "MimeMessage<id:" + super.getMessageID() + ", subject:" + super.getSubject() + ">";
				} catch (MessagingException e) {
					throw new AssertionError("should not reach here");
				}
			}
		};
		
		// set basic email properties
		MimeMessageHelper.setSubject(email, message);
		MimeMessageHelper.setFrom(email, message);
		MimeMessageHelper.setReplyTo(email, message);
		MimeMessageHelper.setRecipients(email, message);
		
		populateMimeMessageMultipartStructure(message, email);
		
		MimeMessageHelper.setHeaders(email, message);
		message.setSentDate(ofNullable(email.getSentDate()).orElse(new Date()));

		/*
			The following order is important:
			1. S/MIME signing
			2. S/MIME encryption
			3. DKIM signing
		 */
		if (ModuleLoader.smimeModuleAvailable()) {
			message = ModuleLoader.loadSmimeModule().signAndOrEncryptEmail(session, message, email);
		}

		if (!valueNullOrEmpty(email.getDkimSigningDomain())) {
			message = ModuleLoader.loadDKIMModule().signMessageWithDKIM(message, email);
		}

		if (email.getBounceToRecipient() != null) {
			// display name not applicable: https://tools.ietf.org/html/rfc5321#section-4.1.2
			message = new ImmutableDelegatingSMTPMessage(message, email.getBounceToRecipient().getAddress());
		}

		return message;
	}

	abstract void populateMimeMessageMultipartStructure(@NotNull MimeMessage  message, @NotNull Email email) throws MessagingException;
	
	
	static boolean emailContainsMixedContent(@NotNull Email email) {
		return !email.getAttachments().isEmpty() || email.getEmailToForward() != null;
	}
	
	static boolean emailContainsRelatedContent(@NotNull Email email) {
		return !email.getEmbeddedImages().isEmpty();
	}
	
	static boolean emailContainsAlternativeContent(@NotNull Email email) {
		return (email.getPlainText() != null ? 1 : 0) +
				(email.getHTMLText() != null ? 1 : 0) +
				(email.getCalendarText() != null ? 1 : 0) > 1;
	}
}