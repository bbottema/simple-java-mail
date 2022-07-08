package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.UnsupportedEncodingException;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;

class MimeMessageProducerMixed extends MimeMessageProducer {
	@Override
	boolean compatibleWithEmail(@NotNull Email email) {
		return emailContainsMixedContent(email) && !emailContainsRelatedContent(email) && !emailContainsAlternativeContent(email);
	}
	
	@Override
	void populateMimeMessageMultipartStructure(@NotNull MimeMessage message, @NotNull Email email)
			throws MessagingException, UnsupportedEncodingException {
		MimeMultipart multipartRootMixed = new MimeMultipart("mixed");
		MimeMessageHelper.setTexts(email, multipartRootMixed, HEADERS_TO_POPULATE_TO_CHILDREN);
		MimeMessageHelper.configureForwarding(email, multipartRootMixed);
		MimeMessageHelper.setAttachments(email, multipartRootMixed);
		message.setContent(multipartRootMixed);
	}
}