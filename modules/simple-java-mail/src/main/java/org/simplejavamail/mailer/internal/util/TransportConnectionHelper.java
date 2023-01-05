package org.simplejavamail.mailer.internal.util;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP_TLS;

/**
 * @see #connectTransport(Transport, Session)
 */
public class TransportConnectionHelper {

    /**
     * To connect using OAuth2 authentication, we need to connect slightly differently as we can't use only Session properties and the traditional Authenticator class for
     * providing password. Instead, <em>mail.smtp.auth</em> is set to {@code false} and the OAuth2 authenticator should take over, but this is only triggered succesfully if we
     * provide an empty non-null password, which is only possible using the alternative {@link Transport#connect(String, String)}.
     */
    public static void connectTransport(Transport transport, Session session) throws MessagingException {
        if (session.getProperties().containsKey(TransportStrategy.OAUTH2_TOKEN_PROPERTY)) {
            transport.connect(session.getProperties().getProperty(SMTP_TLS.propertyNameUsername()), "");
        } else {
            transport.connect();
        }
    }
}