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
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.api.mailer.EmailTooBigException;
import org.simplejavamail.api.mailer.MailSender;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSendCleanupException;
import org.simplejavamail.api.mailer.MailSendException;
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
	@NotNull private final SendingAllowance sendingAllowance;

	SendMailsWithOpenConnectionClosure(@NotNull OperationalConfig operationalConfig, @NotNull Session session,
			@NotNull OpenConnectionCallback<E> openConnectionCallback, @NotNull Function<Email, Email> emailPreparer,
			@NotNull MailSendObserverNotifier mailSendObserverNotifier, @Nullable AnonymousSocks5Server proxyServer,
			boolean transportModeLoggingOnly, @NotNull AtomicInteger smtpConnectionCounter, @NotNull MailSendOperations operations,
			@NotNull SendingAllowance sendingAllowance) {
		super(smtpConnectionCounter, proxyServer, session);
		this.operationalConfig = operationalConfig;
		this.session = session;
		this.openConnectionCallback = openConnectionCallback;
		this.emailPreparer = emailPreparer;
		this.mailSendObserverNotifier = mailSendObserverNotifier;
		this.transportModeLoggingOnly = transportModeLoggingOnly;
		this.operations = operations;
		this.sendingAllowance = sendingAllowance;
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
		Throwable operationFailure = null;
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
			operationFailure = callbackFailure.getCause();
			throw callbackFailure;
		} catch (final RuntimeCallbackException callbackFailure) {
			operationFailure = callbackFailure.getCause();
			throw callbackFailure;
		} catch (final MessagingException failure) {
			operationFailure = createMailerException(failure, GENERIC_ERROR);
			throw (MailerException) operationFailure;
		} catch (final MailerException failure) {
			operationFailure = failure;
			throw failure;
		} catch (final EmailTooBigException failure) {
			operationFailure = createMailerException(failure, MAILER_ERROR);
			throw (MailerException) operationFailure;
		} catch (final MailException failure) {
			operationFailure = failure;
			throw failure;
		} catch (final Exception failure) {
			operationFailure = createMailerException(failure, UNKNOWN_ERROR);
			throw (MailerException) operationFailure;
		} catch (final Error failure) {
			operationFailure = failure;
			throw failure;
		} finally {
			closeTransportIfOpened(operationFailure);
		}
	}

	/** @see MailSender#sendMail(Email) */
	@Override
	@NotNull
	public MailSubmissionReceipt sendMail(@NotNull final Email userProvidedEmail) {
		final Email checkedEmail = verifyNonnull(userProvidedEmail);
		final MailSendAttempt mailSendAttempt = mailSendObserverNotifier.beginAttempt(checkedEmail);
		if (!transportModeLoggingOnly) {
			mailSendAttempt.diagnostics().useSharedConnection();
		} else {
			mailSendAttempt.diagnostics().useLoggingOnly();
		}
		final MailSendOperation<MailSubmissionReceipt> operation;
		try {
			operation = operations.begin(mailSendAttempt::completeSuccessfully, mailSendAttempt::completeWithFailure);
		} catch (RuntimeException failure) {
			mailSendAttempt.completeWithFailure(failure);
			throw failure;
		}
		mailSendAttempt.attachControl(operation.control());
		operation.observeWith(mailSendAttempt);
		mailSendAttempt.readyForAdmission();
		return operation.executeSync(() -> sendOnOpenConnection(checkedEmail, mailSendAttempt, operation.control()));
	}

	private MailSubmissionReceipt sendOnOpenConnection(final Email checkedEmail, final MailSendAttempt mailSendAttempt, final MailSendControl control) {
		mailSendAttempt.started();
		mailSendAttempt.preparationStarted();
		MailSubmissionReceipt receipt = null;
		try {
			final Email preparedEmail = prepareEmail(checkedEmail);
			control.checkStopped();
			mailSendAttempt.prepared(preparedEmail);
			if (transportModeLoggingOnly) {
				mailSendAttempt.diagnostics().startMimePreparation();
				receipt = convertAndLogPreparedEmail(preparedEmail);
				control.checkStopped();
				mailSendAttempt.diagnostics().receiptProduced(receipt);
				return receipt;
			}
			final Transport activeTransport = checkNonEmptyArgument(transport, "transport");
			if (operationalConfig.getMailSendRetryPolicy().isRetryEnabled()) {
				try (MailSendControl.Registration ignored = MailTransportLifecycleResolver.registerAbort(activeTransport, control)) {
					receipt = RetainedMailSendRetry.send(session, activeTransport, preparedEmail, sendingAllowance, control, mailSendAttempt.diagnostics());
					return receipt;
				}
			}
			try (MailSendControl.Registration ignored = MailTransportLifecycleResolver.registerAbort(activeTransport, control);
					SendingAllowance.Reservation allowance = EmailSendingAllowance.reserve(
							sendingAllowance, session, preparedEmail, control, mailSendAttempt.diagnostics())) {
				receipt = TransportRunner.sendMessageOnTransport(activeTransport, session, preparedEmail, control, mailSendAttempt.diagnostics(), allowance);
				return receipt;
			}
		} catch (final MessagingException failure) {
			if (receipt != null) { throw control.translateFailure(new MailSendCleanupException("Individual send cleanup failed on the shared connection", failure, receipt)); }
			throw control.translateFailure(createMailerException(failure, GENERIC_ERROR));
		} catch (final EmailTooBigException failure) {
			throw control.translateFailure(createMailerException(failure, MAILER_ERROR));
		} catch (final RuntimeException failure) {
			if (receipt != null && !(failure instanceof MailSendException)) {
				throw control.translateFailure(new MailSendCleanupException("Individual send cleanup failed on the shared connection", failure, receipt));
			}
			throw control.translateFailure(failure);
		}
	}

	private void runCallback(@NotNull final MailSender sender) {
		final ScopedMailSender scopedSender = new ScopedMailSender(sender);
		try {
			openConnectionCallback.accept(scopedSender);
		} catch (final MailException | EmailTooBigException failure) {
			throw failure;
		} catch (final RuntimeException callbackFailure) {
			throw new RuntimeCallbackException(callbackFailure);
		} catch (final Error failure) {
			throw failure;
		} catch (final Exception callbackFailure) {
			throw new CheckedCallbackException(callbackFailure);
		} finally {
			scopedSender.endCallback();
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

	private void closeTransportIfOpened(@Nullable final Throwable operationFailure) {
		if (transport == null) {
			return;
		}

		try {
			LOGGER.trace("closing transport");
			transport.close();
		} catch (final MessagingException | RuntimeException | Error closeFailure) {
			if (operationFailure != null) {
				if (operationFailure != closeFailure) {
					operationFailure.addSuppressed(closeFailure);
				}
				return;
			}
			if (closeFailure instanceof Error) {
				throw (Error) closeFailure;
			}
			throw new MailerException("Was unable to close SMTP transport", (Exception) closeFailure);
		}
	}

	private Email prepareEmail(@NotNull final Email userProvidedEmail) {
		currentEmail = null;
		currentEmail = emailPreparer.apply(userProvidedEmail);
		return currentEmail;
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
