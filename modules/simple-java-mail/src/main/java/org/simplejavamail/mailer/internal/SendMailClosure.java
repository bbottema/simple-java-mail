package org.simplejavamail.mailer.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.api.mailer.EmailTooBigException;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendTimeoutException;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.mailer.internal.util.TransportRunner;
import org.simplejavamail.mailer.internal.util.AcquiredMailTransport;
import org.slf4j.Logger;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static org.simplejavamail.mailer.internal.MailerException.GENERIC_ERROR;
import static org.simplejavamail.mailer.internal.MailerException.MAILER_ERROR;
import static org.simplejavamail.mailer.internal.MailerException.UNKNOWN_ERROR;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Submits one prepared email after routing has finished acquiring its destination. It owns no connection or proxy lifetime.
 * <p>
 * Note that this Runnable implementation is <strong>not</strong> thread related, it is just to encapsulate the code to
 * be run directly or from a <em>real</em> Runnable.
 */
class SendMailClosure implements Runnable {

	private static final Logger LOGGER = getLogger(SendMailClosure.class);

	@NotNull private final OperationalConfig operationalConfig;
	@NotNull private final Session session;
	@NotNull private final Email email;
	private final boolean transportModeLoggingOnly;
	@Nullable private MailSubmissionReceipt receipt;
	@NotNull private final MailSendControl control;
	@NotNull private final MailSendDiagnosticsRecorder diagnostics;
	@Nullable private final AcquiredMailTransport connection;
	@Nullable private final SendingAllowance.Reservation allowance;

	SendMailClosure(final OperationalConfig operationalConfig, final Session session, final Email email, final MailSendControl control,
			final MailSendDiagnosticsRecorder diagnostics, @Nullable final AcquiredMailTransport connection,
			@Nullable final SendingAllowance.Reservation allowance) {
		this.operationalConfig = operationalConfig;
		this.session = session;
		this.email = email;
		this.transportModeLoggingOnly = operationalConfig.isTransportModeLoggingOnly();
		this.control = control;
		this.diagnostics = diagnostics;
		this.connection = connection;
		this.allowance = allowance;
	}

	@Override
	public void run() {
		LOGGER.trace("sending email...");
		try {
			control.checkStopped();
			if (transportModeLoggingOnly) {
				diagnostics.startMimePreparation();
				SessionBasedEmailToMimeMessageConverter.convertAndLogMimeMessage(session, email);
				control.checkStopped();
				LOGGER.info("TRANSPORT_MODE_LOGGING_ONLY: skipping actual sending...");
				receipt = TransportRunner.buildReceipt(email, null);
				diagnostics.receiptProduced(receipt);
			} else if (operationalConfig.getCustomMailer() != null) {
				diagnostics.startMimePreparation();
				val message = SessionBasedEmailToMimeMessageConverter.convertAndLogMimeMessage(session, email);
				diagnostics.effectiveMessageId(email.getId());
				control.checkStopped();
				diagnostics.startSubmission();
				requireNonNull(allowance, "allowance").commit(control);
				diagnostics.submissionInvoked();
				operationalConfig.getCustomMailer().sendMessage(operationalConfig, session, email, message);
				receipt = TransportRunner.buildReceipt(email, null);
				diagnostics.receiptProduced(receipt);
			} else {
				receipt = requireNonNull(connection, "connection").send(email, diagnostics, requireNonNull(allowance, "allowance"), control);
			}
		} catch (final MessagingException e) {
			handleException(e, GENERIC_ERROR);
		} catch (final MailSubmissionException | MailSendCancelledException | MailSendTimeoutException | ConnectionSetupFailure e) {
			throw e;
		} catch (final MailerException | EmailTooBigException e) {
			handleException(e, MAILER_ERROR);
		} catch (final Exception e) {
			handleException(e, UNKNOWN_ERROR);
		}
	}

	@NotNull
	MailSubmissionReceipt getReceipt() {
		if (receipt == null) {
			throw new IllegalStateException("No submission receipt available; send closure has not completed successfully");
		}
		return receipt;
	}

	private void handleException(final Exception e, String errorMsg) {
		LOGGER.trace("Failed to send email {}\n{}\n\t{}", email.getId(), email, errorMsg);
		val emailId = ofNullable(email.getId())
				.map(id -> format("ID: '%s'", id))
				.orElse(format("Subject: '%s'", email.getSubject()));
		throw control.translateFailure(new MailerException(format(errorMsg, emailId), e));
	}
}
