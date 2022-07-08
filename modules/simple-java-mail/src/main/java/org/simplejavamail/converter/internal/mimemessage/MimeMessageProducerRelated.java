package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.UnsupportedEncodingException;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;

class MimeMessageProducerRelated extends MimeMessageProducer {
	@Override
	boolean compatibleWithEmail(@NotNull Email email) {
		return !emailContainsMixedContent(email) && emailContainsRelatedContent(email) && !emailContainsAlternativeContent(email);
	}
	
	@Override
	public void populateMimeMessageMultipartStructure(@NotNull MimeMessage message, @NotNull Email email)
			throws MessagingException, UnsupportedEncodingException {
		MimeMultipart multipartRootRelated = new MimeMultipart("related");
		MimeMessageHelper.setTexts(email, multipartRootRelated, HEADERS_TO_POPULATE_TO_CHILDREN);
		MimeMessageHelper.setEmbeddedImages(email, multipartRootRelated);
		message.setContent(multipartRootRelated);
	}
}