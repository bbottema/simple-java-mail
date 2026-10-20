package org.simplejavamail.mailer.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSendCleanupException;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge;
import org.simplejavamail.mailer.internal.util.TransportConnectionHelper;
import org.simplejavamail.mailer.internal.util.TransportRunner;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnull;
import static org.simplejavamail.mailer.internal.MailerException.GENERIC_ERROR;
import static org.simplejavamail.mailer.internal.MailerException.UNKNOWN_ERROR;

/**
 * Sends a caller-managed sequence of emails over one SMTP connection.
 */
class SendMailsInSimpleBatchClosure extends AbstractProxyServerSyncingClosure {

	@NotNull private final OperationalConfig operationalConfig;
	@NotNull private final Session session;
	@NotNull private final Iterable<Email> userProvidedEmails;
	@NotNull private final Function<Email, Email> emailPreparer;
	@NotNull private final MailSendObserverNotifier mailSendObserverNotifier;
	private final boolean transportModeLoggingOnly;
	@Nullable private Email currentEmail;
	@Nullable private MailSendAttempt currentMailSendAttempt;
	@Nullable private RuntimeException handlerInitializationFailure;
	@Nullable private MailSendTelemetryBridge.Scope currentTelemetryScope;
	@NotNull private final MailSendControl control;
	@NotNull private final SendingAllowance sendingAllowance;
	@NotNull private MailFailureKind checkedFailureKind = MailFailureKind.CONNECTION;

	SendMailsInSimpleBatchClosure(@NotNull OperationalConfig operationalConfig, @NotNull Session session, @NotNull Iterable<Email> userProvidedEmails,
			@NotNull Function<Email, Email> emailPreparer, @NotNull MailSendObserverNotifier mailSendObserverNotifier,
			@Nullable AnonymousSocks5Server proxyServer, boolean transportModeLoggingOnly, @NotNull AtomicInteger smtpConnectionCounter,
			@NotNull MailSendControl control, @NotNull SendingAllowance sendingAllowance) {
		super(smtpConnectionCounter, proxyServer, session);
		this.operationalConfig = operationalConfig;
		this.session = session;
		this.userProvidedEmails = userProvidedEmails;
		this.emailPreparer = emailPreparer;
		this.mailSendObserverNotifier = mailSendObserverNotifier;
		this.transportModeLoggingOnly = transportModeLoggingOnly;
		this.control = control;
		this.sendingAllowance = sendingAllowance;
	}

	@Override
	public void executeClosure() {
		LOGGER.trace("sending emails in simple batch...");
		try {
			control.checkStopped();
			val emailIterator = userProvidedEmails.iterator();
			if (!emailIterator.hasNext()) {
				LOGGER.trace("simple batch contained no emails");
				return;
			}

			if (transportModeLoggingOnly) {
				convertAndLogEmailsOnly(emailIterator);
				LOGGER.info("TRANSPORT_MODE_LOGGING_ONLY: skipping actual simple batch sending...");
			} else if (operationalConfig.getCustomMailer() != null) {
				sendEmailsUsingCustomMailer(emailIterator);
			} else {
				sendEmailsUsingSingleTransport(emailIterator);
			}
		} catch (final Exception failure) {
			throw reportFailure(failure);
		} catch (final Error failure) {
			completeCurrentSendWithFailure(failure);
			throw failure;
		}
	}

	private void convertAndLogEmailsOnly(@NotNull final Iterator<Email> emailIterator)
			throws MessagingException {
		while (emailIterator.hasNext()) {
			final Email email = prepareNextEmail(emailIterator);
			control.checkStopped();
			currentDiagnostics().startMimePreparation();
			SessionBasedEmailToMimeMessageConverter.convertAndLogMimeMessage(session, email);
			control.checkStopped();
			completeCurrentSendSuccessfully(TransportRunner.buildReceipt(email, null));
		}
		checkedFailureKind = MailFailureKind.CLEANUP;
	}

	private void sendEmailsUsingCustomMailer(@NotNull final Iterator<Email> emailIterator)
			throws MessagingException {
		while (emailIterator.hasNext()) {
			val email = prepareNextEmail(emailIterator);
			control.checkStopped();
			MailSubmissionReceipt receipt = null;
			try (SendingAllowance.Reservation allowance = EmailSendingAllowance.reserve(sendingAllowance, session, email, control, currentDiagnostics())) {
				currentDiagnostics().startMimePreparation();
				final MimeMessage message = SessionBasedEmailToMimeMessageConverter.convertAndLogMimeMessage(session, email);
				currentDiagnostics().effectiveMessageId(email.getId());
				control.checkStopped();
				currentDiagnostics().startSubmission();
				allowance.commit(control);
				TransportRunner.invokeCustomMailer(operationalConfig, session, email, message, currentDiagnostics());
				receipt = TransportRunner.buildReceipt(email, null);
				currentDiagnostics().receiptProduced(receipt);
			} catch (RuntimeException failure) {
				if (receipt != null) { throw new MailSendCleanupException("Individual send cleanup failed in the simple batch", failure, receipt); }
				throw failure;
			}
			completeCurrentSendSuccessfully(receipt);
		}
		checkedFailureKind = MailFailureKind.CLEANUP;
	}

	private void sendEmailsUsingSingleTransport(@NotNull final Iterator<Email> emailIterator)
			throws MessagingException {
		final Transport transport = session.getTransport();
		try (MailSendControl.Registration ignored = MailTransportLifecycleResolver.registerAbort(transport, control)) {
			try (Transport ownedTransport = transport) {
				try {
					control.checkStopped();
					TransportConnectionHelper.connectForSending(ownedTransport, session);
					while (emailIterator.hasNext()) {
						final Email email = prepareNextEmail(emailIterator);
						control.checkStopped();
						if (operationalConfig.getMailSendRetryPolicy().isRetryEnabled()) {
							completeCurrentSendSuccessfully(RetainedMailSendRetry.send(session, ownedTransport, email,
									sendingAllowance, control, currentDiagnostics()));
							continue;
						}
						MailSubmissionReceipt receipt = null;
						try (SendingAllowance.Reservation allowance = EmailSendingAllowance.reserve(sendingAllowance, session, email, control, currentDiagnostics())) {
							receipt = TransportRunner.sendMessageOnTransport(ownedTransport, session, email, control, currentDiagnostics(), allowance);
						} catch (RuntimeException failure) {
							if (receipt != null) { throw new MailSendCleanupException("Individual send cleanup failed in the simple batch", failure, receipt); }
							throw failure;
						}
						completeCurrentSendSuccessfully(receipt);
					}
					checkedFailureKind = MailFailureKind.CLEANUP;
				} catch (Exception failure) {
					throw reportFailure(failure);
				} catch (Error failure) {
					completeCurrentSendWithFailure(failure);
					throw failure;
				}
			}
		} finally {
			LOGGER.trace("closing transport");
		}
	}

	private Email prepareNextEmail(@NotNull final Iterator<Email> emailIterator) {
		checkedFailureKind = MailFailureKind.PREPARATION;
		currentEmail = null;
		currentMailSendAttempt = null;
		handlerInitializationFailure = null;
		control.checkStopped();
		final Email userProvidedEmail = verifyNonnull(emailIterator.next());
		try {
			currentMailSendAttempt = mailSendObserverNotifier.beginAttempt(userProvidedEmail, control);
		} catch (RuntimeException failure) {
			handlerInitializationFailure = failure;
			throw failure;
		}
		currentTelemetryScope = currentMailSendAttempt.attachTelemetry();
		currentMailSendAttempt.readyForAdmission();
		currentMailSendAttempt.started();
		currentMailSendAttempt.preparationStarted();
		if (transportModeLoggingOnly) {
			currentDiagnostics().useLoggingOnly();
		} else if (operationalConfig.getCustomMailer() != null) {
			currentDiagnostics().useCustomMailer();
		} else {
			currentDiagnostics().useSharedConnection();
		}
		currentEmail = emailPreparer.apply(userProvidedEmail);
		control.checkStopped();
		currentMailSendAttempt.prepared(currentEmail);
		return currentEmail;
	}

	private MailSendDiagnosticsRecorder currentDiagnostics() {
		return checkNonEmptyArgument(currentMailSendAttempt, "currentMailSendAttempt").diagnostics();
	}

	private void completeCurrentSendSuccessfully(@NotNull final MailSubmissionReceipt submissionReceipt) {
		currentDiagnostics().receiptProduced(submissionReceipt);
		closeCurrentTelemetryScope();
		checkNonEmptyArgument(currentMailSendAttempt, "currentMailSendAttempt").completeSuccessfully(submissionReceipt);
	}

	private void completeCurrentSendWithFailure(@NotNull final Throwable failure) {
		closeCurrentTelemetryScope();
		if (currentMailSendAttempt != null) {
			currentMailSendAttempt.completeWithFailure(failure);
		}
	}

	private void closeCurrentTelemetryScope() {
		if (currentTelemetryScope != null) {
			currentTelemetryScope.close();
			currentTelemetryScope = null;
		}
	}

	/** Freeze the failure before connection cleanup, then notify while the shared transport is still in scope. */
	private RuntimeException reportFailure(@NotNull final Exception cause) {
		if (cause == handlerInitializationFailure) { return handlerInitializationFailure; }
		final RuntimeException failure = control.translateFailure(cause instanceof RuntimeException
				? (RuntimeException) cause : createMailerException(cause));
		completeCurrentSendWithFailure(failure);
		return failure;
	}

	private MailerException createMailerException(@NotNull final Exception cause) {
		final String errorMessage = cause instanceof MessagingException ? GENERIC_ERROR
				: UNKNOWN_ERROR;
		final String emailId = currentEmail == null ? "simple batch" : ofNullable(currentEmail.getId())
				.map(id -> format("ID: '%s'", id))
				.orElse(format("Subject: '%s'", currentEmail.getSubject()));
		LOGGER.trace("Failed to send email {}\n\t{}", emailId, errorMessage);
		return new MailerException(checkedFailureKind, format(errorMessage, emailId), cause);
	}
}
