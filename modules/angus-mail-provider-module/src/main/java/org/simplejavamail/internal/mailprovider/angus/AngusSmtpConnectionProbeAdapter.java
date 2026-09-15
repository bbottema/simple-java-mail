package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.SmtpConnectionReport;
import org.simplejavamail.api.mailer.spi.SmtpConnectionProbeAdapter;

/** Uses a dedicated Angus subclass to observe connection setup without changing the transport used for sending. */
public final class AngusSmtpConnectionProbeAdapter implements SmtpConnectionProbeAdapter {
    /** @see SmtpConnectionProbeAdapter#supportsProvider(Provider) */
    @Override
    public boolean supportsProvider(@NotNull final Provider provider) {
        return provider.getType() == Provider.Type.TRANSPORT && (provider.getProtocol().equals("smtp") || provider.getProtocol().equals("smtps"))
                && (provider.getClassName().equals("org.eclipse.angus.mail.smtp.SMTPTransport")
                || provider.getClassName().equals("org.eclipse.angus.mail.smtp.SMTPSSLTransport")
                || provider.getClassName().equals(ManagedAngusTransport.class.getName()));
    }

    /** @see SmtpConnectionProbeAdapter#probe(Session, boolean, Connector) */
    @Override
    @NotNull
    public SmtpConnectionReport probe(@NotNull final Session session, final boolean authenticate, @NotNull final Connector connector) {
        final String protocol = session.getProperty("mail.transport.protocol") == null ? "smtp" : session.getProperty("mail.transport.protocol");
        // The send factory refers to its original Session. Rebind only our own factory to the probe's private properties,
        // including any ephemeral authenticated-proxy port; application factories remain exactly as supplied.
        if (session.getProperties().get("mail." + protocol + ".socketFactory") instanceof AngusSocketFactory) {
            session.getProperties().put("mail." + protocol + ".socketFactory", new AngusSocketFactory(session.getProperties(), "mail." + protocol));
        }
        final AngusProbeTransport transport = new AngusProbeTransport(session, protocol, authenticate);
        try {
            connector.connect(transport);
            transport.recordConnected();
        } catch (MessagingException | RuntimeException failure) {
            transport.recordFailure(failure);
        } finally {
            transport.closeProbe();
        }
        return transport.report();
    }
}
