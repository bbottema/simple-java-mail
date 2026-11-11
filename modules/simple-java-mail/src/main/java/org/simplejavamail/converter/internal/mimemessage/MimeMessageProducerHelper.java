package org.simplejavamail.converter.internal.mimemessage;

import org.simplejavamail.internal.smime.FactorySmimeRuntime;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.email.internal.InternalEmail;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.List;

/**
 * Finds a compatible {@link SpecializedMimeMessageProducer} for a given Email and produces a MimeMessage accordingly.
 * <p>
 * This way, a MimeMessage structure will always be as succinct as possible, so that email clients will never get confused due to missing parts (such
 * as no attachments in a "mixed" multipart or no embedded images in a "related" multipart).
 * <p>
 * Also see issue <a href="https://github.com/bbottema/simple-java-mail/issues/144">#144</a>
 */
public final class MimeMessageProducerHelper {
	
	private static final List<SpecializedMimeMessageProducer> mimeMessageProducers = Arrays.asList(
			new MimeMessageProducerSimple(),
			new MimeMessageProducerAlternative(),
			new MimeMessageProducerRelated(),
			new MimeMessageProducerMixed(),
			new MimeMessageProducerMixedRelated(),
			new MimeMessageProducerMixedAlternative(),
			new MimeMessageProducerRelatedAlternative(),
			new MimeMessageProducerMixedRelatedAlternative()
	);
	
	private MimeMessageProducerHelper() {
	}
	
	@SuppressWarnings("deprecation")
	public static MimeMessage produceMimeMessage(Email email, Session session) throws UnsupportedEncodingException, MessagingException {
		return produceMimeMessage(email, session, true, FactorySmimeRuntime.isolated());
	}

	@SuppressWarnings("deprecation")
	public static MimeMessage produceBaseMimeMessage(Email email, Session session) throws UnsupportedEncodingException, MessagingException {
		return produceMimeMessage(email, session, false, FactorySmimeRuntime.isolated());
	}

	/** Renders under the actual selected factory owner without retaining that owner on the shared producer. */
	public static MimeMessage produceMimeMessage(final Email email, final Session session, final FactorySmimeRuntime smimeRuntime)
			throws UnsupportedEncodingException, MessagingException {
		return produceMimeMessage(email, session, true, smimeRuntime);
	}

	/** Applies the existing protection pipeline to an already prepared base message without repeating metadata/content preparation. */
	@org.jetbrains.annotations.ApiStatus.Internal
	public static MimeMessage applyMessageProtection(@NotNull final Email email, @NotNull final Session session,
			@NotNull final MimeMessage message) throws MessagingException {
		return applyMessageProtection(email, session, message, FactorySmimeRuntime.isolated());
	}

    public static MimeMessage applyMessageProtection(final Email email, final Session session,
            final MimeMessage message, final FactorySmimeRuntime smimeRuntime) throws MessagingException {
		return applyMessageProtection(email, session, message, smimeRuntime, false);
	}

	/** Finalizes one output under explicit conversion-local consent without changing the source draft. */
	public static MimeMessage applyMessageProtection(final Email email, final Session session, final MimeMessage message,
			final FactorySmimeRuntime smimeRuntime, final boolean conversionPermission) throws MessagingException {
		final MimeMessage completed = SpecializedMimeMessageProducer.applySecurity(email, session, message, smimeRuntime);
		ConfidentialContentGuard.verifyFinalOutput(email, completed, conversionPermission);
		return completed;
	}

	@SuppressWarnings("deprecation")
	private static MimeMessage produceMimeMessage(Email email, Session session, boolean processSecurity, FactorySmimeRuntime smimeRuntime) throws UnsupportedEncodingException, MessagingException {
		assert email instanceof InternalEmail;
		((InternalEmail) email).verifyDefaultsAndOverridesApplied();
		for (SpecializedMimeMessageProducer mimeMessageProducer : mimeMessageProducers) {
			if (mimeMessageProducer.compatibleWithEmail(email)) {
				return processSecurity
						? mimeMessageProducer.populateMimeMessage(email, session, smimeRuntime)
						: mimeMessageProducer.populateBaseMimeMessage(email, session);
			}
		}
		throw new IllegalStateException("no compatible SpecializedMimeMessageProducer found for email");
	}
}
