package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.internal.config.EmailProperty;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.io.UnsupportedEncodingException;
import java.security.cert.X509Certificate;
import java.util.Date;

import static java.util.Optional.ofNullable;
import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

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

		Pkcs12Config pkcs12Config = emailGovernance.resolveEmailProperty(email, EmailProperty.SMIME_SIGNING_CONFIG);
		if (pkcs12Config != null) {
			message = ModuleLoader.loadSmimeModule().signMessageWithSmime(session, message, pkcs12Config);
		}

		X509Certificate x509Certificate = emailGovernance.resolveEmailProperty(email, EmailProperty.SMIME_ENCRYPTION_CONFIG);
		if (x509Certificate != null) {
			message = ModuleLoader.loadSmimeModule().encryptMessageWithSmime(session, message, x509Certificate);
		}

		DkimConfig dkimConfig = emailGovernance.resolveEmailProperty(email, EmailProperty.DKIM_SIGNING_CONFIG);
		if (dkimConfig != null) {
			message = ModuleLoader.loadDKIMModule().signMessageWithDKIM(message, dkimConfig, checkNonEmptyArgument(email.getFromRecipient(), "fromRecipient"));
		}

		Recipient bounceToRecipient = emailGovernance.resolveEmailProperty(email, EmailProperty.BOUNCETO_RECIPIENT);
		if (bounceToRecipient != null) {
			// display name not applicable: https://tools.ietf.org/html/rfc5321#section-4.1.2
			message = new ImmutableDelegatingSMTPMessage(message, bounceToRecipient.getAddress());
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