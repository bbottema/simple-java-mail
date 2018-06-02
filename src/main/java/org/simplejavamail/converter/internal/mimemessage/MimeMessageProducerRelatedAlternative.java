package org.simplejavamail.converter.internal.mimemessage;

import org.simplejavamail.email.Email;

import javax.annotation.Nonnull;
import javax.mail.MessagingException;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

class MimeMessageProducerRelatedAlternative extends MimeMessageProducer {
	@Override
	boolean compatibleWithEmail(@Nonnull Email email) {
		return !emailContainsMixedContent(email) && emailContainsRelatedContent(email) && emailContainsAlternativeContent(email);
	}
	
	@Override
	public void populateMimeMessageMultipartStructure(@Nonnull MimeMessage message, @Nonnull Email email) throws MessagingException {
		MultipartStructureWrapper multipartStructureWrapper = new MultipartStructureWrapper();
		
		MimeMessageHelper.setTexts(email, multipartStructureWrapper.multipartAlternativeMessages);
		MimeMessageHelper.setEmbeddedImages(email, multipartStructureWrapper.multipartRootRelated);
		
		message.setContent(multipartStructureWrapper.multipartRootRelated);
	}
	
	/**
	 * refer to {@link MimeMessageProducerMixedRelatedAlternative}.
	 */
	private static class MultipartStructureWrapper {
		
		private final MimeMultipart multipartRootRelated;
		private final MimeMultipart multipartAlternativeMessages;
		
		private MultipartStructureWrapper() {
			multipartRootRelated = new MimeMultipart("related");
			final MimeBodyPart contentRelated = new MimeBodyPart();
			multipartAlternativeMessages = new MimeMultipart("alternative");
			final MimeBodyPart contentAlternativeMessages = new MimeBodyPart();
			try {
				// construct mail structure
				contentRelated.setContent(multipartRootRelated);
				multipartRootRelated.addBodyPart(contentAlternativeMessages);
				contentAlternativeMessages.setContent(multipartAlternativeMessages);
			} catch (final MessagingException e) {
				throw new MimeMessageParseException(e.getMessage(), e);
			}
		}
	}
}