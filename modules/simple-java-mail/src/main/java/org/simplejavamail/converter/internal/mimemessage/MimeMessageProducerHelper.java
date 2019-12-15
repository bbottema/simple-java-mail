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

import org.jetbrains.annotations.NotNull;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.List;

/**
 * Finds a compatible {@link MimeMessageProducer} for a given Email and produces a MimeMessage accordingly.
 * <p>
 * This way, a MimeMessage structure will always be as succinct as possible, so that email clients will never get confused to due missing parts (such
 * as no attachments in a "mixed" multipart or no embedded images in a "related" multipart).
 * <p>
 * Also see issue <a href="https://github.com/bbottema/simple-java-mail/issues/144">#144</a>
 */
public final class MimeMessageProducerHelper {
	
	private static final List<MimeMessageProducer> mimeMessageProducers = Arrays.asList(
			new MimeMessageProducerSimple(),
			new MimeMessageProducerAlternative(),
			new MimeMessageProducerRelated(),
			new MimeMessageProducerMixed(),
			new MimeMessageProducerMixedRelated(),
			new MimeMessageProducerMixedAlternative(),
			new MimeMessageProducerRelatedAlternative(),
			new MimeMessageProducerMixedRelatedAlternative()
	);
	
	private MimeMessageProducerHelper() {
	}
	
	public static MimeMessage produceMimeMessage(@NotNull Email email, @NotNull Session session) throws UnsupportedEncodingException, MessagingException {
		for (MimeMessageProducer mimeMessageProducer : mimeMessageProducers) {
			if (mimeMessageProducer.compatibleWithEmail(email)) {
				return mimeMessageProducer.populateMimeMessage(email, session);
			}
		}
		throw new AssertionError("no compatible MimeMessageProducer found for email");
	}
}