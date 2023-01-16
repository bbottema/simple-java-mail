package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.io.UnsupportedEncodingException;
import java.util.Date;

import static java.util.Optional.ofNullable;
import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;
import static org.simplejavamail.internal.util.MiscUtil.orOther;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

/**
 * Helper class that produces and populates a mime messages. Deals with jakarta.mail RFC MimeMessage stuff, as well as
 * <a href="https://www.simplejavamail.org/#section-dkim">DKIM signing</a> and
 * <a href="https://www.simplejavamail.org/#section-sending-smime">S/MIME signing / encryption</a>.
 * <p>
 * Some more <a href="https://blogs.technet.microsoft.com/exchange/2011/04/21/mixed-ing-it-up-multipartmixed-messages-and-you/">helpful reading
 * material</a>.
 * <p>
 * One goal of these classes is to produce a Mime structure that exactly matches the email's need. Previously, Simple Java Mail
 * used a complex RFC-conforming structure that is compatible with all situations, but it seems
 * <a href="https://github.com/bbottema/simple-java-mail/issues/133">some email clients</a> would still get confused.
 * Due to this, we explicitely define all possible structures so we take the least complex one needed.
 *
 * @see <a href="https://github.com/bbottema/simple-java-mail/issues/144">#144: Simple Java Mail should tailor the MimeMessage structure to specific needs</a>
 */
public abstract class SpecializedMimeMessageProducer {
	
	/**
	 * @return Whether this mimemessage producer exactly matches the needs of the given email.
	 */
	abstract boolean compatibleWithEmail(@NotNull Email email);
	
	final MimeMessage populateMimeMessage(final Email email, final EmailGovernance emailGovernance, @NotNull Session session)
			throws MessagingException, UnsupportedEncodingException {
		checkArgumentNotEmpty(email, "email is missing");
		checkArgumentNotEmpty(session, "session is needed, it cannot be attached later");

		MimeMessage message = new MimeMessage(session) {
			@Override
			protected void updateMessageID() throws MessagingException {
				if (valueNullOrEmpty(email.getId())) {
					super.updateMessageID();
				} else {
					setHeader("Message-ID", email.getId());
				}
			}
			
			@Override
			public String toString() {
				try {
					return "MimeMessage<id:" + super.getMessageID() + ", subject:" + super.getSubject() + ">";
				} catch (MessagingException e) {
					throw new IllegalStateException("should not reach here");
				}
			}
		};
		
		// set basic email properties
		MimeMessageHelper.setSubject(email, emailGovernance, message);
		MimeMessageHelper.setFrom(email, emailGovernance, message);
		MimeMessageHelper.setReplyTo(email, emailGovernance, message);
		MimeMessageHelper.setRecipients(email, emailGovernance, message);
		
		populateMimeMessageMultipartStructure(message, email, emailGovernance);
		
		MimeMessageHelper.setHeaders(email, emailGovernance, message);
		message.setSentDate(ofNullable(email.getSentDate()).orElse(new Date()));

		/*
			The following order is important:
			1. S/MIME signing
			2. S/MIME encryption
			3. DKIM signing
		 */
		if (ModuleLoader.smimeModuleAvailable()) {
			message = ModuleLoader.loadSmimeModule().signAndOrEncryptEmail(session, message, email, emailGovernance.getPkcs12ConfigForSmimeSigning());
		}

		if (!valueNullOrEmpty(email.getDkimSigningDomain())) {
			message = ModuleLoader.loadDKIMModule().signMessageWithDKIM(message, email);
		}

		val bountToRecipient = orOther(email, emailGovernance.getEmailDefaults(), emailGovernance.getEmailOverrides(), Email::getBounceToRecipient);
		if (bountToRecipient != null) {
			// display name not applicable: https://tools.ietf.org/html/rfc5321#section-4.1.2
			message = new ImmutableDelegatingSMTPMessage(message, bountToRecipient.getAddress());
		}

		return message;
	}

	abstract void populateMimeMessageMultipartStructure(MimeMessage  message, Email email, EmailGovernance emailGovernance) throws MessagingException;
	
	
	static boolean emailContainsMixedContent(@NotNull Email email) {
		return !email.getAttachments().isEmpty() || email.getEmailToForward() != null;
	}
	
	static boolean emailContainsRelatedContent(@NotNull Email email) {
		return !email.getEmbeddedImages().isEmpty();
	}
	
	static boolean emailContainsAlternativeContent(@NotNull Email email) {
		return (email.getPlainText() != null ? 1 : 0) +
				(email.getHTMLText() != null ? 1 : 0) +
				(email.getCalendarText() != null ? 1 : 0) > 1;
	}
}