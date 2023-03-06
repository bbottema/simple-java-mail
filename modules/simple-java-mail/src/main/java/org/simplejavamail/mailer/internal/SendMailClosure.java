package org.simplejavamail.mailer.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.EmailWithDefaultsAndOverridesApplied;
import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.api.mailer.EmailTooBigException;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.mailer.internal.util.TransportRunner;

import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static org.simplejavamail.mailer.internal.MailerException.GENERIC_ERROR;
import static org.simplejavamail.mailer.internal.MailerException.MAILER_ERROR;
import static org.simplejavamail.mailer.internal.MailerException.UNKNOWN_ERROR;

/**
 * Separate closure that can be executed directly or from a thread.
 * <p>
 * Note that this Runnable implementation is <strong>not</strong> thread related, it is just to encapsulate the code to
 * be run directly or from a <em>real</em> Runnable.
 */
class SendMailClosure extends AbstractProxyServerSyncingClosure {

	@NotNull private final OperationalConfig operationalConfig;
	@NotNull private final Session session;
	@NotNull private final EmailWithDefaultsAndOverridesApplied email;
	private final boolean transportModeLoggingOnly;

	SendMailClosure(@NotNull OperationalConfig operationalConfig, @NotNull Session session, @NotNull EmailWithDefaultsAndOverridesApplied email, @Nullable AnonymousSocks5Server proxyServer, boolean transportModeLoggingOnly, @NotNull AtomicInteger smtpConnectionCounter) {
		super(smtpConnectionCounter, proxyServer);
		this.operationalConfig = operationalConfig;
		this.session = session;
		this.email = email;
		this.transportModeLoggingOnly = transportModeLoggingOnly;
	}

	@Override
	public void executeClosure() {
		LOGGER.trace("sending email...");
		try {
			if (transportModeLoggingOnly) {
				SessionBasedEmailToMimeMessageConverter.convertAndLogMimeMessage(session, email);
				LOGGER.info("TRANSPORT_MODE_LOGGING_ONLY: skipping actual sending...");
			} else if (operationalConfig.getCustomMailer() != null) {
				val message = SessionBasedEmailToMimeMessageConverter.convertAndLogMimeMessage(session, email);
				operationalConfig.getCustomMailer().sendMessage(operationalConfig, session, email, message);
			} else {
				TransportRunner.sendMessage(operationalConfig.getClusterKey(), session, email);
			}
		} catch (final MessagingException e) {
			handleException(e, GENERIC_ERROR);
		} catch (final MailerException | EmailTooBigException e) {
			handleException(e, MAILER_ERROR);
		} catch (final Exception e) {
			handleException(e, UNKNOWN_ERROR);
		}
	}

	private void handleException(final Exception e, String errorMsg) {
		LOGGER.trace("Failed to send email {}\n{}\n\t{}", email.getId(), email, errorMsg);
		val emailId = ofNullable(email.getId())
				.map(id -> format("ID: '%s'", id))
				.orElse(format("Subject: '%s'", email.getSubject()));
		throw new MailerException(format(errorMsg, emailId), e);
	}
}