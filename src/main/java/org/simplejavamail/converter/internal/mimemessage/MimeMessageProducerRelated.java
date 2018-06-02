package org.simplejavamail.converter.internal.mimemessage;

import org.simplejavamail.email.Email;

import javax.annotation.Nonnull;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

public class MimeMessageProducerRelated extends MimeMessageProducer {
	@Override
	boolean compatibleWithEmail(@Nonnull Email email) {
		return !emailContainsMixedContent(email) && emailContainsRelatedContent(email) && !emailContainsAlternativeContent(email);
	}
	
	@Override
	public void populateMimeMessageMultipartStructure(@Nonnull MimeMessage message, @Nonnull Email email) throws MessagingException {
		MimeMultipart multipartRootRelated = new MimeMultipart("related");
		MimeMessageHelper.setTexts(email, multipartRootRelated);
		MimeMessageHelper.setEmbeddedImages(email, multipartRootRelated);
		message.setContent(multipartRootRelated);
	}
}