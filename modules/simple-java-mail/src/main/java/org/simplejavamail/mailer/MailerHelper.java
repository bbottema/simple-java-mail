package org.simplejavamail.mailer;

import com.sanctionco.jmail.EmailValidator;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.internal.modules.ModuleLoader;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Map;

import static java.lang.String.format;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * If you don't need to actually connect to a server and/or send anything, then this class still provides you with some of the functionality
 * otherwise triggered only by a {@link org.simplejavamail.api.mailer.Mailer}.
 */
public class MailerHelper {

	private static final Logger LOGGER = getLogger(MailerHelper.class);

	@SuppressWarnings({ "SameReturnValue" })
	public static boolean validate(@NotNull final Email email, @Nullable final EmailValidator emailValidator)
			throws MailException {
		LOGGER.debug("validating email...");

		// check for mandatory values
		if (email.getRecipients().size() == 0) {
			throw new MailValidationException(MailValidationException.MISSING_RECIPIENT);
		} else if (email.getFromRecipient() == null) {
			throw new MailValidationException(MailValidationException.MISSING_SENDER);
		} else if (email.isUseDispositionNotificationTo() && email.getDispositionNotificationTo() == null) {
			throw new MailValidationException(MailValidationException.MISSING_DISPOSITIONNOTIFICATIONTO);
		} else if (email.isUseReturnReceiptTo() && email.getReturnReceiptTo() == null) {
			throw new MailValidationException(MailValidationException.MISSING_RETURNRECEIPTTO);
		} else
		if (emailValidator != null) {
			if (!emailValidator.isValid(email.getFromRecipient().getAddress())) {
				throw new MailValidationException(format(MailValidationException.INVALID_SENDER, email));
			}
			for (final Recipient recipient : email.getRecipients()) {
				if (!emailValidator.isValid(recipient.getAddress())) {
					throw new MailValidationException(format(MailValidationException.INVALID_RECIPIENT, email));
				}
			}
			if (email.getReplyToRecipient() != null && !emailValidator.isValid(email.getReplyToRecipient().getAddress())) {
				throw new MailValidationException(format(MailValidationException.INVALID_REPLYTO, email));
			}
			if (email.getBounceToRecipient() != null && !emailValidator.isValid(email.getBounceToRecipient().getAddress())) {
				throw new MailValidationException(format(MailValidationException.INVALID_BOUNCETO, email));
			}
			if (email.isUseDispositionNotificationTo()) {
				if (!emailValidator.isValid(checkNonEmptyArgument(email.getDispositionNotificationTo(), "dispositionNotificationTo").getAddress())) {
					throw new MailValidationException(format(MailValidationException.INVALID_DISPOSITIONNOTIFICATIONTO, email));
				}
			}
			if (email.isUseReturnReceiptTo()) {
				if (!emailValidator.isValid(checkNonEmptyArgument(email.getReturnReceiptTo(), "returnReceiptTo").getAddress())) {
					throw new MailValidationException(format(MailValidationException.INVALID_RETURNRECEIPTTO, email));
				}
			}
		}

		// check for illegal values
		scanForInjectionAttack(email.getSubject(), "email.subject");
		for (final Map.Entry<String, Collection<String>> headerEntry : email.getHeaders().entrySet()) {
			for (final String headerValue : headerEntry.getValue()) {
				scanForInjectionAttack(headerEntry.getKey(), "email.header.mapEntryKey");
				if (headerEntry.getKey().equals("References")) {
					scanForInjectionAttack(MimeUtility.unfold(headerValue), "email.header.References");
				} else {
					scanForInjectionAttack(headerValue, "email.header." + headerEntry.getKey());
				}
			}
		}
		for (final AttachmentResource attachment : email.getAttachments()) {
			scanForInjectionAttack(attachment.getName(), "email.attachment.name");
			scanForInjectionAttack(attachment.getDataSource().getName(), "email.attachment.datasource.name");
		}
		for (final AttachmentResource embeddedImage : email.getEmbeddedImages()) {
			scanForInjectionAttack(embeddedImage.getName(), "email.embeddedImage.name");
			scanForInjectionAttack(embeddedImage.getDataSource().getName(), "email.embeddedImage.datasource.name");
		}
		scanForInjectionAttack(email.getFromRecipient().getName(), "email.fromRecipient.name");
		scanForInjectionAttack(email.getFromRecipient().getAddress(), "email.fromRecipient.address");
		if (!valueNullOrEmpty(email.getReplyToRecipient())) {
			scanForInjectionAttack(email.getReplyToRecipient().getName(), "email.replyToRecipient.name");
			scanForInjectionAttack(email.getReplyToRecipient().getAddress(), "email.replyToRecipient.address");
		}
		if (!valueNullOrEmpty(email.getBounceToRecipient())) {
			scanForInjectionAttack(email.getBounceToRecipient().getName(), "email.bounceToRecipient.name");
			scanForInjectionAttack(email.getBounceToRecipient().getAddress(), "email.bounceToRecipient.address");
		}
		for (final Recipient recipient : email.getRecipients()) {
			scanForInjectionAttack(recipient.getName(), "email.recipient.name");
			scanForInjectionAttack(recipient.getAddress(), "email.recipient.address");
		}

		LOGGER.debug("...no problems found");

		return true;
	}

	/**
	 * @param value      Value checked for suspicious newline characters "\n", "\r" and "%0A" (as acknowledged by SMTP servers).
	 * @param valueLabel The name of the field being checked, used for reporting exceptions.
	 *
	 * @see <a href="https://web.archive.org/web/20160331233647/http://www.cakesolutions.net/teamblogs/2008/05/08/email-header-injection-security">https://web.archive.org/web/20160331233647/http://www.cakesolutions.net/teamblogs/2008/05/08/email-header-injection-security</a>
	 * @see <a href="https://security.stackexchange.com/a/54100/110048">https://security.stackexchange.com/a/54100/110048</a>
	 * @see <a href="https://www.owasp.org/index.php/Testing_for_IMAP/SMTP_Injection_(OTG-INPVAL-011)">https://www.owasp.org/index.php/Testing_for_IMAP/SMTP_Injection_(OTG-INPVAL-011)</a>
	 * @see <a href="http://cwe.mitre.org/data/definitions/93.html">http://cwe.mitre.org/data/definitions/93.html</a>
	 */
	private static void scanForInjectionAttack(final @Nullable String value, final String valueLabel) {
		if (value != null && (value.contains("\n") || value.contains("\r") || value.contains("%0A"))) {
			throw new MailValidationException(format(MailValidationException.INJECTION_SUSPECTED, valueLabel, value));
		}
	}

	/**
	 * @see org.simplejavamail.internal.modules.DKIMModule#signMessageWithDKIM(MimeMessage, Email)
	 */
	@SuppressWarnings("unused")
	public static MimeMessage signMessageWithDKIM(@NotNull final MimeMessage messageToSign, @NotNull final Email emailContainingSigningDetails) {
		return ModuleLoader.loadDKIMModule().signMessageWithDKIM(messageToSign, emailContainingSigningDetails);
	}

	/**
	 * Depending on the Email configuration, signs and then encrypts message (both steps optional), using the S/MIME module.
	 *
	 * @see org.simplejavamail.internal.modules.SMIMEModule#signAndOrEncryptEmail(Session, MimeMessage, Email, Pkcs12Config)
	 */
	@SuppressWarnings("unused")
	public static MimeMessage signAndOrEncryptMessageWithSmime(@NotNull final Session session, @NotNull final MimeMessage messageToProtect, @NotNull final Email emailContainingSmimeDetails, @Nullable final Pkcs12Config defaultSmimeSigningStore) {
		return ModuleLoader.loadSmimeModule()
				.signAndOrEncryptEmail(session, messageToProtect, emailContainingSmimeDetails, defaultSmimeSigningStore);
	}
}
