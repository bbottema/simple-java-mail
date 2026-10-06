package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.spi.MailTransportLifecycleAdapter;

import java.util.Optional;
import java.util.Properties;

/** Installs owned Angus resources and protocol handling; application factories retain their TLS decisions. */
public final class AngusMailTransportLifecycleAdapter implements MailTransportLifecycleAdapter {

    /** @see MailTransportLifecycleAdapter#supportsProvider(Provider) */
    @Override
    public boolean supportsProvider(@NotNull final Provider provider) {
        return provider.getClassName().equals("org.eclipse.angus.mail.smtp.SMTPTransport")
                || provider.getClassName().equals("org.eclipse.angus.mail.smtp.SMTPSSLTransport");
    }

    /** @see MailTransportLifecycleAdapter#configureOwnedSession(Session, String) */
    @Override
    public void configureOwnedSession(@NotNull final Session session, @NotNull final String protocol) {
        final String prefix = "mail." + protocol;
        final Properties properties = session.getProperties();
        // Initialize once, before MIME or transports exist. Protocol handling does not require ownership of the application's socket factory.
        // containsKey preserves explicit Boolean values; getProperty also sees inherited defaults.
        if (!properties.containsKey("mail.mime.allowutf8") && properties.getProperty("mail.mime.allowutf8") == null) {
            properties.setProperty("mail.mime.allowutf8", "true");
        }
        AngusSocketFactories.configure(properties, prefix);
        AngusWriteTimeoutScheduler.configure(properties, prefix);
        final Provider provider = new Provider(Provider.Type.TRANSPORT, protocol,
                ManagedAngusTransport.class.getName(), "Simple Java Mail", null);
        session.addProvider(provider);
        try {
            session.setProvider(provider);
        } catch (NoSuchProviderException failure) {
            throw new IllegalStateException("Couldn't select transport '" + provider.getClassName() + "' for protocol '" + protocol
                    + "' after registering it. Please report this with the cause and your Simple Java Mail, Jakarta Mail and Angus versions.", failure);
        }
    }

    /** @see MailTransportLifecycleAdapter#createAbortAction(Transport) */
    @Override
    @NotNull
    public Optional<Runnable> createAbortAction(@NotNull final Transport transport) {
        if (transport instanceof ManagedAngusTransport && ((ManagedAngusTransport) transport).hasTrackedSocketConfiguration()) {
            return Optional.of(((ManagedAngusTransport) transport)::abortConnection);
        }
        return Optional.empty();
    }
}
