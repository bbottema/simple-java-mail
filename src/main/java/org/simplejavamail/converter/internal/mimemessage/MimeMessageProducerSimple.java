package org.simplejavamail.converter.internal.mimemessage;

import org.simplejavamail.email.Email;

import javax.annotation.Nonnull;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

/**
 * Produces a MimeMessage with {@link MimeMultipart} structure as follows:<br>
 * <pre>
 * - plain text OR HTML text OR iCalendar tekst
 * </pre>
 * <p>
 * Some more <a href="https://blogs.technet.microsoft.com/exchange/2011/04/21/mixed-ing-it-up-multipartmixed-messages-and-you/">helpful reading
 * material</a>.
 */
class MimeMessageProducerSimple extends MimeMessageProducer {
	
	@Override
	public boolean compatibleWithEmail(@Nonnull Email email) {
		return !emailContainsMixedContent(email) && !emailContainsRelatedContent(email) && !emailContainsAlternativeContent(email);
	}
	
	@Override
	public void populateMimeMessageMultipartStructure(@Nonnull MimeMessage message, @Nonnull Email email) throws MessagingException {
		MimeMessageHelper.setTexts(email, message);
	}
}