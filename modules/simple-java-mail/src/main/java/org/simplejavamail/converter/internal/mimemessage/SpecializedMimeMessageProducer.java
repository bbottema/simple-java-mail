package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.internal.util.MessageIdFixingMimeMessage;

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
 * <a href="https://www.simplejavamail.org/security.html#section-sending-dkim">DKIM signing</a> and
 * <a href="https://www.simplejavamail.org/security.html#section-sending-smime">S/MIME signing / encryption</a>.
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
		ProviderNeutralDataContentHandlers.install();

		MimeMessage message = new MessageIdFixingMimeMessage(session, email.getId());
		
		// set basic email properties
		MimeMessageHelper.setSubject(email, message);
		MimeMessageHelper.setFrom(email, message);
		MimeMessageHelper.setReplyTo(email, message);
		MimeMessageHelper.setRecipients(email, message);
		
		populateMimeMessageMultipartStructure(message, email);
		
		MimeMessageHelper.setHeaders(email, message);
		message.setSentDate(ofNullable(email.getSentDate()).orElse(new Date()));
		message = FinalizedMimeMessage.finalizeMessage(message, FinalizedMimeMessage.ProtectionState.NONE);

		/*
			The following order is important:
			1. S/MIME signing
			2. S/MIME encryption
			3. OpenPGP signing/encryption (when S/MIME is not configured)
			4. DKIM signing
		 */

		if (email.getSmimeSigningConfig() != null) {
			final MimeMessage input = message;
			try {
				final MimeMessage signed = ModuleLoader.loadSmimeModule().signMessageWithSmime(
						session, email, input, email.getSmimeSigningConfig());
				message = FinalizedMimeMessage.finalizeMessage(signed, FinalizedMimeMessage.ProtectionState.CONTENT_PROTECTED);
			} finally {
				closeFinalized(input);
			}
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
				final MimeMessage input = message;
				try {
					final MimeMessage encrypted = ModuleLoader.loadSmimeModule()
							.encryptMessageWithSmimeForRecipients(session, email, input, effectiveCerts, keyAlg, cipherAlg);
					message = FinalizedMimeMessage.finalizeMessage(encrypted, FinalizedMimeMessage.ProtectionState.CONTENT_PROTECTED);
				} finally {
					closeFinalized(input);
				}
			}
		} else if (email.getSmimeEncryptionConfig() != null) {
			final MimeMessage input = message;
			try {
				final MimeMessage encrypted = ModuleLoader.loadSmimeModule().encryptMessageWithSmime(
						session, email, input, email.getSmimeEncryptionConfig());
				message = FinalizedMimeMessage.finalizeMessage(encrypted, FinalizedMimeMessage.ProtectionState.CONTENT_PROTECTED);
			} finally {
				closeFinalized(input);
			}
		}

		if (email.getOpenPgpSigningConfig() != null) {
			final MimeMessage input = message;
			try {
				message = ModuleLoader.loadOpenPgpModule().signMessage(
						session, email, input, email.getOpenPgpSigningConfig());
			} finally {
				closeFinalized(input);
			}
		}
		if (email.getOpenPgpEncryptionConfig() != null) {
			final MimeMessage input = message;
			try {
				message = ModuleLoader.loadOpenPgpModule().encryptMessage(
						session, email, input, email.getOpenPgpEncryptionConfig());
			} finally {
				closeFinalized(input);
			}
		}

		if (email.getDkimConfig() != null) {
			final MimeMessage input = message;
			try {
				message = ModuleLoader.loadDKIMModule().signMessageWithDKIM(email, input, email.getDkimConfig(),
						checkNonEmptyArgument(email.getFromRecipient(), "fromRecipient"));
			} finally {
				closeFinalized(input);
			}
		}

		return message;
	}

	private static void closeFinalized(@NotNull final MimeMessage message) {
		if (message instanceof FinalizedMimeMessage) {
			((FinalizedMimeMessage) message).close();
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
