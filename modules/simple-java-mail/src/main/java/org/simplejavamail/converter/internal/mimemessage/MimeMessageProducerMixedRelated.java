package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.EmailGovernance;

class MimeMessageProducerMixedRelated extends SpecializedMimeMessageProducer {
	@Override
	boolean compatibleWithEmail(@NotNull Email email) {
		return emailContainsMixedContent(email) && emailContainsRelatedContent(email) && !emailContainsAlternativeContent(email);
	}
	
	@SuppressWarnings("Duplicates")
	@Override
	public void populateMimeMessageMultipartStructure(MimeMessage message, Email email, EmailGovernance emailGovernance) throws MessagingException {
		MultipartStructureWrapper multipartStructureWrapper = new MultipartStructureWrapper();
		
		MimeMessageHelper.setTexts(email, emailGovernance, multipartStructureWrapper.multipartRelated);
		MimeMessageHelper.configureForwarding(email, emailGovernance, multipartStructureWrapper.multipartRootMixed);
		MimeMessageHelper.setEmbeddedImages(email, emailGovernance, multipartStructureWrapper.multipartRelated);
		MimeMessageHelper.setAttachments(email, emailGovernance, multipartStructureWrapper.multipartRootMixed);
		
		message.setContent(multipartStructureWrapper.multipartRootMixed);
	}
	
	/**
	 * refer to {@link MimeMessageProducerMixedRelatedAlternative}.
	 */
	private static class MultipartStructureWrapper {
		
		private final MimeMultipart multipartRootMixed;
		private final MimeMultipart multipartRelated;
		
		private MultipartStructureWrapper() {
			multipartRootMixed = new MimeMultipart("mixed");
			final MimeBodyPart contentRelated = new MimeBodyPart();
			multipartRelated = new MimeMultipart("related");
			try {
				// construct mail structure
				multipartRootMixed.addBodyPart(contentRelated);
				contentRelated.setContent(multipartRelated);
			} catch (final MessagingException e) {
				throw new MimeMessageProduceException(e.getMessage(), e);
			}
		}
	}
}