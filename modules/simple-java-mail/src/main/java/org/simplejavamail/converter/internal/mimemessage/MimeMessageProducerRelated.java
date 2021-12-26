package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;

class MimeMessageProducerRelated extends MimeMessageProducer {
	@Override
	boolean compatibleWithEmail(@NotNull Email email) {
		return !emailContainsMixedContent(email) && emailContainsRelatedContent(email) && !emailContainsAlternativeContent(email);
	}
	
	@Override
	public void populateMimeMessageMultipartStructure(@NotNull MimeMessage message, @NotNull Email email) throws MessagingException {
		MimeMultipart multipartRootRelated = new MimeMultipart("related");
		MimeMessageHelper.setTexts(email, multipartRootRelated);
		MimeMessageHelper.setEmbeddedImages(email, multipartRootRelated);
		message.setContent(multipartRootRelated);
	}
}