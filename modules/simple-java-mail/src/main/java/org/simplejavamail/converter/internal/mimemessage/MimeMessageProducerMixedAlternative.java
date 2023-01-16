package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.EmailGovernance;

class MimeMessageProducerMixedAlternative extends SpecializedMimeMessageProducer {
	@Override
	boolean compatibleWithEmail(@NotNull Email email) {
		return emailContainsMixedContent(email) && !emailContainsRelatedContent(email) && emailContainsAlternativeContent(email);
	}
	
	@SuppressWarnings("Duplicates")
	@Override
	void populateMimeMessageMultipartStructure(MimeMessage message, Email email, EmailGovernance emailGovernance) throws MessagingException {
		MultipartStructureWrapper multipartStructureWrapper = new MultipartStructureWrapper();
		
		MimeMessageHelper.setTexts(email, emailGovernance, multipartStructureWrapper.multipartAlternativeMessages);
		MimeMessageHelper.configureForwarding(email, emailGovernance, multipartStructureWrapper.multipartRootMixed);
		MimeMessageHelper.setAttachments(email, emailGovernance, multipartStructureWrapper.multipartRootMixed);
		
		message.setContent(multipartStructureWrapper.multipartRootMixed);
	}
	
	/**
	 * refer to {@link MimeMessageProducerMixedRelatedAlternative}.
	 */
	private static class MultipartStructureWrapper {
		
		private final MimeMultipart multipartRootMixed;
		private final MimeMultipart multipartAlternativeMessages;
		
		@SuppressWarnings("Duplicates")
		private MultipartStructureWrapper() {
			multipartRootMixed = new MimeMultipart("mixed");
			final MimeBodyPart contentAlternativeMessages = new MimeBodyPart();
			multipartAlternativeMessages = new MimeMultipart("alternative");
			try {
				// construct mail structure
				multipartRootMixed.addBodyPart(contentAlternativeMessages);
				contentAlternativeMessages.setContent(multipartAlternativeMessages);
			} catch (final MessagingException e) {
				throw new MimeMessageProduceException(e.getMessage(), e);
			}
		}
	}
}