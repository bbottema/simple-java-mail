package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.EmailGovernance;

class MimeMessageProducerRelatedAlternative extends SpecializedMimeMessageProducer {
	@Override
	boolean compatibleWithEmail(@NotNull Email email) {
		return !emailContainsMixedContent(email) && emailContainsRelatedContent(email) && emailContainsAlternativeContent(email);
	}
	
	@Override
	public void populateMimeMessageMultipartStructure(MimeMessage message, Email email, EmailGovernance emailGovernance) throws MessagingException {
		MultipartStructureWrapper multipartStructureWrapper = new MultipartStructureWrapper();
		
		MimeMessageHelper.setTexts(email, emailGovernance, multipartStructureWrapper.multipartAlternativeMessages);
		MimeMessageHelper.setEmbeddedImages(email, emailGovernance, multipartStructureWrapper.multipartRootRelated);
		
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
				throw new MimeMessageProduceException(e.getMessage(), e);
			}
		}
	}
}