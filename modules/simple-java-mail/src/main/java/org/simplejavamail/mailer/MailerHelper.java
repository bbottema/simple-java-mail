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
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Map;

import static java.lang.Boolean.TRUE;
import static java.lang.String.format;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * If you don't need to actually connect to a server and/or send anything, then this class still provides you with some functionality
 * otherwise triggered only by a {@link org.simplejavamail.api.mailer.Mailer}.
 */
public class MailerHelper {

	private static final Logger LOGGER = getLogger(MailerHelper.class);

	/**
	 * Delegates to all other validations for a full checkup.
	 *
	 * @see #validateCompleteness(Email)
	 * @see #validateAddresses(Email, EmailValidator)
	 * @see #scanForInjectionAttacks(Email)
	 */
	@SuppressWarnings({ "SameReturnValue" })
	public static boolean validate(@NotNull final Email email, @Nullable final EmailValidator emailValidator)
			throws MailException {
		LOGGER.debug("validating email...");

		validateCompleteness(email);
		validateAddresses(email, emailValidator);
		scanForInjectionAttacks(email);

		LOGGER.debug("...no problems found");

		return true;
	}

	/**
	 * Lenient validation only checks for missing fields (which implies incorrect configuration or missing data),
	 * but only warns for invalid address and suspected CRLF injections.
	 *
	 * @see #validateCompleteness(Email)
	 * @see #validateAddresses(Email, EmailValidator)
	 * @see #scanForInjectionAttacks(Email)
	 */
	@SuppressWarnings({ "SameReturnValue" })
	public static boolean validateLenient(@NotNull final Email email, @Nullable final EmailValidator emailValidator)
			throws MailException {
		LOGGER.debug("validating email...");
		MailerHelper.validateCompleteness(email);
		try {
			MailerHelper.validateAddresses(email, emailValidator);
		} catch (MailInvalidAddressException e) {
			LOGGER.warn("encountered (and ignored) invalid address: {}", e.getMessage());
		}
		try {
			MailerHelper.scanForInjectionAttacks(email);
		} catch (MailSuspiciousCRLFValueException e) {
			LOGGER.warn("encountered (and ignored) suspected CRLF injection: {}", e.getMessage());
		}
		LOGGER.debug("...no blocking problems found");

		return true;
	}

	/**
	 * Checks whether:
	 * <ol>
	 *     <li>there are recipients</li>
	 *     <li>if there is a sender</li>
	 *     <li>if there is a disposition notification TO if flag is set to use it</li>
	 *     <li>if there is a return receipt TO if flag is set to use it</li>
	 * </ol>
	 */
	public static void validateCompleteness(final @NotNull Email email) {
		// check for mandatory values
		if (email.getRecipients().size() == 0) {
			throw new MailCompletenessException(MailCompletenessException.MISSING_RECIPIENT);
		} else if (email.getFromRecipient() == null) {
			throw new MailCompletenessException(MailCompletenessException.MISSING_SENDER);
		} else if (TRUE.equals(email.getUseDispositionNotificationTo()) && email.getDispositionNotificationTo() == null) {
			throw new MailCompletenessException(MailCompletenessException.MISSING_DISPOSITIONNOTIFICATIONTO);
		} else if (TRUE.equals(email.getUseReturnReceiptTo()) && email.getReturnReceiptTo() == null) {
			throw new MailCompletenessException(MailCompletenessException.MISSING_RETURNRECEIPTTO);
		}
	}

	/**
	 * If email validator is provided, checks:
	 * <ol>
	 *     <li>from recipient</li>
	 *     <li>all TO/CC/BCC recipients</li>
	 *     <li>reply-to recipient, if provided</li>
	 *     <li>bounce-to recipient, if provided</li>
	 *     <li>disposition-notification-to recipient, if provided</li>
	 *     <li>return-receipt-to recipient, if provided</li>
	 * </ol>
	 */
	public static void validateAddresses(final @NotNull Email email, final @Nullable EmailValidator emailValidator) {
		if (emailValidator != null) {
			if (!emailValidator.isValid(email.getFromRecipient().getAddress())) {
				throw new MailInvalidAddressException(format(MailInvalidAddressException.INVALID_SENDER, email));
			}
			for (final Recipient recipient : email.getRecipients()) {
				if (!emailValidator.isValid(recipient.getAddress())) {
					throw new MailInvalidAddressException(format(MailInvalidAddressException.INVALID_RECIPIENT, email));
				}
			}
			if (email.getReplyToRecipient() != null && !emailValidator.isValid(email.getReplyToRecipient().getAddress())) {
				throw new MailInvalidAddressException(format(MailInvalidAddressException.INVALID_REPLYTO, email));
			}
			if (email.getBounceToRecipient() != null && !emailValidator.isValid(email.getBounceToRecipient().getAddress())) {
				throw new MailInvalidAddressException(format(MailInvalidAddressException.INVALID_BOUNCETO, email));
			}
			if (TRUE.equals(email.getUseDispositionNotificationTo())) {
				if (!emailValidator.isValid(checkNonEmptyArgument(email.getDispositionNotificationTo(), "dispositionNotificationTo").getAddress())) {
					throw new MailInvalidAddressException(format(MailInvalidAddressException.INVALID_DISPOSITIONNOTIFICATIONTO, email));
				}
			}
			if (TRUE.equals(email.getUseReturnReceiptTo())) {
				if (!emailValidator.isValid(checkNonEmptyArgument(email.getReturnReceiptTo(), "returnReceiptTo").getAddress())) {
					throw new MailInvalidAddressException(format(MailInvalidAddressException.INVALID_RETURNRECEIPTTO, email));
				}
			}
		}
	}

	/**
	 * Checks the following headers for suspicious content (newlines and characters):
	 * <ol>
	 *     <li>subject</li>
	 *     <li>every header name and value</li>
	 *     <li>every attachment name, nested datasource name and description</li>
	 *     <li>every embedded image name, nested datasource name and description</li>
	 *     <li>from recipient name and address</li>
	 *     <li>replyTo recipient name and address, if provided</li>
	 *     <li>bounceTo recipient name and address, if provided</li>
	 *     <li>every TO/CC/BCC recipient name and address</li>
	 *     <li>disposition-notification-to recipient name and address, if provided</li>
	 *     <li>return-receipt-to recipient name and address, if provided</li>
	 * </ol>
	 *
	 * @see #scanForInjectionAttack
	 */
	public static void scanForInjectionAttacks(final @NotNull Email email) {
		// check for illegal values
		scanForInjectionAttack(email.getSubject(), "email.subject");
		for (final Map.Entry<String, Collection<String>> headerEntry : email.getHeaders().entrySet()) {
			for (final String headerValue : headerEntry.getValue()) {
				// FIXME is this still needed?
				scanForInjectionAttack(headerEntry.getKey(), "email.header.headerName");
				scanForInjectionAttack(MimeUtility.unfold(headerValue), format("email.header.[%s]", headerEntry.getKey()));
			}
		}
		for (final AttachmentResource attachment : email.getAttachments()) {
			scanForInjectionAttack(attachment.getName(), "email.attachment.name");
			scanForInjectionAttack(attachment.getDataSource().getName(), "email.attachment.datasource.name");
			scanForInjectionAttack(attachment.getDescription(), "email.attachment.description");
		}
		for (final AttachmentResource embeddedImage : email.getEmbeddedImages()) {
			scanForInjectionAttack(embeddedImage.getName(), "email.embeddedImage.name");
			scanForInjectionAttack(embeddedImage.getDataSource().getName(), "email.embeddedImage.datasource.name");
			scanForInjectionAttack(embeddedImage.getDescription(), "email.embeddedImage.description");
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
		if (!valueNullOrEmpty(email.getDispositionNotificationTo())) {
			scanForInjectionAttack(email.getDispositionNotificationTo().getName(), "email.dispositionNotificationTo.name");
			scanForInjectionAttack(email.getDispositionNotificationTo().getAddress(), "email.dispositionNotificationTo.address");
		}
		if (!valueNullOrEmpty(email.getReturnReceiptTo())) {
			scanForInjectionAttack(email.getReturnReceiptTo().getName(), "email.returnReceiptTo.name");
			scanForInjectionAttack(email.getReturnReceiptTo().getAddress(), "email.returnReceiptTo.address");
		}
		for (final Recipient recipient : email.getRecipients()) {
			scanForInjectionAttack(recipient.getName(), "email.recipient.name");
			scanForInjectionAttack(recipient.getAddress(), "email.recipient.address");
		}
	}

	/**
	 * @param value      Value checked for suspicious newline characters "\n", "\r" and the URL-encoded newline "%0A" (as acknowledged by SMTP servers).
	 * @param valueLabel The name of the field being checked, used for reporting exceptions.
	 *
	 * @see <a href="https://web.archive.org/web/20160331233647/http://www.cakesolutions.net/teamblogs/2008/05/08/email-header-injection-security">Email Header Injection security</a>
	 * @see <a href="https://security.stackexchange.com/a/54100/110048">StackExchange - What threats come from CRLF in email generation?</a>
	 * @see <a href="https://archive.ph/NuETu">OWASP - Testing for IMAP SMTP Injection</a>
	 * @see <a href="https://archive.ph/uReuD">CWE-93: Improper Neutralization of CRLF Sequences ('CRLF Injection')</a>
	 */
	public static void scanForInjectionAttack(final @Nullable String value, final String valueLabel) {
		if (value != null && (value.contains("\n") || value.contains("\r") || value.contains("%0A"))) {
			throw new MailSuspiciousCRLFValueException(format(MailSuspiciousCRLFValueException.INJECTION_SUSPECTED, valueLabel, value));
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