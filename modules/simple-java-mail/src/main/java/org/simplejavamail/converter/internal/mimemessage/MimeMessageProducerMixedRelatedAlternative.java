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
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

/**
 * Produces a MimeMessage with {@link MimeMultipart} structure as follows:<br>
 * <pre>
 * - mixed (root)
 * 	- related
 * 		- alternative
 * 			- mail tekst
 * 			- mail html tekst
 * 			- iCalendar tekst
 * 		- embedded images
 * 	- forwarded message
 * 	- attachments
 * </pre>
 * <p>
 * Some more <a href="https://blogs.technet.microsoft.com/exchange/2011/04/21/mixed-ing-it-up-multipartmixed-messages-and-you/">helpful reading
 * material</a>.
 */
class MimeMessageProducerMixedRelatedAlternative extends MimeMessageProducer {
	
	@Override
	public boolean compatibleWithEmail(@NotNull Email email) {
		return emailContainsMixedContent(email) && emailContainsRelatedContent(email) && emailContainsAlternativeContent(email);
	}
	
	@Override
	public void populateMimeMessageMultipartStructure(@NotNull MimeMessage message, @NotNull Email email) throws MessagingException {
		MultipartStructureWrapper multipartStructureWrapper = new MultipartStructureWrapper();
		
		MimeMessageHelper.setTexts(email, multipartStructureWrapper.multipartAlternativeMessages);
		MimeMessageHelper.configureForwarding(email, multipartStructureWrapper.multipartRootMixed);
		MimeMessageHelper.setEmbeddedImages(email, multipartStructureWrapper.multipartRelated);
		MimeMessageHelper.setAttachments(email, multipartStructureWrapper.multipartRootMixed);
		
		message.setContent(multipartStructureWrapper.multipartRootMixed);
	}
	
	/**
	 * refer to {@link MimeMessageProducerMixedRelatedAlternative}.
	 */
	private static class MultipartStructureWrapper {
		
		private final MimeMultipart multipartRootMixed;
		private final MimeMultipart multipartRelated;
		private final MimeMultipart multipartAlternativeMessages;
		
		private MultipartStructureWrapper() {
			multipartRootMixed = new MimeMultipart("mixed");
			final MimeBodyPart contentRelated = new MimeBodyPart();
			multipartRelated = new MimeMultipart("related");
			final MimeBodyPart contentAlternativeMessages = new MimeBodyPart();
			multipartAlternativeMessages = new MimeMultipart("alternative");
			try {
				// construct mail structure
				multipartRootMixed.addBodyPart(contentRelated);
				contentRelated.setContent(multipartRelated);
				multipartRelated.addBodyPart(contentAlternativeMessages);
				contentAlternativeMessages.setContent(multipartAlternativeMessages);
			} catch (final MessagingException e) {
				throw new MimeMessageProduceException(e.getMessage(), e);
			}
		}
	}
}