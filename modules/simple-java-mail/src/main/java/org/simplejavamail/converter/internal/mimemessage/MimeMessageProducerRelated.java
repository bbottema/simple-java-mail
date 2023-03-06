package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.EmailWithDefaultsAndOverridesApplied;

class MimeMessageProducerRelated extends SpecializedMimeMessageProducer {
	@Override
	boolean compatibleWithEmail(@NotNull EmailWithDefaultsAndOverridesApplied email) {
		return !emailContainsMixedContent(email) && emailContainsRelatedContent(email) && !emailContainsAlternativeContent(email);
	}
	
	@Override
	public void populateMimeMessageMultipartStructure(MimeMessage message, EmailWithDefaultsAndOverridesApplied email) throws MessagingException {
		MimeMultipart multipartRootRelated = new MimeMultipart("related");
		MimeMessageHelper.setTexts(email, multipartRootRelated);
		MimeMessageHelper.setEmbeddedImages(email, multipartRootRelated);
		message.setContent(multipartRootRelated);
	}
}