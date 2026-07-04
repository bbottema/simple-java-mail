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
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.mailer.internal.util.TransportConnectionHelper;
import org.simplejavamail.mailer.internal.util.TransportRunner;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
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
	private final boolean transportModeLoggingOnly;
	@Nullable private Email currentEmail;

	SendMailsInSimpleBatchClosure(@NotNull OperationalConfig operationalConfig, @NotNull Session session, @NotNull Iterable<Email> userProvidedEmails,
			@NotNull Function<Email, Email> emailPreparer, @Nullable AnonymousSocks5Server proxyServer, boolean transportModeLoggingOnly,
			@NotNull AtomicInteger smtpConnectionCounter) {
		super(smtpConnectionCounter, proxyServer);
		this.operationalConfig = operationalConfig;
		this.session = session;
		this.userProvidedEmails = userProvidedEmails;
		this.emailPreparer = emailPreparer;
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
		} catch (final MessagingException e) {
			handleException(e, GENERIC_ERROR);
		} catch (final MailerException | EmailTooBigException e) {
			handleException(e, MAILER_ERROR);
		} catch (final MailException e) {
			throw e;
		} catch (final Exception e) {
			handleException(e, UNKNOWN_ERROR);
		}
	}

	private void convertAndLogEmailsOnly(@NotNull final Iterator<Email> emailIterator)
			throws MessagingException {
		while (emailIterator.hasNext()) {
			SessionBasedEmailToMimeMessageConverter.convertAndLogMimeMessage(session, prepareNextEmail(emailIterator));
		}
	}

	private void sendEmailsUsingCustomMailer(@NotNull final Iterator<Email> emailIterator)
			throws MessagingException {
		val customMailer = checkNonEmptyArgument(operationalConfig.getCustomMailer(), "customMailer");
		while (emailIterator.hasNext()) {
			val email = prepareNextEmail(emailIterator);
			final MimeMessage message = SessionBasedEmailToMimeMessageConverter.convertAndLogMimeMessage(session, email);
			customMailer.sendMessage(operationalConfig, session, email, message);
		}
	}

	private void sendEmailsUsingSingleTransport(@NotNull final Iterator<Email> emailIterator)
			throws MessagingException {
		try (Transport transport = session.getTransport()) {
			TransportConnectionHelper.connectTransport(transport, session);
			while (emailIterator.hasNext()) {
				TransportRunner.sendMessageOnTransport(transport, session, prepareNextEmail(emailIterator));
			}
		} finally {
			LOGGER.trace("closing transport");
		}
	}

	private Email prepareNextEmail(@NotNull final Iterator<Email> emailIterator) {
		currentEmail = null;
		currentEmail = emailPreparer.apply(emailIterator.next());
		return currentEmail;
	}

	private void handleException(final Exception e, String errorMsg) {
		if (currentEmail == null) {
			LOGGER.trace("Failed to send simple email batch\n\t{}", errorMsg);
			throw new MailerException(format(errorMsg, "simple batch"), e);
		}

		LOGGER.trace("Failed to send email {}\n{}\n\t{}", currentEmail.getId(), currentEmail, errorMsg);
		val emailId = ofNullable(currentEmail.getId())
				.map(id -> format("ID: '%s'", id))
				.orElse(format("Subject: '%s'", currentEmail.getSubject()));
		throw new MailerException(format(errorMsg, emailId), e);
	}
}
