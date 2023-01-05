package org.simplejavamail.mailer.internal.util;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import lombok.val;
import org.simplejavamail.api.mailer.config.TransportStrategy;

/**
 * @see #connectTransport(Transport, Session)
 */
public class TransportConnectionHelper {

    /**
     * To connect using OAuth2 authentication, we need to connect slightly differently as we can't use only Session properties and the traditional Authenticator class for
     * providing password. Instead, <em>mail.smtp.auth.mechanisms</em> is set to {@code "XOAUTH2"} and the built-in OAuth2 Security Provider should take over and use the
     * token as the password, and that is only possible using the alternative {@link Transport#connect(String, String)}.
     *
     * @see <a href="https://javaee.github.io/javamail/OAuth2">https://javaee.github.io/javamail/OAuth2</a>
     */
    public static void connectTransport(Transport transport, Session session) throws MessagingException {
        if (session.getProperties().containsKey(TransportStrategy.OAUTH2_TOKEN_PROPERTY)) {
            val username = session.getProperties().getProperty(TransportStrategy.SMTP_TLS.propertyNameUsername());
            val oauth2Token = session.getProperties().getProperty(TransportStrategy.OAUTH2_TOKEN_PROPERTY);
            transport.connect(username, oauth2Token);
        } else {
            transport.connect();
        }
    }
}