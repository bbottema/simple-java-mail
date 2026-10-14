package testutil.retry;

import jakarta.mail.Message;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

/** Provider-neutral fixture: no sockets, Angus classes or retry-engine access. Every test owns its state through its Session. */
public final class RetryProviderTransport extends Transport {
    public static final String STATE = "test.retry.provider.state";
    public final State state;

    public static final class State {
        public final AtomicInteger invocations = new AtomicInteger();
        public final AtomicInteger captures = new AtomicInteger();
        public final List<byte[]> content = new CopyOnWriteArrayList<>();
        public boolean repeatable = true;
        public int temporaryRefusals = 1;
        public boolean uncertain;
        public boolean partial;
        public boolean permanent;
        public boolean failCleanup;
    }

    public RetryProviderTransport(final Session session, final URLName url) {
        super(session, url);
        state = (State) session.getProperties().get(STATE);
    }

    /** Each test gets a separately owned Session while every connection inside that test records into the same ledger. */
    public static Session session(final State state) throws MessagingException {
        final Properties properties = new Properties();
        properties.put(STATE, state);
        properties.setProperty("mail.transport.protocol", "retry-test");
        properties.setProperty("mail.retry-test.host", "fixture");
        final Session session = Session.getInstance(properties);
        session.setProvider(new Provider(Provider.Type.TRANSPORT, "retry-test", RetryProviderTransport.class.getName(), "fixture", "1"));
        return session;
    }

    @Override protected boolean protocolConnect(final String host, final int port, final String user, final String password) { return true; }
    @Override public void sendMessage(final Message message, final Address[] recipients) { throw new AssertionError("Use the adapter"); }
    @Override public synchronized void close() throws MessagingException {
        setConnected(false);
        if (state.failCleanup) { throw new MessagingException("fixture cleanup failed"); }
    }
}
