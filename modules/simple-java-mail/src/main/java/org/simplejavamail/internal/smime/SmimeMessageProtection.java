package org.simplejavamail.internal.smime;

import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.internal.configresolution.ConfigurationChoice;
import org.simplejavamail.internal.configresolution.ConfigurationDeclaration;
import org.simplejavamail.internal.configresolution.ConfigurationOrigin;
import org.simplejavamail.internal.configresolution.ConfigurationResolver;
import org.simplejavamail.internal.configresolution.ConfigurationSetting;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.util.FinalizedMimeMessage;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;

/** Shared main-runtime S/MIME selection and finalization for composed conversion and existing MIME content. */
@ApiStatus.Internal
public final class SmimeMessageProtection {

	private SmimeMessageProtection() {
	}

	/** Applies selected S/MIME stages to the supplied message, leaving entry points to map checked finalization failures. */
	@NotNull
	public static MimeMessage apply(@NotNull final Session session, @NotNull MimeMessage message, @NotNull final Email email)
			throws MessagingException {
		if (email.getSmimeSigningConfig() != null) {
			final MimeMessage signed = ModuleLoader.loadSmimeModule().signMessageWithSmime(
					session, email, message, email.getSmimeSigningConfig());
			message = FinalizedMimeMessage.finalizeMessage(signed, FinalizedMimeMessage.ProtectionState.CONTENT_PROTECTED);
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
					.map(r -> selectRecipientCertificate(r, email))
					.filter(Objects::nonNull)
					.collect(Collectors.toList());
			if (!effectiveCerts.isEmpty()) {
				final String keyAlg = email.getSmimeEncryptionConfig() != null
						? email.getSmimeEncryptionConfig().getKeyEncapsulationAlgorithm() : null;
				final String cipherAlg = email.getSmimeEncryptionConfig() != null
						? email.getSmimeEncryptionConfig().getCipherAlgorithm() : null;
				final MimeMessage encrypted = ModuleLoader.loadSmimeModule()
						.encryptMessageWithSmimeForRecipients(session, email, message, effectiveCerts, keyAlg, cipherAlg);
				message = FinalizedMimeMessage.finalizeMessage(encrypted, FinalizedMimeMessage.ProtectionState.CONTENT_PROTECTED);
			}
		} else if (email.getSmimeEncryptionConfig() != null) {
			final MimeMessage encrypted = ModuleLoader.loadSmimeModule().encryptMessageWithSmime(
					session, email, message, email.getSmimeEncryptionConfig());
			message = FinalizedMimeMessage.finalizeMessage(encrypted, FinalizedMimeMessage.ProtectionState.CONTENT_PROTECTED);
		}
		return message;
	}

	private static X509Certificate selectRecipientCertificate(final Recipient recipient, final Email email) {
		final X509Certificate fallback = email.getSmimeEncryptionConfig() == null ? null : email.getSmimeEncryptionConfig().getX509Certificate();
		final ConfigurationSetting<X509Certificate> setting = ConfigurationSetting.of("smime", "recipient-encryption-certificate");
		return ConfigurationResolver.resolve(setting, List.of(
				ConfigurationDeclaration.of(setting, ConfigurationChoice.ofNullable(recipient.getSmimeCertificate()),
						ConfigurationOrigin.of(ConfigurationOrigin.Kind.SUBMITTED, "recipient.smimeCertificate", "withSmimeCertificate")),
				ConfigurationDeclaration.of(setting, ConfigurationChoice.ofNullable(fallback),
						ConfigurationOrigin.of(ConfigurationOrigin.Kind.DEFAULT, "email.smimeEncryptionConfig", "governance-resolved Email"))),
				List.of(ConfigurationOrigin.Kind.SUBMITTED, ConfigurationOrigin.Kind.DEFAULT)).valueOrNull();
	}
}
