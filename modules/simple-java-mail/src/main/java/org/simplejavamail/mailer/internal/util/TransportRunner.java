package org.simplejavamail.mailer.internal.util;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import lombok.val;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.BatchModule;
import org.simplejavamail.internal.util.MiscUtil;
import org.simplejavamail.mailer.internal.SessionBasedEmailToMimeMessageConverter;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
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
	public static MailSubmissionReceipt sendMessage(@NotNull final UUID clusterKey, final Session session, @NotNull Email email)
			throws MessagingException {
		return runOnSessionTransport(clusterKey, session, false, (transport, actualSessionUsed) -> sendMessageOnTransport(transport, actualSessionUsed, email));
	}

	public static MailSubmissionReceipt sendMessageOnTransport(@NotNull final Transport transport, @NotNull final Session actualSessionUsed, @NotNull Email email)
			throws MessagingException {
		val message = SessionBasedEmailToMimeMessageConverter.convertAndLogMimeMessage(actualSessionUsed, email);
		val actualRecipients = email.getOverrideReceivers().isEmpty()
				? message.getAllRecipients()
				: MiscUtil.asInternetAddresses(email.getOverrideReceivers(), UTF_8).toArray(new InternetAddress[0]);
		transport.sendMessage(message, actualRecipients);
		LOGGER.trace("...email sent");
		return buildReceipt(email, transport);
	}

	public static void connect(@NotNull UUID clusterKey, final Session session)
			throws MessagingException {
		runOnSessionTransport(clusterKey, session, true, (transport, actualSessionUsed) -> {
			// the fact that we reached here means a connection was made successfully
			LOGGER.debug("...connection successful");
			return null;
		});
	}

	@NotNull
	public static MailSubmissionReceipt buildReceipt(@NotNull final Email email, @Nullable final Transport transport) {
		return new MailSubmissionReceipt(email.getId(), extractSmtpServerResponse(transport), Instant.now());
	}

	@Nullable
	private static SmtpServerResponse extractSmtpServerResponse(@Nullable final Transport transport) {
		if (transport instanceof SMTPTransport) {
			val smtpTransport = (SMTPTransport) transport;
			return new SmtpServerResponse(smtpTransport.getLastReturnCode(), smtpTransport.getLastServerResponse());
		}
		return null;
	}

	private static <T> T runOnSessionTransport(@NotNull UUID clusterKey, Session session, final boolean stickySession, TransportOperation<T> operation)
			throws MessagingException {
		if (ModuleLoader.batchModuleAvailable()) {
			return sendUsingConnectionPool(ModuleLoader.loadBatchModule(), clusterKey, session, stickySession, operation);
		} else {
			try (Transport transport = session.getTransport()) {
				TransportConnectionHelper.connectTransport(transport, session);
				return operation.run(transport, session);
			} finally {
				LOGGER.trace("closing transport");
			}
		}
	}

	private static <T> T sendUsingConnectionPool(@NotNull BatchModule batchModule, @NotNull UUID clusterKey, Session session, boolean stickySession, TransportOperation<T> operation)
			throws MessagingException {
		LifecycleDelegatingTransport delegatingTransport = batchModule.acquireTransport(clusterKey, session, stickySession);
		try {
			T result = operation.run(delegatingTransport.getTransport(), delegatingTransport.getSessionUsedToObtainTransport());
			delegatingTransport.signalTransportUsed();
			return result;
		} catch (final Throwable t) {
			// always make sure claimed resources are released
			delegatingTransport.signalTransportFailed();
			throw t;
		}
	}

	private interface TransportOperation<T> {
		T run(Transport transport, Session actualSessionUsed)
				throws MessagingException;
	}
}
