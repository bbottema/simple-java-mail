package org.simplejavamail.internal.util;

import org.simplejavamail.api.mailer.MailFailureKind;

import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.spi.MailTransportLifecycleAdapter;
import org.simplejavamail.api.mailer.spi.MailTransportLifecycleAdapter.ConnectionProgressRegistration;
import org.simplejavamail.api.mailer.spi.MailTransportProgress;
import org.simplejavamail.api.internal.authenticatedsockssupport.common.AuthenticatedSocksSocketProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.simplejavamail.internal.util.concurrent.MailSendControl;

import java.util.Optional;
import java.util.Properties;
import java.util.Objects;

/** Selects optional setup observation and connection-abort integration for the Mailer and its pool. Never connects a transport. */
public final class MailTransportLifecycleResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(MailTransportLifecycleResolver.class);

    private MailTransportLifecycleResolver() {
    }

    /** Optional reporting cannot change connection compatibility. Abort discovery and its mandatory-timeout checks remain independent. */
    @Nullable
    public static ConnectionProgressRegistration observeConnectionSetup(final Transport transport, final MailTransportProgress progress) {
        try {
            return observeConnectionSetup(transport, progress, MailProviderDiscovery.newProviders(MailTransportLifecycleAdapter.class));
        } catch (RuntimeException failure) {
            warnProgressFailure("discovery", failure);
            return null;
        }
    }

    @Nullable
    static ConnectionProgressRegistration observeConnectionSetup(final Transport transport, final MailTransportProgress progress,
            final Iterable<MailTransportLifecycleAdapter> adapters) {
        ConnectionProgressRegistration selected = null;
        try {
            for (final MailTransportLifecycleAdapter adapter : adapters) {
                final ConnectionProgressRegistration candidate = adapter.observeConnectionSetup(transport, progress);
                if (candidate != null) {
                    if (selected != null) {
                        closeProgressRegistration(candidate);
                        closeProgressRegistration(selected);
                        LOGGER.warn("Multiple connection-progress adapters matched; continuing without setup detail");
                        return null;
                    }
                    selected = candidate;
                }
            }
            return selected;
        } catch (RuntimeException failure) {
            closeProgressRegistration(selected);
            warnProgressFailure("registration", failure);
            return null;
        }
    }

    /** Optional reporting cleanup must not replace a successful send or its original exception. */
    public static void closeProgressRegistration(@Nullable final ConnectionProgressRegistration registration) {
        if (registration != null) {
            try {
                registration.close();
            } catch (RuntimeException failure) {
                warnProgressFailure("cleanup", failure);
            }
        }
    }

    private static void warnProgressFailure(final String operation, final RuntimeException failure) {
        // Provider exception messages can contain connection URLs or credentials. Keep only the exception class.
        LOGGER.warn("Connection-progress {} failed ({}); leaving the connection result unchanged", operation, failure.getClass().getName());
    }

    /** Configure only a Session just created by the Mailer, before any consumer can start using it. */
    public static void configureOwnedSession(@NotNull final Session session) throws MessagingException {
        final String protocol = Optional.ofNullable(session.getProperty("mail.transport.protocol")).orElse("smtp");
        final Provider provider;
        try {
            provider = session.getProvider(protocol);
        } catch (NoSuchProviderException absentProvider) {
            // Building a Mailer for validation/rehearsal does not require a transport provider.
            return;
        }
        MailTransportLifecycleAdapter selected = null;
        for (final MailTransportLifecycleAdapter candidate : MailProviderDiscovery.newProviders(MailTransportLifecycleAdapter.class)) {
            if (candidate.supportsProvider(provider)) {
                if (selected != null) {
                    throw conflictingAdapters(provider.getClassName(), selected, candidate);
                }
                selected = candidate;
            }
        }
        if (selected != null) {
            selected.configureOwnedSession(session, protocol);
        }
    }

    /** Selects the proxy capability using the same provider discovery as owned-Session configuration. Never connects. */
    public static boolean supportsDirectAuthenticatedSocks(final Session session) {
        return supportsDirectAuthenticatedSocks(session, session.getProperties());
    }

    /** Uses the caller's provider registry with final property declarations, without temporarily modifying that Session. */
    public static boolean supportsDirectAuthenticatedSocks(final Session session, final Properties effectiveProperties) {
        return authenticatedSocksConfiguration(session, effectiveProperties) != null;
    }

    /** The route was selected during preflight; losing its capability is an error, never permission to retry another route. */
    public static void configureAuthenticatedSocks(final Session session, final AuthenticatedSocksSocketProvider provider) {
        final AuthenticatedSocksSessionConfiguration capability = authenticatedSocksConfiguration(session, session.getProperties());
        if (capability == null) {
            throw new ConfigurationFailure("The selected SMTP provider no longer supports the authenticated SOCKS transport route. "
                    + "Keep the Session's provider and Angus dependencies unchanged while building and using this Mailer.", null);
        }
        capability.configureAuthenticatedSocks(session, Optional.ofNullable(session.getProperty("mail.transport.protocol")).orElse("smtp"), provider);
    }

    @Nullable
    private static AuthenticatedSocksSessionConfiguration authenticatedSocksConfiguration(final Session session, final Properties effective) {
        final Provider provider = selectedProvider(session, effective);
        if (provider == null) {
            return null;
        }
        MailTransportLifecycleAdapter selected = null;
        for (final MailTransportLifecycleAdapter candidate : MailProviderDiscovery.newProviders(MailTransportLifecycleAdapter.class)) {
            if (candidate.supportsProvider(provider)) {
                if (selected != null) {
                    throw conflictingAdapters(provider.getClassName(), selected, candidate);
                }
                selected = candidate;
            }
        }
        if (selected instanceof AuthenticatedSocksSessionConfiguration) {
            final AuthenticatedSocksSessionConfiguration capability = (AuthenticatedSocksSessionConfiguration) selected;
            return capability.supportsDirectAuthenticatedSocks(provider) ? capability : null;
        }
        return null;
    }

    @Nullable
    private static Provider selectedProvider(final Session session, final Properties effective) {
        final String protocol = Optional.ofNullable(effective.getProperty("mail.transport.protocol")).orElse("smtp");
        if (!protocol.equals("smtp") && !protocol.equals("smtps")) {
            return null;
        }
        final String classKey = "mail." + protocol + ".class";
        final String className = effective.getProperty(classKey);
        if (!Objects.equals(className, session.getProperty(classKey))) {
            // An unknown class or removal could expose a different protocol-map entry hidden behind the current property.
            // Conservatively retain legacy routing rather than reconstructing Session's private provider maps.
            if (className == null) {
                return null;
            }
            Provider declared = null;
            for (final Provider candidate : session.getProviders()) {
                if (candidate.getClassName().equals(className)) {
                    if (candidate.getType() != Provider.Type.TRANSPORT || !candidate.getProtocol().equals(protocol)) {
                        return null;
                    }
                    declared = candidate;
                }
            }
            return declared;
        }
        final Provider provider;
        try {
            provider = session.getProvider(protocol);
        } catch (NoSuchProviderException absentProvider) {
            return null;
        }
        return provider.getType() == Provider.Type.TRANSPORT && provider.getProtocol().equals(protocol) ? provider : null;
    }

    /** Returns the selected transport's abort capability, without acquiring any network resources. */
    @NotNull
    public static Optional<Runnable> findAbortAction(@NotNull final Transport transport) {
        Optional<Runnable> selected = Optional.empty();
        MailTransportLifecycleAdapter selectedAdapter = null;
        for (final MailTransportLifecycleAdapter candidate : MailProviderDiscovery.newProviders(MailTransportLifecycleAdapter.class)) {
            final Optional<Runnable> action = candidate.createAbortAction(transport);
            if (action.isPresent()) {
                if (selectedAdapter != null) {
                    throw conflictingAdapters(transport.getClass().getName(), selectedAdapter, candidate);
                }
                selectedAdapter = candidate;
                selected = action;
            }
        }
        return selected;
    }

    /** Bind only while this operation owns the transport; close the registration before a healthy connection is reused. */
    public static MailSendControl.Registration registerAbort(final Transport transport, final MailSendControl control) {
        final Optional<Runnable> abort = findAbortAction(transport);
        if (control.isDeadlineEnabled() && !abort.isPresent()) {
            throw unsupportedTimeout(transport);
        }
        return control.onStop(abort.orElse(() -> { }));
    }

    /** Fail before connection establishment when a configured total deadline cannot abort this provider's I/O. */
    public static void requireAbortSupport(@NotNull final Session session) {
        try (Transport transport = session.getTransport()) {
            if (!findAbortAction(transport).isPresent()) {
                throw unsupportedTimeout(transport);
            }
        } catch (MessagingException failure) {
            throw new ConfigurationFailure("Couldn't create or close the Session's transport while checking the total send timeout. "
                    + "Check the cause, your Jakarta Mail provider dependency, and the Session's mail.transport.protocol setting.", failure);
        }
    }

    private static ConfigurationFailure conflictingAdapters(final String transportClassName,
            final MailTransportLifecycleAdapter first, final MailTransportLifecycleAdapter second) {
        return new ConfigurationFailure("Two MailTransportLifecycleAdapter implementations match transport '" + transportClassName
                + "': '" + first.getClass().getName() + "' and '" + second.getClass().getName()
                + "'. Keep only one matching adapter in your dependencies or service registrations.", null);
    }

    private static ConfigurationFailure unsupportedTimeout(final Transport transport) {
        return new ConfigurationFailure("Don't configure a total send timeout with transport '" + transport.getClass().getName()
                + "': Simple Java Mail can't stop its network calls. Remove the total timeout from your builder or configuration "
                + "and use the provider's own timeouts instead. If you can't change the configuration that sets it, "
                + "call resetMailSendTimeout() on the builder to disable it.", null);
    }

    private static final class ConfigurationFailure extends org.simplejavamail.MailException {
        private static final long serialVersionUID = 1L;
        private ConfigurationFailure(final String message, final Throwable cause) {
            super(MailFailureKind.CONFIGURATION, message, cause, null);
        }
    }
}
