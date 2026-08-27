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
import org.simplejavamail.api.mailer.EmailTooBigException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.config.OperationalConfig;
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
import static org.simplejavamail.mailer.internal.MailerException.MAILER_ERROR;
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

	SendMailsInSimpleBatchClosure(@NotNull OperationalConfig operationalConfig, @NotNull Session session, @NotNull Iterable<Email> userProvidedEmails,
			@NotNull Function<Email, Email> emailPreparer, @NotNull MailSendObserverNotifier mailSendObserverNotifier,
			@Nullable AnonymousSocks5Server proxyServer, boolean transportModeLoggingOnly, @NotNull AtomicInteger smtpConnectionCounter) {
		super(smtpConnectionCounter, proxyServer, session);
		this.operationalConfig = operationalConfig;
		this.session = session;
		this.userProvidedEmails = userProvidedEmails;
		this.emailPreparer = emailPreparer;
		this.mailSendObserverNotifier = mailSendObserverNotifier;
		this.transportModeLoggingOnly = transportModeLoggingOnly;
	}

	@Override
	public void executeClosure() {
		LOGGER.trace("sending emails in simple batch...");
		try {
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
		} catch (final MessagingException failure) {
			throwMappedFailure(failure, GENERIC_ERROR);
		} catch (final MailerException | EmailTooBigException failure) {
			throwMappedFailure(failure, MAILER_ERROR);
		} catch (final MailException failure) {
			completeCurrentSendWithFailure(failure);
			throw failure;
		} catch (final Exception failure) {
			throwMappedFailure(failure, UNKNOWN_ERROR);
		} catch (final Error failure) {
			completeCurrentSendWithFailure(failure);
			throw failure;
		}
	}

	private void convertAndLogEmailsOnly(@NotNull final Iterator<Email> emailIterator)
			throws MessagingException {
		while (emailIterator.hasNext()) {
			final Email email = prepareNextEmail(emailIterator);
			markCurrentSendStarted();
			SessionBasedEmailToMimeMessageConverter.convertAndLogMimeMessage(session, email);
			completeCurrentSendSuccessfully(TransportRunner.buildReceipt(email, null));
		}
	}

	private void sendEmailsUsingCustomMailer(@NotNull final Iterator<Email> emailIterator)
			throws MessagingException {
		val customMailer = checkNonEmptyArgument(operationalConfig.getCustomMailer(), "customMailer");
		while (emailIterator.hasNext()) {
			val email = prepareNextEmail(emailIterator);
			markCurrentSendStarted();
			final MimeMessage message = SessionBasedEmailToMimeMessageConverter.convertAndLogMimeMessage(session, email);
			customMailer.sendMessage(operationalConfig, session, email, message);
			completeCurrentSendSuccessfully(TransportRunner.buildReceipt(email, null));
		}
	}

	private void sendEmailsUsingSingleTransport(@NotNull final Iterator<Email> emailIterator)
			throws MessagingException {
		try (Transport transport = session.getTransport()) {
			TransportConnectionHelper.connectTransport(transport, session);
			while (emailIterator.hasNext()) {
				final Email email = prepareNextEmail(emailIterator);
				markCurrentSendStarted();
				completeCurrentSendSuccessfully(TransportRunner.sendMessageOnTransport(transport, session, email));
			}
		} finally {
			LOGGER.trace("closing transport");
		}
	}

	private Email prepareNextEmail(@NotNull final Iterator<Email> emailIterator) {
		currentEmail = null;
		currentMailSendAttempt = null;
		final Email userProvidedEmail = verifyNonnull(emailIterator.next());
		currentMailSendAttempt = mailSendObserverNotifier.beginAttempt(userProvidedEmail);
		currentEmail = emailPreparer.apply(userProvidedEmail);
		currentMailSendAttempt.prepared(currentEmail);
		return currentEmail;
	}

	private void markCurrentSendStarted() {
		checkNonEmptyArgument(currentMailSendAttempt, "currentMailSendAttempt").started();
	}

	private void completeCurrentSendSuccessfully(@NotNull final MailSubmissionReceipt submissionReceipt) {
		checkNonEmptyArgument(currentMailSendAttempt, "currentMailSendAttempt").completeSuccessfully(submissionReceipt);
	}

	private void completeCurrentSendWithFailure(@NotNull final Throwable failure) {
		if (currentMailSendAttempt != null) {
			currentMailSendAttempt.completeWithFailure(failure);
		}
	}

	private void throwMappedFailure(@NotNull final Exception cause, @NotNull final String errorMessage) {
		if (currentEmail == null) {
			LOGGER.trace("Failed to send simple email batch\n\t{}", errorMessage);
			final MailerException failure = new MailerException(format(errorMessage, "simple batch"), cause);
			completeCurrentSendWithFailure(failure);
			throw failure;
		}

		LOGGER.trace("Failed to send email {}\n{}\n\t{}", currentEmail.getId(), currentEmail, errorMessage);
		val emailId = ofNullable(currentEmail.getId())
				.map(id -> format("ID: '%s'", id))
				.orElse(format("Subject: '%s'", currentEmail.getSubject()));
		final MailerException failure = new MailerException(format(errorMessage, emailId), cause);
		completeCurrentSendWithFailure(failure);
		throw failure;
	}
}
