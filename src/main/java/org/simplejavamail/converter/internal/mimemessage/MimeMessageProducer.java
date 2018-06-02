package org.simplejavamail.converter.internal.mimemessage;

import org.simplejavamail.email.Email;

import javax.annotation.Nonnull;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.util.Date;

import static org.simplejavamail.converter.internal.mimemessage.MimeMessageHelper.signMessageWithDKIM;
import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

/**
 * Helper class that produces and populates a mime messages. Deals with javax.mail RFC MimeMessage stuff, as well as DKIM signing.
 * <p>
 * Some more <a href="https://blogs.technet.microsoft.com/exchange/2011/04/21/mixed-ing-it-up-multipartmixed-messages-and-you/">helpful reading
 * material</a>.
 */
abstract class MimeMessageProducer {
	
	/**
	 * @return Whether this mimemessage producer exactly matches the needs of the given email.
	 */
	abstract boolean compatibleWithEmail(@Nonnull Email email);
	
	/**
	 * Performs a standard population and then delegates multipart specifics to the subclass.
	 */
	final MimeMessage populateMimeMessage(@Nonnull final Email email, @Nonnull Session session)
			throws MessagingException, UnsupportedEncodingException {
		checkArgumentNotEmpty(email, "email is missing");
		checkArgumentNotEmpty(session, "session is needed, it cannot be attached later");
		
		final MimeMessage message = new MimeMessage(session) {
			@Override
			protected void updateMessageID() throws MessagingException {
				if (valueNullOrEmpty(email.getId())) {
					super.updateMessageID();
				} else {
					setHeader("Message-ID", email.getId());
				}
			}
		};
		
		// set basic email properties
		MimeMessageHelper.setSubject(email, message);
		MimeMessageHelper.setFrom(email, message);
		MimeMessageHelper.setReplyTo(email, message);
		MimeMessageHelper.setRecipients(email, message);
		
		populateMimeMessageMultipartStructure(message, email);
		
		MimeMessageHelper.setHeaders(email, message);
		message.setSentDate(new Date());

		if (!valueNullOrEmpty(email.getDkimSigningDomain())) {
			return signMessageWithDKIM(message, email);
		}
		
		return message;
	}
	
	abstract void populateMimeMessageMultipartStructure(@Nonnull MimeMessage  message, @Nonnull Email email) throws MessagingException;
	
	
	static boolean emailContainsMixedContent(@Nonnull Email email) {
		return !email.getAttachments().isEmpty() || email.getEmailToForward() != null;
	}
	
	static boolean emailContainsRelatedContent(@Nonnull Email email) {
		return !email.getEmbeddedImages().isEmpty();
	}
	
	static boolean emailContainsAlternativeContent(@Nonnull Email email) {
		return (email.getPlainText() != null ? 1 : 0) +
				(email.getHTMLText() != null ? 1 : 0) +
				(email.getCalendarText() != null ? 1 : 0) > 1;
	}
}