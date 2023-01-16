package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.EmailGovernance;

/**
 * Produces a MimeMessage with {@link MimeMultipart} structure as follows:<br>
 * <pre>
 * - mixed (root)
 * 	- related
 * 		- alternative
 * 			- mail tekst
 * 			- mail html tekst
 * 			- iCalendar tekst
 * 		- embedded images
 * 	- forwarded message
 * 	- attachments
 * </pre>
 * <p>
 * Some more <a href="https://blogs.technet.microsoft.com/exchange/2011/04/21/mixed-ing-it-up-multipartmixed-messages-and-you/">helpful reading
 * material</a>.
 */
class MimeMessageProducerMixedRelatedAlternative extends SpecializedMimeMessageProducer {
	
	@Override
	public boolean compatibleWithEmail(@NotNull Email email) {
		return emailContainsMixedContent(email) && emailContainsRelatedContent(email) && emailContainsAlternativeContent(email);
	}
	
	@Override
	public void populateMimeMessageMultipartStructure(MimeMessage message, Email email, EmailGovernance emailGovernance) throws MessagingException {
		MultipartStructureWrapper multipartStructureWrapper = new MultipartStructureWrapper();
		
		MimeMessageHelper.setTexts(email, emailGovernance, multipartStructureWrapper.multipartAlternativeMessages);
		MimeMessageHelper.configureForwarding(email, emailGovernance, multipartStructureWrapper.multipartRootMixed);
		MimeMessageHelper.setEmbeddedImages(email, emailGovernance, multipartStructureWrapper.multipartRelated);
		MimeMessageHelper.setAttachments(email, emailGovernance, multipartStructureWrapper.multipartRootMixed);
		
		message.setContent(multipartStructureWrapper.multipartRootMixed);
	}
	
	/**
	 * refer to {@link MimeMessageProducerMixedRelatedAlternative}.
	 */
	private static class MultipartStructureWrapper {
		
		private final MimeMultipart multipartRootMixed;
		private final MimeMultipart multipartRelated;
		private final MimeMultipart multipartAlternativeMessages;
		
		private MultipartStructureWrapper() {
			multipartRootMixed = new MimeMultipart("mixed");
			final MimeBodyPart contentRelated = new MimeBodyPart();
			multipartRelated = new MimeMultipart("related");
			final MimeBodyPart contentAlternativeMessages = new MimeBodyPart();
			multipartAlternativeMessages = new MimeMultipart("alternative");
			try {
				// construct mail structure
				multipartRootMixed.addBodyPart(contentRelated);
				contentRelated.setContent(multipartRelated);
				multipartRelated.addBodyPart(contentAlternativeMessages);
				contentAlternativeMessages.setContent(multipartAlternativeMessages);
			} catch (final MessagingException e) {
				throw new MimeMessageProduceException(e.getMessage(), e);
			}
		}
	}
}