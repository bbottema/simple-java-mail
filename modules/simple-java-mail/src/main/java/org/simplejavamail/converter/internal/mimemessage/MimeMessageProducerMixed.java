package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.EmailWithDefaultsAndOverridesApplied;

class MimeMessageProducerMixed extends SpecializedMimeMessageProducer {
	@Override
	boolean compatibleWithEmail(@NotNull EmailWithDefaultsAndOverridesApplied email) {
		return emailContainsMixedContent(email) && !emailContainsRelatedContent(email) && !emailContainsAlternativeContent(email);
	}
	
	@Override
	void populateMimeMessageMultipartStructure(MimeMessage message, EmailWithDefaultsAndOverridesApplied email) throws MessagingException {
		MimeMultipart multipartRootMixed = new MimeMultipart("mixed");
		MimeMessageHelper.setTexts(email, multipartRootMixed);
		MimeMessageHelper.configureForwarding(email, multipartRootMixed);
		MimeMessageHelper.setAttachments(email, multipartRootMixed);
		message.setContent(multipartRootMixed);
	}
}