package org.simplejavamail.thirdpartyprobe;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendConnectionStep;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.FailoverPolicy;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises connection-only failover with a third-party transport, without Angus or the optional pool module. */
public final class ConnectionFailoverConsumer {

    private static final String ATTEMPTS_PROPERTY = "test.attempts";

    /** Real orchestration with a refusing provider; neither Angus, the batch module nor any network access is present. */
    static void verify(final SimpleJavaMail simpleJavaMail, final Email email) throws Exception {
        final AtomicInteger connectionAttempts = new AtomicInteger();
        final List<MailSendOutcome> outcomes = new ArrayList<>();
        try (Mailer failoverMailer = simpleJavaMail.mailerBuilder(refusingSession(connectionAttempts)).buildMailer();
             Mailer initiatingMailer = simpleJavaMail.mailerBuilder(refusingSession(connectionAttempts))
                     .withFailoverMailer(failoverMailer, FailoverPolicy.builder().build())
                     .withMailSendResultObserver(outcomes::add)
                     .buildMailer()) {
            try {
                initiatingMailer.sync().sendMail(email);
                throw new AssertionError("Both destinations refuse their connection");
            } catch (RuntimeException expected) {
                if (!expected.getMessage().contains("No configured SMTP destination")) {
                    throw new AssertionError("Expected connection exhaustion, not a provider/linkage failure", expected);
                }
            }
            final List<MailSendConnectionStep> history = outcomes.get(0).getDiagnostics().orElseThrow().getConnectionHistory();
            if (connectionAttempts.get() != 2 || history.size() != 2 || outcomes.get(0).getSubmissionReceipt().isPresent()
                    || history.stream().anyMatch(step -> !step.isConnectionAttempted() || step.isConnectionAcquired())) {
                throw new AssertionError("Connection-only failover must retain two failed acquisitions without inventing a receipt");
            }
        }
    }

    private static Session refusingSession(final AtomicInteger connectionAttempts) throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.host", "localhost");
        properties.put(ATTEMPTS_PROPERTY, connectionAttempts);
        final Session session = Session.getInstance(properties);
        session.setProvider(new Provider(Provider.Type.TRANSPORT, "smtp", RefusingTransport.class.getName(), "fixture", "1"));
        return session;
    }

    public static final class RefusingTransport extends Transport {
        private final AtomicInteger attempts;

        public RefusingTransport(final Session session, final URLName name) {
            super(session, name);
            attempts = (AtomicInteger) session.getProperties().get(ATTEMPTS_PROPERTY);
        }

        @Override
        protected boolean protocolConnect(final String host, final int port, final String user, final String password) throws MessagingException {
            attempts.incrementAndGet();
            throw new MessagingException("Fixture connection refused", new ConnectException("Fixture connection refused"));
        }

        @Override
        public void sendMessage(final Message message, final Address[] recipients) {
            throw new AssertionError("Submission must not be reached");
        }
    }
}
