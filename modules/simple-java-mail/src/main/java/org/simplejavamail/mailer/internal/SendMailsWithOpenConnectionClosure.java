package org.simplejavamail.mailer.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.api.mailer.EmailTooBigException;
import org.simplejavamail.api.mailer.MailSender;
import org.simplejavamail.api.mailer.OpenConnectionCallback;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.mailer.internal.util.TransportConnectionHelper;
import org.simplejavamail.mailer.internal.util.TransportRunner;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnull;
import static org.simplejavamail.mailer.internal.MailerException.GENERIC_ERROR;
import static org.simplejavamail.mailer.internal.MailerException.MAILER_ERROR;
import static org.simplejavamail.mailer.internal.MailerException.UNKNOWN_ERROR;

/**
 * Runs caller-managed send logic while one SMTP connection is open.
 */
class SendMailsWithOpenConnectionClosure<E extends Exception> extends AbstractProxyServerSyncingClosure {

	@NotNull private final OperationalConfig operationalConfig;
	@NotNull private final Session session;
	@NotNull private final OpenConnectionCallback<E> openConnectionCallback;
	@NotNull private final Function<Email, Email> emailPreparer;
	private final boolean transportModeLoggingOnly;
	@Nullable private Transport transport;
	@Nullable private Email currentEmail;

	SendMailsWithOpenConnectionClosure(@NotNull OperationalConfig operationalConfig, @NotNull Session session,
			@NotNull OpenConnectionCallback<E> openConnectionCallback, @NotNull Function<Email, Email> emailPreparer,
			@Nullable AnonymousSocks5Server proxyServer, boolean transportModeLoggingOnly, @NotNull AtomicInteger smtpConnectionCounter) {
		super(smtpConnectionCounter, proxyServer);
		this.operationalConfig = operationalConfig;
		this.session = session;
		this.openConnectionCallback = openConnectionCallback;
		this.emailPreparer = emailPreparer;
		this.transportModeLoggingOnly = transportModeLoggingOnly;
	}

	@SuppressWarnings("unchecked")
	void runOpenConnectionCallback() throws E {
		try {
			run();
		} catch (final CheckedCallbackException e) {
			throw (E) e.getCause();
		} catch (final RuntimeCallbackException e) {
			throw e.getCause();
		}
	}

	@Override
	void executeClosure() {
		LOGGER.trace("sending emails with open connection...");
		boolean failed = false;
		try {
			if (operationalConfig.getCustomMailer() != null) {
				throw new MailerException("Cannot use withOpenConnection when a custom mailer is configured");
			} else if (transportModeLoggingOnly) {
				runCallback(this::convertAndLogEmailOnly);
				LOGGER.info("TRANSPORT_MODE_LOGGING_ONLY: skipping actual open connection sending...");
			} else {
				openSmtpTransport();
				runCallback(this::sendEmailUsingSingleTransport);
			}
		} catch (final CheckedCallbackException e) {
			failed = true;
			throw e;
		} catch (final RuntimeCallbackException e) {
			failed = true;
			throw e;
		} catch (final MessagingException e) {
			failed = true;
			handleException(e, GENERIC_ERROR);
		} catch (final MailerException e) {
			failed = true;
			throw e;
		} catch (final EmailTooBigException e) {
			failed = true;
			handleException(e, MAILER_ERROR);
		} catch (final MailException e) {
			failed = true;
			throw e;
		} catch (final Exception e) {
			failed = true;
			handleException(e, UNKNOWN_ERROR);
		} finally {
			closeTransportIfOpened(failed);
		}
	}

	private void runCallback(@NotNull final MailSender sender) {
		try {
			openConnectionCallback.accept(sender);
		} catch (final MailException | EmailTooBigException e) {
			throw e;
		} catch (final RuntimeException e) {
			throw new RuntimeCallbackException(e);
		} catch (final Error e) {
			throw e;
		} catch (final Exception e) {
			throw new CheckedCallbackException(e);
		}
	}

	private void convertAndLogEmailOnly(@NotNull final Email userProvidedEmail) {
		try {
			SessionBasedEmailToMimeMessageConverter.convertAndLogMimeMessage(session, prepareEmail(userProvidedEmail));
		} catch (final MessagingException e) {
			handleException(e, GENERIC_ERROR);
		}
	}

	private void sendEmailUsingSingleTransport(@NotNull final Email userProvidedEmail) {
		try {
			TransportRunner.sendMessageOnTransport(checkNonEmptyArgument(transport, "transport"), session, prepareEmail(userProvidedEmail));
		} catch (final MessagingException e) {
			handleException(e, GENERIC_ERROR);
		}
	}

	private void openSmtpTransport()
			throws MessagingException {
		transport = session.getTransport();
		TransportConnectionHelper.connectTransport(transport, session);
	}

	private void closeTransportIfOpened(boolean suppressCloseFailure) {
		if (transport == null) {
			return;
		}

		try {
			LOGGER.trace("closing transport");
			transport.close();
		} catch (final MessagingException e) {
			if (suppressCloseFailure) {
				LOGGER.trace("Failed to close open connection after earlier failure", e);
				return;
			}
			throw new MailerException("Was unable to close SMTP transport", e);
		}
	}

	private Email prepareEmail(@NotNull final Email userProvidedEmail) {
		currentEmail = null;
		currentEmail = emailPreparer.apply(verifyNonnull(userProvidedEmail));
		return currentEmail;
	}

	private void handleException(final Exception e, String errorMsg) {
		if (currentEmail == null) {
			LOGGER.trace("Failed to send emails with open connection\n\t{}", errorMsg);
			throw new MailerException(format(errorMsg, "open connection"), e);
		}

		LOGGER.trace("Failed to send email {}\n{}\n\t{}", currentEmail.getId(), currentEmail, errorMsg);
		val emailId = ofNullable(currentEmail.getId())
				.map(id -> format("ID: '%s'", id))
				.orElse(format("Subject: '%s'", currentEmail.getSubject()));
		throw new MailerException(format(errorMsg, emailId), e);
	}

	private static class CheckedCallbackException extends RuntimeException {

		CheckedCallbackException(final Exception cause) {
			super(cause);
		}

		@Override
		public synchronized Exception getCause() {
			return (Exception) super.getCause();
		}
	}

	private static class RuntimeCallbackException extends RuntimeException {

		RuntimeCallbackException(final RuntimeException cause) {
			super(cause);
		}

		@Override
		public synchronized RuntimeException getCause() {
			return (RuntimeException) super.getCause();
		}
	}
}
