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
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.OpenConnectionCallback;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
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
	@NotNull private final MailSendOperations operations;

	SendMailsWithOpenConnectionClosure(@NotNull OperationalConfig operationalConfig, @NotNull Session session,
			@NotNull OpenConnectionCallback<E> openConnectionCallback, @NotNull Function<Email, Email> emailPreparer,
			@NotNull MailSendObserverNotifier mailSendObserverNotifier, @Nullable AnonymousSocks5Server proxyServer,
			boolean transportModeLoggingOnly, @NotNull AtomicInteger smtpConnectionCounter, @NotNull MailSendOperations operations) {
		super(smtpConnectionCounter, proxyServer, session);
		this.operationalConfig = operationalConfig;
		this.session = session;
		this.openConnectionCallback = openConnectionCallback;
		this.emailPreparer = emailPreparer;
		this.mailSendObserverNotifier = mailSendObserverNotifier;
		this.transportModeLoggingOnly = transportModeLoggingOnly;
		this.operations = operations;
	}

	@SuppressWarnings("unchecked")
	void runOpenConnectionCallback() throws E {
		try {
			operations.withinOperation(this::run);
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
		final MailSendOperation<MailSubmissionReceipt> operation;
		try {
			operation = operations.begin(mailSendAttempt::completeSuccessfully, mailSendAttempt::completeWithFailure);
		} catch (RuntimeException failure) {
			mailSendAttempt.completeWithFailure(failure);
			throw failure;
		}
		mailSendAttempt.attachControl(operation.control());
		return operation.executeSync(() -> sendOnOpenConnection(checkedEmail, mailSendAttempt, operation.control()));
	}

	private MailSubmissionReceipt sendOnOpenConnection(final Email checkedEmail, final MailSendAttempt mailSendAttempt, final MailSendControl control) {
		try {
			final Email preparedEmail = prepareEmail(checkedEmail);
			control.checkStopped();
			mailSendAttempt.prepared(preparedEmail);
			mailSendAttempt.started();
			if (transportModeLoggingOnly) {
				final MailSubmissionReceipt receipt = convertAndLogPreparedEmail(preparedEmail);
				control.checkStopped();
				return receipt;
			}
			final Transport activeTransport = checkNonEmptyArgument(transport, "transport");
			try (MailSendControl.Registration ignored = MailTransportLifecycleResolver.registerAbort(activeTransport, control)) {
				return TransportRunner.sendMessageOnTransport(activeTransport, session, preparedEmail, control);
			}
		} catch (final MessagingException failure) {
			throw control.translateFailure(createMailerException(failure, GENERIC_ERROR));
		} catch (final EmailTooBigException failure) {
			throw control.translateFailure(createMailerException(failure, MAILER_ERROR));
		} catch (final RuntimeException failure) {
			throw control.translateFailure(failure);
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

	private void openSmtpTransport()
			throws MessagingException {
		final MailSendOperation<Void> opening = operations.begin(unused -> { }, failure -> { });
		opening.executeSync(() -> {
			try {
				transport = session.getTransport();
				try (MailSendControl.Registration ignored = MailTransportLifecycleResolver.registerAbort(transport, opening.control())) {
					opening.control().checkStopped();
					TransportConnectionHelper.connectTransport(transport, session);
					opening.control().checkStopped();
				}
				return null;
			} catch (MessagingException failure) {
				throw opening.control().translateFailure(createMailerException(failure, GENERIC_ERROR));
			}
		});
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
