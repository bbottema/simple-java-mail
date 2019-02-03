package org.simplejavamail.api.mailer.internal.mailsender;

import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.AsyncResponse;
import org.simplejavamail.api.mailer.config.OperationalConfig;

import javax.annotation.Nullable;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import java.util.concurrent.Executors;

public interface MailSender {
	/**
	 * Processes an {@link Email} instance into a completely configured {@link Message}.
	 * <p>
	 * Sends the Sun JavaMail {@link Message} object using {@link Session#getTransport()}. It will call {@link Transport#connect()} assuming all
	 * connection details have been configured in the provided {@link Session} instance and finally {@link Transport#sendMessage(Message,
	 * javax.mail.Address[])}.
	 * <p>
	 * Performs a call to {@link Message#saveChanges()} as the Sun JavaMail API indicates it is needed to configure the message headers and providing
	 * a message id.
	 * <p>
	 * If the email should be sent asynchrounously - perhaps as part of a batch, then a new thread is started using the <em>executor</em> for
	 * threadpooling.
	 * <p>
	 * If the email should go through an authenticated proxy server, then the SOCKS proxy bridge is started if not already running. When the last
	 * email in a batch has finished, the proxy bridging server is shut down.
	 *
	 * @param email The information for the email to be sent.
	 * @param async If false, this method blocks until the mail has been processed completely by the SMTP server. If true, a new thread is started to
	 *              send the email and this method returns immediately.
	 * @return A {@link AsyncResponse} or null if not <em>async</em>.
	 * @throws MailException Can be thrown if an email isn't validating correctly, or some other problem occurs during connection, sending etc.
	 * @see Executors#newFixedThreadPool(int)
	 */
	@Nullable
	AsyncResponse send(Email email, boolean async);
	
	/**
	 * Tries to connect to the configured SMTP server, including (authenticated) proxy if set up.
	 * <p>
	 * Note: synchronizes on this mailer instance, so that we don't get into race condition conflicts with emails actually being sent.
	 *
	 * @return An AsyncResponse in case of async == true, otherwise <code>null</code>.
	 */
	@Nullable
	AsyncResponse testConnection(boolean async);
	
	/**
	 * For emergencies, when a client really wants access to the internally created {@link Session} instance.
	 */
	Session getSession();
	
	OperationalConfig getOperationalConfig();
}
