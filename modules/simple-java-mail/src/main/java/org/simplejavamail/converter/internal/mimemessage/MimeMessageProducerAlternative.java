package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.EmailGovernance;

class MimeMessageProducerAlternative extends SpecializedMimeMessageProducer {
	@Override
	boolean compatibleWithEmail(@NotNull Email email) {
		return !emailContainsMixedContent(email) && !emailContainsRelatedContent(email) && emailContainsAlternativeContent(email);
	}
	
	@Override
	void populateMimeMessageMultipartStructure(MimeMessage message, Email email, EmailGovernance emailGovernance) throws MessagingException {
		MimeMultipart multipartRootAlternative = new MimeMultipart("alternative");
		MimeMessageHelper.setTexts(email, emailGovernance, multipartRootAlternative);
		message.setContent(multipartRootAlternative);
	}
}