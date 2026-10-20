package org.simplejavamail.mailer.failover;

import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.api.mailer.spi.MailTransportAdapter;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/** Fault injection at the supported Jakarta Mail/provider SPI, never inside SJM's routing or lease implementation. */
public final class FailoverTestProvider implements MailTransportAdapter {

    static MailerFromSessionBuilder<?> builder(final State state) throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.host", "contract.example.test");
        properties.setProperty("mail.smtp.port", "25");
        properties.put("test.provider.state", state);
        final Session session = Session.getInstance(properties);
        session.setProvider(new Provider(Provider.Type.TRANSPORT, "smtp", ContractTransport.class.getName(), "test", "1"));
        return FailoverContractSupport.factory().mailerBuilder(session).withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1).build());
    }

    @Override
    public boolean supports(final Transport transport) {
        return transport instanceof ContractTransport;
    }

    @Override
    public MailTransportResult sendMessage(final Transport transport, final PreparedMail mail) {
        try {
            transport.sendMessage(mail.getMimeMessage(), mail.getRecipients());
            return MailTransportResult.accepted(mail.getRecipients(), new SmtpServerResponse(250, "250 accepted"));
        } catch (MessagingException failure) {
            return MailTransportResult.failed(failure, null);
        }
    }

    static final class State {
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger invocations = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        volatile MessagingException connectionFailure;
        volatile MessagingException submissionFailure;
        volatile MessagingException cleanupFailure;
        volatile Runnable beforeClose = () -> { };
    }

    public static final class ContractTransport extends Transport {
        private final State state;

        public ContractTransport(final Session session, final URLName name) {
            super(session, name);
            state = (State) session.getProperties().get("test.provider.state");
        }

        @Override
        protected boolean protocolConnect(final String host, final int port, final String user, final String password) throws MessagingException {
            state.connections.incrementAndGet();
            if (state.connectionFailure != null) {
                throw state.connectionFailure;
            }
            return true;
        }

        @Override
        public void sendMessage(final Message message, final Address[] recipients) throws MessagingException {
            state.invocations.incrementAndGet();
            if (state.submissionFailure != null) {
                throw state.submissionFailure;
            }
        }

        @Override
        public synchronized void close() throws MessagingException {
            super.close();
            state.closes.incrementAndGet();
            state.beforeClose.run();
            if (state.cleanupFailure != null) {
                throw state.cleanupFailure;
            }
        }
    }
}
