package org.simplejavamail.converter.internal.mimemessage;

import org.simplejavamail.api.email.Email;

import javax.annotation.Nonnull;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

class MimeMessageProducerMixed extends MimeMessageProducer {
	@Override
	boolean compatibleWithEmail(@Nonnull Email email) {
		return emailContainsMixedContent(email) && !emailContainsRelatedContent(email) && !emailContainsAlternativeContent(email);
	}
	
	@Override
	void populateMimeMessageMultipartStructure(@Nonnull MimeMessage message, @Nonnull Email email) throws MessagingException {
		MimeMultipart multipartRootMixed = new MimeMultipart("mixed");
		MimeMessageHelper.setTexts(email, multipartRootMixed);
		MimeMessageHelper.configureForwarding(email, multipartRootMixed);
		MimeMessageHelper.setAttachments(email, multipartRootMixed);
		message.setContent(multipartRootMixed);
	}
}