package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.mailer.internal.util.MessageIdFixingMimeMessage;

import java.io.UnsupportedEncodingException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static java.util.Optional.ofNullable;
import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;
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
	
	final MimeMessage populateMimeMessage(@NotNull final Email email, @NotNull Session session)
			throws MessagingException, UnsupportedEncodingException {
		checkArgumentNotEmpty(email, "email is missing");
		checkArgumentNotEmpty(session, "session is needed, it cannot be attached later");

		MimeMessage message = new MessageIdFixingMimeMessage(session, email.getId());
		
		// set basic email properties
		MimeMessageHelper.setSubject(email, message);
		MimeMessageHelper.setFrom(email, message);
		MimeMessageHelper.setReplyTo(email, message);
		MimeMessageHelper.setRecipients(email, message);
		
		populateMimeMessageMultipartStructure(message, email);
		
		MimeMessageHelper.setHeaders(email, message);
		message.setSentDate(ofNullable(email.getSentDate()).orElse(new Date()));

		/*
			The following order is important:
			1. S/MIME signing
			2. S/MIME encryption
			3. DKIM signing
		 */

		if (email.getSmimeSigningConfig() != null) {
			message = ModuleLoader.loadSmimeModule().signMessageWithSmime(session, email, message, email.getSmimeSigningConfig());
		}

		/*
		 * Per-recipient S/MIME encryption:
		 * If any TO/CC/BCC recipient carries a smimeCertificate, use the per-recipient path.
		 * Effective cert per recipient = recipient cert (level 2) ?? email-level config cert (levels 1/4/5, already governance-resolved).
		 *
		 * NOTE: When a Mailer-level *override* cert has been applied via EmailGovernance it is already
		 * folded into email.getSmimeEncryptionConfig() and is indistinguishable from an email-level default
		 * at this point. In the per-recipient path the recipient cert therefore always wins.
		 * If you need the Mailer override to trump all per-recipient certs, simply leave the recipient
		 * smimeCertificate fields null and rely on the email-level (governance-resolved) cert alone.
		 */
		final boolean anyRecipientHasSmimeCert = email.getRecipients().stream()
				.anyMatch(r -> r.getSmimeCertificate() != null);

		if (anyRecipientHasSmimeCert) {
			final List<X509Certificate> effectiveCerts = email.getRecipients().stream()
					.filter(r -> r.getType() == TO || r.getType() == CC || r.getType() == BCC)
					.map(r -> r.getSmimeCertificate() != null
							? r.getSmimeCertificate()
							: (email.getSmimeEncryptionConfig() != null
									? email.getSmimeEncryptionConfig().getX509Certificate()
									: null))
					.filter(Objects::nonNull)
					.collect(Collectors.toList());
			if (!effectiveCerts.isEmpty()) {
				final String keyAlg = email.getSmimeEncryptionConfig() != null
						? email.getSmimeEncryptionConfig().getKeyEncapsulationAlgorithm() : null;
				final String cipherAlg = email.getSmimeEncryptionConfig() != null
						? email.getSmimeEncryptionConfig().getCipherAlgorithm() : null;
				message = ModuleLoader.loadSmimeModule()
						.encryptMessageWithSmimeForRecipients(session, email, message, effectiveCerts, keyAlg, cipherAlg);
			}
		} else if (email.getSmimeEncryptionConfig() != null) {
			message = ModuleLoader.loadSmimeModule().encryptMessageWithSmime(session, email, message, email.getSmimeEncryptionConfig());
		}

		if (email.getDkimConfig() != null) {
			message = ModuleLoader.loadDKIMModule().signMessageWithDKIM(email, message, email.getDkimConfig(), checkNonEmptyArgument(email.getFromRecipient(), "fromRecipient"));
		}

		if (email.getBounceToRecipient() != null || email.getDeliveryStatusNotification() != null) {
			// display name not applicable: https://tools.ietf.org/html/rfc5321#section-4.1.2
			message = new ImmutableDelegatingSMTPMessage(message,
					email.getBounceToRecipient() != null ? email.getBounceToRecipient().getAddress() : null,
					toSmtpNotifyOptions(email.getDeliveryStatusNotification()),
					toSmtpReturnOption(email.getDeliveryStatusNotification()));
		}

		return message;
	}

	@Nullable
	private static Integer toSmtpNotifyOptions(@Nullable final DeliveryStatusNotification deliveryStatusNotification) {
		if (deliveryStatusNotification == null || deliveryStatusNotification.getNotifyOptions().isEmpty()) {
			return null;
		}
		if (deliveryStatusNotification.getNotifyOptions().contains(DeliveryStatusNotification.NotifyOption.NEVER)) {
			return SMTPMessage.NOTIFY_NEVER;
		}
		int smtpNotifyOptions = 0;
		for (final DeliveryStatusNotification.NotifyOption notifyOption : deliveryStatusNotification.getNotifyOptions()) {
			switch (notifyOption) {
				case SUCCESS:
					smtpNotifyOptions |= SMTPMessage.NOTIFY_SUCCESS;
					break;
				case FAILURE:
					smtpNotifyOptions |= SMTPMessage.NOTIFY_FAILURE;
					break;
				case DELAY:
					smtpNotifyOptions |= SMTPMessage.NOTIFY_DELAY;
					break;
				default:
					throw new AssertionError("Unsupported DSN notify option: " + notifyOption);
			}
		}
		return smtpNotifyOptions;
	}

	@Nullable
	private static Integer toSmtpReturnOption(@Nullable final DeliveryStatusNotification deliveryStatusNotification) {
		if (deliveryStatusNotification == null || deliveryStatusNotification.getReturnOption() == null) {
			return null;
		}
		switch (deliveryStatusNotification.getReturnOption()) {
			case FULL_MESSAGE:
				return SMTPMessage.RETURN_FULL;
			case HEADERS_ONLY:
				return SMTPMessage.RETURN_HDRS;
			default:
				throw new AssertionError("Unsupported DSN return option: " + deliveryStatusNotification.getReturnOption());
		}
	}

	abstract void populateMimeMessageMultipartStructure(MimeMessage  message, Email email) throws MessagingException;
	
	
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
