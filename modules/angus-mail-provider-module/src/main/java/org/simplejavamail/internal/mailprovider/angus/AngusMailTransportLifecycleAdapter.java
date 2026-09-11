package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.spi.MailTransportLifecycleAdapter;

import java.util.Optional;
import java.util.Properties;

/** Installs the managed Angus transport on SJM-owned Sessions without replacing application socket factories. */
public final class AngusMailTransportLifecycleAdapter implements MailTransportLifecycleAdapter {

    @Override
    public boolean supportsProvider(@NotNull final Provider provider) {
        return provider.getClassName().equals("org.eclipse.angus.mail.smtp.SMTPTransport")
                || provider.getClassName().equals("org.eclipse.angus.mail.smtp.SMTPSSLTransport");
    }

    @Override
    public void configureOwnedSession(@NotNull final Session session, @NotNull final String protocol) {
        final String prefix = "mail." + protocol;
        final Properties properties = session.getProperties();
        if (hasCustomSocketFactory(properties, prefix)) {
            return;
        }
        properties.put(prefix + ".socketFactory", new AngusSocketFactory(properties, prefix));
        // An aborted/failed connection must not cause SocketFetcher to retry with an untracked socket.
        properties.setProperty(prefix + ".socketFactory.fallback", "false");
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

    private static boolean hasCustomSocketFactory(final Properties properties, final String prefix) {
        return properties.get(prefix + ".socketFactory") != null || properties.getProperty(prefix + ".socketFactory.class") != null
                || properties.get(prefix + ".ssl.socketFactory") != null || properties.getProperty(prefix + ".ssl.socketFactory.class") != null;
    }

    @Override
    @NotNull
    public Optional<Runnable> createAbortAction(@NotNull final Transport transport) {
        if (transport instanceof ManagedAngusTransport && ((ManagedAngusTransport) transport).hasTrackedSocketConfiguration()) {
            return Optional.of(((ManagedAngusTransport) transport)::abortConnection);
        }
        return Optional.empty();
    }
}
