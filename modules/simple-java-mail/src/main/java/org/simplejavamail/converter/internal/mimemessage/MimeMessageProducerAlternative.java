package org.simplejavamail.converter.internal.mimemessage;

import org.simplejavamail.api.email.Email;

import org.jetbrains.annotations.NotNull;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

class MimeMessageProducerAlternative extends MimeMessageProducer {
	@Override
	boolean compatibleWithEmail(@NotNull Email email) {
		return !emailContainsMixedContent(email) && !emailContainsRelatedContent(email) && emailContainsAlternativeContent(email);
	}
	
	@Override
	void populateMimeMessageMultipartStructure(@NotNull MimeMessage message, @NotNull Email email) throws MessagingException {
		MimeMultipart multipartRootAlternative = new MimeMultipart("alternative");
		MimeMessageHelper.setTexts(email, multipartRootAlternative);
		message.setContent(multipartRootAlternative);
	}
}