package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.EmailWithDefaultsAndOverridesApplied;

/**
 * Produces a MimeMessage with {@link MimeMultipart} structure as follows:<br>
 * <pre>
 * - plain text OR HTML text OR iCalendar tekst
 * </pre>
 * <p>
 * Some more <a href="https://blogs.technet.microsoft.com/exchange/2011/04/21/mixed-ing-it-up-multipartmixed-messages-and-you/">helpful reading
 * material</a>.
 */
class MimeMessageProducerSimple extends SpecializedMimeMessageProducer {
	
	@Override
	public boolean compatibleWithEmail(@NotNull EmailWithDefaultsAndOverridesApplied email) {
		return !emailContainsMixedContent(email) && !emailContainsRelatedContent(email) && !emailContainsAlternativeContent(email);
	}
	
	@Override
	public void populateMimeMessageMultipartStructure(MimeMessage message, EmailWithDefaultsAndOverridesApplied email) throws MessagingException {
		MimeMessageHelper.setTexts(email, message);
	}
}