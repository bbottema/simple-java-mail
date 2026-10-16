package org.simplejavamail.mailer.internal.util;

import org.simplejavamail.mailer.internal.MailSubmissionReceiptImpl;

import org.simplejavamail.api.mailer.MailRetryDisposition;

import org.simplejavamail.api.mailer.MailSubmissionStatus;

import java.util.Collections;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.api.internal.batchsupport.SelectedPoolTransport;
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.api.mailer.spi.MailTransportCompatibilityException;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.BatchModule;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.mailer.internal.MailSendDiagnosticsRecorder;
import org.simplejavamail.mailer.internal.MailSendProgressScope;
import org.simplejavamail.mailer.internal.SessionBasedEmailToMimeMessageConverter;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.simplejavamail.internal.util.MiscUtil.asInternetAddresses;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Sends mail through a selected pooled transport when available, or a direct Jakarta Mail transport otherwise.
 * <p>
 * Selection remains separate from acquisition so the selected destination keeps ownership of its allowance and connection facts.
 * <p>
 * <strong>Note</strong> that <a href="https://stackoverflow.com/a/12733317/441662">
 *     multiple threads can safely use a Session</a>, but are synchronized in the Transport connection.
 */
public class TransportRunner {

	private static final Logger LOGGER = getLogger(TransportRunner.class);

	/** Selects a registered destination without borrowing a connection; the Mailer can now apply that destination's sending limits. */
	@Nullable
	public static SelectedPoolTransport selectTransport(final UUID clusterKey, final Session session, final MailSendControl control) {
		control.checkStopped();
		return ModuleLoader.batchModuleAvailable() ? ModuleLoader.loadBatchModule().selectTransport(clusterKey, session, false, control) : null;
	}

	/** Sends through an already-acquired connection. The owning connection route has returned before this method can be invoked. */
	public static MailSubmissionReceipt sendMessageOnTransport(final Transport transport, final Session actualSessionUsed, final Email email,
			final MailSendControl control, final MailSendDiagnosticsRecorder diagnostics, final SendingAllowance.Reservation allowance)
			throws MessagingException {
		try {
			diagnostics.connectionAcquired(transport);
			control.checkStopped();
			diagnostics.startMimePreparation();
			final PreparedMail preparedMail = SessionBasedEmailToMimeMessageConverter.convertAndLogPreparedMail(actualSessionUsed, email);
			diagnostics.effectiveMessageId(email.getId());
			control.checkStopped();
			diagnostics.startSubmission();
			final MailTransportResult transportResult;
			try (MailSendProgressScope progress = diagnostics.openProgressScope(false, control)) {
				transportResult = MailTransportAdapterResolver.sendMessage(transport, preparedMail, () -> {
					allowance.commit(control);
					diagnostics.submissionInvoked();
				}, progress).withEnvelopeRecipients(preparedMail.getRecipients());
			}
			final MailSubmissionReceipt receipt = buildReceipt(email, transportResult);
			diagnostics.submissionReturned(receipt, transportResult.isSuccessful());
			if (!transportResult.isSuccessful()) {
				throw buildSubmissionException(email.getId(), email.getSubject(), transportResult, receipt);
			}
			LOGGER.trace("...email sent");
			return receipt;
		} catch (final MailSubmissionException failure) {
			diagnostics.submissionFailed(failure);
			diagnostics.failed();
			throw control.translateFailure(failure);
		} catch (final MessagingException failure) {
			diagnostics.submissionFailed(failure);
			diagnostics.failed();
			throw control.translateFailure(buildSubmissionException(email, MailTransportResult.failed(failure, null)));
		} catch (final RuntimeException failure) {
			diagnostics.submissionFailed(failure);
			diagnostics.failed();
			throw control.translateFailure(failure);
		} catch (final Error failure) {
			diagnostics.submissionFailed(failure);
			diagnostics.failed();
			throw failure;
		}
	}

	/** Captures provider-specific replay options once; it neither submits nor owns the supplied transport. */
	public static PreparedMail prepareForRepeatedSubmission(final Transport transport, final PreparedMail original, final MimeMessage frozen)
			throws MessagingException {
		return MailTransportAdapterResolver.prepareForRepeatedSubmission(transport, original, frozen);
	}

	/**
	 * Runs one physical attempt without freezing a logical terminal failure. The retry owner decides whether the captured
	 * refusal is intermediate; ordinary sending retains its existing translating boundary above.
	 */
	public static MailSubmissionReceipt sendPreparedMessageOnTransport(final Transport transport, final PreparedMail preparedMail,
			@Nullable final String messageId, @Nullable final String subject, final MailSendControl control, final MailSendDiagnosticsRecorder diagnostics,
			final SendingAllowance.Reservation allowance, final Runnable invocationStarted) throws MessagingException {
		try {
			diagnostics.effectiveMessageId(messageId);
			final MailTransportResult result = invokePreparedSubmission(transport, preparedMail, control, diagnostics, allowance, invocationStarted);
			final MailSubmissionReceipt receipt = recordSubmissionReceipt(messageId, preparedMail, result, diagnostics);
			if (!result.isSuccessful()) {
				throw buildSubmissionException(messageId, subject, result, receipt);
			}
			return receipt;
		} catch (MailSubmissionException failure) {
			diagnostics.submissionFailed(failure);
			diagnostics.failed();
			throw failure;
		} catch (MessagingException failure) {
			diagnostics.submissionFailed(failure);
			diagnostics.failed();
			final MailTransportResult result = MailTransportResult.failed(failure, null).withEnvelopeRecipients(preparedMail.getRecipients());
			final MailSubmissionReceipt receipt = recordSubmissionReceipt(messageId, preparedMail, result, diagnostics);
			throw buildSubmissionException(messageId, subject, result, receipt);
		} catch (RuntimeException | Error failure) {
			diagnostics.submissionFailed(failure);
			diagnostics.failed();
			throw failure;
		}
	}

	/** Observe the opaque callback itself, without inventing SMTP acceptance or connection details for its implementation. */
	public static void invokeCustomMailer(final OperationalConfig operationalConfig, final Session session, final Email email,
			final MimeMessage message, final MailSendDiagnosticsRecorder diagnostics) {
		diagnostics.submissionInvoked(true);
		try {
			operationalConfig.getCustomMailer().sendMessage(operationalConfig, session, email, message);
			diagnostics.customSubmissionReturned();
		} catch (RuntimeException | Error failure) {
			diagnostics.submissionFailed(failure);
			throw failure;
		}
	}

	private static MailTransportResult invokePreparedSubmission(final Transport transport, final PreparedMail preparedMail, final MailSendControl control,
			final MailSendDiagnosticsRecorder diagnostics, final SendingAllowance.Reservation allowance, final Runnable invocationStarted)
			throws MessagingException {
		control.checkStopped();
		diagnostics.startSubmission();
		try (MailSendProgressScope progress = diagnostics.openProgressScope(false, control)) {
			return MailTransportAdapterResolver.sendMessage(transport, preparedMail, () -> {
				// Charge allowance and the attempt count at provider entry, not for resolver failures before an invocation starts.
				allowance.commit(control);
				diagnostics.submissionInvoked();
				invocationStarted.run();
			}, progress).withEnvelopeRecipients(preparedMail.getRecipients());
		}
	}

	private static MailSubmissionReceipt recordSubmissionReceipt(@Nullable final String messageId, final PreparedMail preparedMail,
			final MailTransportResult result, final MailSendDiagnosticsRecorder diagnostics) {
		final MailSubmissionReceipt receipt = buildReceipt(messageId, preparedMail.getRecipients(), result);
		diagnostics.submissionReturned(receipt, result.isSuccessful());
		return receipt;
	}

	public static void connect(@NotNull final UUID clusterKey, @NotNull final Session session)
			throws MessagingException {
		runOnSessionTransport(clusterKey, session, true, (transport, actualSessionUsed) -> {
			// the fact that we reached here means a connection was made successfully
			LOGGER.debug("...connection successful");
			return null;
		});
	}

	@NotNull
	public static MailSubmissionReceipt buildReceipt(@NotNull final Email email, @Nullable final SmtpServerResponse smtpServerResponse) {
		if (smtpServerResponse != null) {
			final boolean accepted = smtpServerResponse.isPositiveCompletionReply();
			return MailSubmissionReceiptImpl.builder()
				.emailId(email.getId())
				.smtpResponse(smtpServerResponse)
				.submittedAt(Instant.now())
				.status(accepted ? MailSubmissionStatus.ACCEPTED : MailSubmissionStatus.REJECTED)
				.recipientResults(Collections.emptyList())
				.retryDisposition(accepted ? MailRetryDisposition.DO_NOT_RETRY : MailRetryDisposition.CALLER_POLICY_REQUIRED)
				.build();
		}
		return buildReceipt(email, MailTransportResult.unknown(null));
	}

	@NotNull
	private static MailSubmissionReceipt buildReceipt(@NotNull final Email email, @NotNull final MailTransportResult transportResult) {
		final Address[] envelope = asInternetAddresses(email.getOverrideReceivers().isEmpty()
				? email.getRecipients() : email.getOverrideReceivers(), UTF_8).toArray(new Address[0]);
		return buildReceipt(email.getId(), envelope, transportResult);
	}

	private static MailSubmissionReceipt buildReceipt(@Nullable final String messageId, final Address[] envelope, final MailTransportResult transportResult) {
		final MailTransportResult completeResult = transportResult.withEnvelopeRecipients(envelope);
		return MailSubmissionReceiptImpl.builder()
			.emailId(messageId)
			.smtpResponse(transportResult.getSmtpResponse().orElse(null))
			.submittedAt(Instant.now())
			.status(completeResult.getStatus())
			.recipientResults(completeResult.getRecipientResults())
			.retryDisposition(completeResult.getRetryDisposition())
			.envelopeId(completeResult.getEnvelopeId())
			.requireTlsUsed(completeResult.isRequireTlsUsed())
			.messageSize(completeResult.getMessageSize())
			.serverMaximumMessageSize(completeResult.getServerMaximumMessageSize())
			.build();
	}

	@NotNull
	private static MailSubmissionException buildSubmissionException(@NotNull final Email email, @NotNull final MailTransportResult transportResult) {
		return buildSubmissionException(email.getId(), email.getSubject(), transportResult, buildReceipt(email, transportResult));
	}

	private static MailSubmissionException buildSubmissionException(@Nullable final String messageId, @Nullable final String subject,
			final MailTransportResult transportResult, final MailSubmissionReceipt receipt) {
		final MessagingException failure = transportResult.getFailure()
				.orElseThrow(() -> new IllegalArgumentException("A failed transport result must retain its MessagingException"));
		final String emailIdentifier = messageId != null ? "ID: '" + messageId + "'" : "Subject: '" + subject + "'";
		return new MailSubmissionException("Failed to submit email [" + emailIdentifier + "], submission status: " + receipt.getStatus(),
				failure, receipt);
	}

	private static <T> T runOnSessionTransport(@NotNull final UUID clusterKey, @NotNull final Session session,
			final boolean stickySession, @NotNull final TransportOperation<T> operation)
			throws MessagingException {
		if (ModuleLoader.batchModuleAvailable()) {
			return sendUsingConnectionPool(ModuleLoader.loadBatchModule(), clusterKey, session, stickySession, null,
					MailSendDiagnosticsRecorder.unobserved(), operation);
		}
		try (Transport transport = transportFor(session)) {
			TransportConnectionHelper.connectTransport(transport, session);
			return operation.run(transport, session);
		} finally {
			LOGGER.trace("closing transport");
		}
	}

	@NotNull
	static Transport transportFor(@NotNull final Session session) throws MessagingException {
		try {
			return session.getTransport();
		} catch (NoSuchProviderException failure) {
			final String protocol = session.getProperty("mail.transport.protocol");
			throw new MessagingException("No Jakarta Mail transport provider is available"
					+ (protocol == null ? "" : " for protocol '" + protocol + "'")
					+ ". Sending requires a Jakarta Mail implementation and a matching MailTransportAdapter. "
					+ "For the supported Angus stack, add org.simplejavamail:angus-mail-provider-module.", failure);
		}
	}

	private static <T> T sendUsingConnectionPool(@NotNull final BatchModule batchModule, @NotNull final UUID clusterKey,
			@NotNull final Session session, final boolean stickySession, @Nullable final MailSendControl control,
			@NotNull final MailSendDiagnosticsRecorder diagnostics, @NotNull final TransportOperation<T> operation) throws MessagingException {
		final LifecycleDelegatingTransport delegatingTransport = batchModule.acquireTransport(clusterKey, session, stickySession, control);
		return sendUsingLease(delegatingTransport, diagnostics, operation);
	}

	private static <T> T sendUsingLease(final LifecycleDelegatingTransport delegatingTransport, final MailSendDiagnosticsRecorder diagnostics,
			final TransportOperation<T> operation) throws MessagingException {
		final T result;
		try {
			result = operation.run(delegatingTransport.getTransport(), delegatingTransport.getSessionUsedToObtainTransport());
		} catch (final Throwable failure) {
			diagnostics.failed();
			diagnostics.startCleanup();
			try {
				if (isTransportCompatibilityFailure(failure)) {
					delegatingTransport.signalTransportUsed();
				} else {
					delegatingTransport.signalTransportFailed();
				}
			} catch (RuntimeException | Error cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
		diagnostics.startCleanup();
		delegatingTransport.signalTransportUsed();
		return result;
	}

	private static boolean isTransportCompatibilityFailure(@NotNull final Throwable failure) {
		return failure instanceof MailSubmissionException
				&& failure.getCause() instanceof MailTransportCompatibilityException;
	}

	private interface TransportOperation<T> {
		T run(Transport transport, Session actualSessionUsed)
				throws MessagingException;
	}
}
