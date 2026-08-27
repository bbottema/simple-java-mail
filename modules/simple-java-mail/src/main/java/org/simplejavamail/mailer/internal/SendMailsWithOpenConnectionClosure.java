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
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
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
class SendMailsWithOpenConnectionClosure<E extends Exception> extends AbstractProxyServerSyncingClosure implements MailSender {

	@NotNull private final OperationalConfig operationalConfig;
	@NotNull private final Session session;
	@NotNull private final OpenConnectionCallback<E> openConnectionCallback;
	@NotNull private final Function<Email, Email> emailPreparer;
	@NotNull private final MailSendObserverNotifier mailSendObserverNotifier;
	private final boolean transportModeLoggingOnly;
	@Nullable private Transport transport;
	@Nullable private Email currentEmail;

	SendMailsWithOpenConnectionClosure(@NotNull OperationalConfig operationalConfig, @NotNull Session session,
			@NotNull OpenConnectionCallback<E> openConnectionCallback, @NotNull Function<Email, Email> emailPreparer,
			@NotNull MailSendObserverNotifier mailSendObserverNotifier, @Nullable AnonymousSocks5Server proxyServer,
			boolean transportModeLoggingOnly, @NotNull AtomicInteger smtpConnectionCounter) {
		super(smtpConnectionCounter, proxyServer, session);
		this.operationalConfig = operationalConfig;
		this.session = session;
		this.openConnectionCallback = openConnectionCallback;
		this.emailPreparer = emailPreparer;
		this.mailSendObserverNotifier = mailSendObserverNotifier;
		this.transportModeLoggingOnly = transportModeLoggingOnly;
	}

	@SuppressWarnings("unchecked")
	void runOpenConnectionCallback() throws E {
		try {
			run();
		} catch (final CheckedCallbackException callbackFailure) {
			throw (E) callbackFailure.getCause();
		} catch (final RuntimeCallbackException callbackFailure) {
			throw callbackFailure.getCause();
		}
	}

	@Override
	void executeClosure() {
		LOGGER.trace("sending emails with open connection...");
		boolean operationFailed = false;
		try {
			if (operationalConfig.getCustomMailer() != null) {
				throw new MailerException("Cannot use withOpenConnection when a custom mailer is configured");
			} else if (transportModeLoggingOnly) {
				runCallback(this);
				LOGGER.info("TRANSPORT_MODE_LOGGING_ONLY: skipping actual open connection sending...");
			} else {
				openSmtpTransport();
				runCallback(this);
			}
		} catch (final CheckedCallbackException callbackFailure) {
			operationFailed = true;
			throw callbackFailure;
		} catch (final RuntimeCallbackException callbackFailure) {
			operationFailed = true;
			throw callbackFailure;
		} catch (final MessagingException failure) {
			operationFailed = true;
			throwMappedFailure(failure, GENERIC_ERROR);
		} catch (final MailerException failure) {
			operationFailed = true;
			throw failure;
		} catch (final EmailTooBigException failure) {
			operationFailed = true;
			throwMappedFailure(failure, MAILER_ERROR);
		} catch (final MailException failure) {
			operationFailed = true;
			throw failure;
		} catch (final Exception failure) {
			operationFailed = true;
			throwMappedFailure(failure, UNKNOWN_ERROR);
		} catch (final Error failure) {
			operationFailed = true;
			throw failure;
		} finally {
			closeTransportIfOpened(operationFailed);
		}
	}

	@Override
	public void sendMail(@NotNull final Email userProvidedEmail) {
		sendMailAndGetReceipt(userProvidedEmail);
	}

	@Override
	@NotNull
	public MailSubmissionReceipt sendMailAndGetReceipt(@NotNull final Email userProvidedEmail) {
		final Email checkedEmail = verifyNonnull(userProvidedEmail);
		final MailSendAttempt mailSendAttempt = mailSendObserverNotifier.beginAttempt(checkedEmail);
		try {
			final Email preparedEmail = prepareEmail(checkedEmail);
			mailSendAttempt.prepared(preparedEmail);
			mailSendAttempt.started();
			final MailSubmissionReceipt submissionReceipt = transportModeLoggingOnly
					? convertAndLogPreparedEmail(preparedEmail)
					: sendPreparedEmailUsingSingleTransport(preparedEmail);
			mailSendAttempt.completeSuccessfully(submissionReceipt);
			return submissionReceipt;
		} catch (final MessagingException failure) {
			throw completeWithMappedFailure(mailSendAttempt, failure, GENERIC_ERROR);
		} catch (final EmailTooBigException failure) {
			throw completeWithMappedFailure(mailSendAttempt, failure, MAILER_ERROR);
		} catch (final RuntimeException | Error failure) {
			mailSendAttempt.completeWithFailure(failure);
			throw failure;
		}
	}

	private void runCallback(@NotNull final MailSender sender) {
		try {
			openConnectionCallback.accept(sender);
		} catch (final MailException | EmailTooBigException failure) {
			throw failure;
		} catch (final RuntimeException callbackFailure) {
			throw new RuntimeCallbackException(callbackFailure);
		} catch (final Error failure) {
			throw failure;
		} catch (final Exception callbackFailure) {
			throw new CheckedCallbackException(callbackFailure);
		}
	}

	@NotNull
	private MailSubmissionReceipt convertAndLogPreparedEmail(@NotNull final Email preparedEmail)
			throws MessagingException {
		SessionBasedEmailToMimeMessageConverter.convertAndLogMimeMessage(session, preparedEmail);
		return TransportRunner.buildReceipt(preparedEmail, null);
	}

	@NotNull
	private MailSubmissionReceipt sendPreparedEmailUsingSingleTransport(@NotNull final Email preparedEmail)
			throws MessagingException {
		return TransportRunner.sendMessageOnTransport(checkNonEmptyArgument(transport, "transport"), session, preparedEmail);
	}

	@NotNull
	private MailerException completeWithMappedFailure(@NotNull final MailSendAttempt mailSendAttempt,
			@NotNull final Exception cause,
			@NotNull final String errorMessage) {
		final MailerException failure = createMailerException(cause, errorMessage);
		mailSendAttempt.completeWithFailure(failure);
		return failure;
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
		} catch (final MessagingException closeFailure) {
			if (suppressCloseFailure) {
				LOGGER.trace("Failed to close open connection after earlier failure", closeFailure);
				return;
			}
			throw new MailerException("Was unable to close SMTP transport", closeFailure);
		}
	}

	private Email prepareEmail(@NotNull final Email userProvidedEmail) {
		currentEmail = null;
		currentEmail = emailPreparer.apply(userProvidedEmail);
		return currentEmail;
	}

	private void throwMappedFailure(@NotNull final Exception cause, @NotNull final String errorMessage) {
		throw createMailerException(cause, errorMessage);
	}

	@NotNull
	private MailerException createMailerException(@NotNull final Exception cause, @NotNull final String errorMessage) {
		if (currentEmail == null) {
			LOGGER.trace("Failed to send emails with open connection\n\t{}", errorMessage);
			return new MailerException(format(errorMessage, "open connection"), cause);
		}

		LOGGER.trace("Failed to send email {}\n{}\n\t{}", currentEmail.getId(), currentEmail, errorMessage);
		val emailId = ofNullable(currentEmail.getId())
				.map(id -> format("ID: '%s'", id))
				.orElse(format("Subject: '%s'", currentEmail.getSubject()));
		return new MailerException(format(errorMessage, emailId), cause);
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
