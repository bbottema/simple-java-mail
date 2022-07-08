package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.UnsupportedEncodingException;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;

class MimeMessageProducerAlternative extends MimeMessageProducer {
	@Override
	boolean compatibleWithEmail(@NotNull Email email) {
		return !emailContainsMixedContent(email) && !emailContainsRelatedContent(email) && emailContainsAlternativeContent(email);
	}
	
	@Override
	void populateMimeMessageMultipartStructure(@NotNull MimeMessage message, @NotNull Email email)
			throws MessagingException, UnsupportedEncodingException {
		MimeMultipart multipartRootAlternative = new MimeMultipart("alternative");
		MimeMessageHelper.setTexts(email, multipartRootAlternative, HEADERS_TO_POPULATE_TO_CHILDREN);
		message.setContent(multipartRootAlternative);
	}
}