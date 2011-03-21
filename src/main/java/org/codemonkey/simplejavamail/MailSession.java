package org.codemonkey.simplejavamail;

import java.util.Properties;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;

/**
 * Creates and initializes a {@link Session} instance based on provided host and credentials as well as a {@link TransportStrategy} instance
 * to apply SMTP, SMTPS or SMTP + TLS protocol semantics.
 * <p>
 * The <code>Session</code> needs to be created before assembling the concerning e-mail message in the {@link Mailer} as the Sun JavaMail
 * API directly wires the e-mail message into the <code>Session</code>. The session again is needed when actually sending the e-mail or when
 * {@link #toString()} is called.
 * 
 * @author Benny Bottema
 */
class MailSession {

	/**
	 * Used to actually send the email. This session can come from being passed in the default constructor, or made by this
	 * <code>MailSession</code> directly, when no <code>Session</code> instance was provided.
	 * 
	 * @see #MailSession(Session)
	 * @see #MailSession(String, int, String, String, TransportStrategy)
	 */
	private final Session session;

	/**
	 * The transport protocol strategy enum that actually handles the session configuration. Session configuration meaning setting the right
	 * properties for the appropriate transport type (ie. <em>"mail.smtp.host"</em> for SMTP, <em>"mail.smtps.host"</em> for SMTPS).
	 */
	private TransportStrategy transportStrategy;

	/**
	 * Default constructor, stores the given mail session for later use. It is assumed all properties are set, such as host, credentials and
	 * transport properties.
	 * 
	 * @param session A preconfigured mail {@link Session} object with which a {@link Message} can be produced.
	 */
	public MailSession(final Session session) {
		this.session = session;
	}

	/**
	 * Overloaded constructor which produces a new {@link Session} on the fly. Use this if you don't have a mail session configured in your
	 * web container, or Spring context etc.
	 * 
	 * @param host The address URL of the SMTP server to be used.
	 * @param port The port of the SMTP server.
	 * @param username An optional username, may be <code>null</code>.
	 * @param password An optional password, may be <code>null</code>, but only if username is <code>null</code> as well.
	 */
	public MailSession(final String host, final int port, final String username, final String password,
			final TransportStrategy transportStrategy) {
		// we're doing this manually instead of using Apache Commons, to avoid another dependency
		if (host == null || "".equals(host.trim())) {
			throw new RuntimeException("Can't send an email without host");
		} else if ((password != null && !"".equals(password.trim())) && (username == null || "".equals(username.trim()))) {
			throw new RuntimeException("Can't have a password without username");
		}
		this.transportStrategy = transportStrategy;
		this.session = createMailSession(host, port, username, password);
	}

	/**
	 * Actually instantiates and configures the {@link Session} instance. Delegates resolving transport protocol specific properties to the
	 * {@link #transportStrategy} in two ways:
	 * <ol>
	 * <li>request an initial property list which the strategy may pre-populate</li>
	 * <li>by requesting the property names according to the respective transport protocol it handles (for the host property name it would
	 * be <em>"mail.smtp.host"</em> for SMTP and <em>"mail.smtps.host"</em> for SMTPS)</li>
	 * </ol>
	 * 
	 * @param host The address URL of the SMTP server to be used.
	 * @param port The port of the SMTP server.
	 * @param username An optional username, may be <code>null</code>.
	 * @param password An optional password, may be <code>null</code>.
	 * @return A fully configured <code>Session</code> instance complete with transport protocol settings.
	 * @see TransportStrategy#generateProperties()
	 * @see TransportStrategy#propertyNameHost()
	 * @see TransportStrategy#propertyNamePort()
	 * @see TransportStrategy#propertyNameUsername()
	 * @see TransportStrategy#propertyNameAuthenticate()
	 */
	public Session createMailSession(final String host, final int port, final String username, final String password) {
		Properties props = transportStrategy.generateProperties();
		props.put(transportStrategy.propertyNameHost(), host);
		props.put(transportStrategy.propertyNamePort(), String.valueOf(port));

		if (username != null) {
			props.put(transportStrategy.propertyNameUsername(), username);
		}

		if (password != null) {
			props.put(transportStrategy.propertyNameAuthenticate(), "true");
			return Session.getInstance(props, new Authenticator() {
				@Override
				protected PasswordAuthentication getPasswordAuthentication() {
					return new PasswordAuthentication(username, password);
				}
			});
		} else {
			return Session.getInstance(props);
		}
	}

	/**
	 * Sends a Sun JavaMail {@link Message} object using the {@link Session#getTransport()}. It will call {@link Transport#connect()}
	 * assuming all connection details have been configured in the provided {@link Session} instance.
	 * <p>
	 * Performs a call to {@link Message#saveChanges()} as the Sun JavaMail API indicates it is needed to configure the message headers and
	 * providing a message id.
	 * 
	 * @param message The message to send. Generate by the {@link Mailer}.
	 * @throws MessagingException Thrown by {@link Message#saveChanges()}, {@link Transport#connect()},
	 *             {@link Transport#sendMessage(Message, javax.mail.Address[])}, {@link Transport#close()}
	 */
	public void sendMessage(Message message)
			throws MessagingException {
		message.saveChanges(); // some headers and id's will be set for this specific message
		Transport transport = session.getTransport();
		transport.connect();
		transport.sendMessage(message, message.getAllRecipients());
		transport.close();
	}

	/**
	 * Returns host details, credentials used and whether authentication will take place and finally the transport protocol used.
	 */
	@Override
	public String toString() {
		final String logmsg = "host: %s, port: %s, username: %s, authenticate: %s, transport: %s";
		return String.format(logmsg, transportStrategy.propertyNameHost(), transportStrategy.propertyNamePort(),
				transportStrategy.propertyNameUsername(), Boolean.parseBoolean(transportStrategy.propertyNameAuthenticate()),
				transportStrategy);
	}

	/**
	 * Bean getter for {@link #session}.
	 */
	public Session getSession() {
		return session;
	}
}