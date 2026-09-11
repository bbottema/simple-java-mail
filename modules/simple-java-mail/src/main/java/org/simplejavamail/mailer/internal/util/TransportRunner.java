package org.simplejavamail.mailer.internal.util;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.BatchModule;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.mailer.internal.SessionBasedEmailToMimeMessageConverter;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.simplejavamail.internal.util.MiscUtil.asInternetAddresses;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * If available, runs activities on Transport connections using SMTP connection pool from the batch-module.
 * <p>
 * Otherwise, always creates a new connection to run the activity on.
 * <p>
 * <strong>Note</strong> that <a href="https://stackoverflow.com/a/12733317/441662">
 *     multiple threads can safely use a Session</a>, but are synchronized in the Transport connection.
 */
public class TransportRunner {

	private static final Logger LOGGER = getLogger(TransportRunner.class);

	/**
	 * NOTE: only in case batch-module is *not* in use, the {@link Session} passed in here is guaranteed to be used to send this message.
	 *
	 * @param clusterKey The cluster key to use for the connection pool, which was randomly generated in the Mailer builder if not provided.
	 */
	public static MailSubmissionReceipt sendMessage(@NotNull final UUID clusterKey, @NotNull final Session session, @NotNull final Email email,
			@NotNull final MailSendControl control)
			throws MessagingException {
		control.checkStopped();
		if (ModuleLoader.batchModuleAvailable()) {
			return sendUsingConnectionPool(ModuleLoader.loadBatchModule(), clusterKey, session, false, control,
					(transport, actualSessionUsed) -> sendMessageOnTransport(transport, actualSessionUsed, email, control));
		}
		return sendOnNewTransport(session, email, control);
	}

	private static MailSubmissionReceipt sendOnNewTransport(final Session session, final Email email, final MailSendControl control)
			throws MessagingException {
		final Transport transport = transportFor(session);
		try (MailSendControl.Registration ignored = MailTransportLifecycleResolver.registerAbort(transport, control)) {
			MailSubmissionReceipt receipt = null;
			Throwable primaryFailure = null;
			try {
				control.checkStopped();
				TransportConnectionHelper.connectTransport(transport, session);
				receipt = sendMessageOnTransport(transport, session, email, control);
				return receipt;
			} catch (MessagingException failure) {
				final RuntimeException mapped = control.translateFailure(buildSubmissionException(email, MailTransportResult.failed(failure, null)));
				primaryFailure = mapped;
				throw mapped;
			} catch (RuntimeException failure) {
				final RuntimeException mapped = control.translateFailure(failure);
				primaryFailure = mapped;
				throw mapped;
			} catch (Error failure) {
				primaryFailure = failure;
				throw failure;
			} finally {
				closeAfterSend(transport, control, receipt, primaryFailure);
			}
		}
	}

	private static void closeAfterSend(final Transport transport, final MailSendControl control,
			@Nullable final MailSubmissionReceipt receipt, @Nullable final Throwable primaryFailure) throws MessagingException {
		try {
			transport.close();
		} catch (MessagingException | RuntimeException cleanupFailure) {
			if (primaryFailure != null) {
				primaryFailure.addSuppressed(cleanupFailure);
			} else if (receipt != null && receipt.getStatus() == MailSubmissionStatus.ACCEPTED && control.isStopRequested()) {
				LOGGER.debug("SMTP acceptance was observed before the connection was aborted during cleanup", cleanupFailure);
			} else {
				throw new MessagingException("Unable to close the SMTP transport after sending", cleanupFailure);
			}
		}
	}

	public static MailSubmissionReceipt sendMessageOnTransport(@NotNull final Transport transport, @NotNull final Session actualSessionUsed,
			@NotNull final Email email, @NotNull final MailSendControl control)
			throws MessagingException {
		try {
			control.checkStopped();
			final PreparedMail preparedMail = SessionBasedEmailToMimeMessageConverter.convertAndLogPreparedMail(actualSessionUsed, email);
			control.checkStopped();
			final MailTransportResult transportResult = MailTransportAdapterResolver.sendMessage(transport, preparedMail)
					.withEnvelopeRecipients(preparedMail.getRecipients());
			if (!transportResult.isSuccessful()) {
				throw buildSubmissionException(email, transportResult);
			}
			LOGGER.trace("...email sent");
			return buildReceipt(email, transportResult);
		} catch (final MailSubmissionException failure) {
			throw control.translateFailure(failure);
		} catch (final MessagingException failure) {
			throw control.translateFailure(buildSubmissionException(email, MailTransportResult.failed(failure, null)));
		} catch (final RuntimeException failure) {
			throw control.translateFailure(failure);
		}
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
			return new MailSubmissionReceipt(email.getId(), smtpServerResponse, Instant.now());
		}
		return buildReceipt(email, MailTransportResult.unknown(null));
	}

	@NotNull
	private static MailSubmissionReceipt buildReceipt(@NotNull final Email email, @NotNull final MailTransportResult transportResult) {
		final Address[] envelope = asInternetAddresses(email.getOverrideReceivers().isEmpty()
				? email.getRecipients() : email.getOverrideReceivers(), UTF_8).toArray(new Address[0]);
		final MailTransportResult completeResult = transportResult.withEnvelopeRecipients(envelope);
		return new MailSubmissionReceipt(email.getId(), transportResult.getSmtpResponse().orElse(null), Instant.now(),
				completeResult.getStatus(), completeResult.getRecipientResults(), completeResult.getRetryDisposition());
	}

	@NotNull
	private static MailSubmissionException buildSubmissionException(@NotNull final Email email, @NotNull final MailTransportResult transportResult) {
		final MessagingException failure = transportResult.getFailure()
				.orElseThrow(() -> new IllegalArgumentException("A failed transport result must retain its MessagingException"));
		final MailSubmissionReceipt receipt = buildReceipt(email, transportResult);
		final String emailIdentifier = email.getId() != null
				? "ID: '" + email.getId() + "'"
				: "Subject: '" + email.getSubject() + "'";
		return new MailSubmissionException("Failed to submit email [" + emailIdentifier + "], submission status: " + receipt.getStatus(),
				failure, receipt);
	}

	private static <T> T runOnSessionTransport(@NotNull final UUID clusterKey, @NotNull final Session session,
			final boolean stickySession, @NotNull final TransportOperation<T> operation)
			throws MessagingException {
		if (ModuleLoader.batchModuleAvailable()) {
			return sendUsingConnectionPool(ModuleLoader.loadBatchModule(), clusterKey, session, stickySession, null, operation);
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
			@NotNull final Session session, final boolean stickySession, @Nullable final MailSendControl control, @NotNull final TransportOperation<T> operation)
			throws MessagingException {
		final LifecycleDelegatingTransport delegatingTransport = batchModule.acquireTransport(clusterKey, session, stickySession, control);
		final T result;
		try {
			result = operation.run(delegatingTransport.getTransport(), delegatingTransport.getSessionUsedToObtainTransport());
		} catch (final Throwable failure) {
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
