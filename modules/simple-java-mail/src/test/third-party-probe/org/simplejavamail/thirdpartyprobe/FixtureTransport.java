package org.simplejavamail.thirdpartyprobe;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** An independent Jakarta Mail provider with controlled connection facts; it opens no sockets and cannot send mail. */
public final class FixtureTransport extends Transport {
    static final String USER = "fixture-user";
    static final String PASSWORD = "fixture-password";
    static final List<FixtureTransport> CREATED = new CopyOnWriteArrayList<>();

    final Session probeSession;
    int connectionCalls;
    int closeCalls;
    boolean authenticated;
    boolean closed;
    Thread connectionThread;

    /** Jakarta Mail constructs providers reflectively using this exact public constructor. */
    public FixtureTransport(final Session session, final URLName urlName) {
        super(session, urlName);
        probeSession = session;
        CREATED.add(this);
    }

    @Override
    public synchronized void connect() throws MessagingException {
        connectionCalls++;
        super.connect();
    }

    @Override
    protected boolean protocolConnect(final String host, final int port, final String user, final String password) throws MessagingException {
        connectionThread = Thread.currentThread();
        if (Boolean.parseBoolean(session.getProperty("fixture.fail.connect"))) {
            throw new MessagingException("Private provider failure: " + USER + " " + PASSWORD);
        }
        if (Boolean.parseBoolean(session.getProperty("mail.smtp.auth"))) {
            // Returning false first lets Jakarta Mail obtain credentials through the supplied Session authenticator.
            if (password == null) {
                return false;
            }
            if (!USER.equals(user) || !PASSWORD.equals(password)) {
                throw new AssertionError("The probe did not preserve the caller's authenticator");
            }
            authenticated = true;
        }
        return true;
    }

    @Override
    public synchronized void close() throws MessagingException {
        closeCalls++;
        closed = true;
        super.close();
        if (Boolean.parseBoolean(session.getProperty("fixture.fail.close"))) {
            throw new MessagingException("Private cleanup failure: " + PASSWORD);
        }
    }

    @Override
    public void sendMessage(final Message message, final Address[] addresses) {
        throw new AssertionError("A connection probe must never submit a message");
    }
}
