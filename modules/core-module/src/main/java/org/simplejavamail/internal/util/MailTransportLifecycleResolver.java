package org.simplejavamail.internal.util;

import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.spi.MailTransportLifecycleAdapter;
import org.simplejavamail.internal.util.concurrent.MailSendControl;

import java.util.Optional;
import java.util.ServiceLoader;

/** Selects the optional connection-abort integration shared by the Mailer and its pool module. Never connects a transport. */
public final class MailTransportLifecycleResolver {

    private MailTransportLifecycleResolver() {
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
        for (final MailTransportLifecycleAdapter candidate : ServiceLoader.load(MailTransportLifecycleAdapter.class)) {
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

    /** Returns the selected transport's abort capability, without acquiring any network resources. */
    @NotNull
    public static Optional<Runnable> findAbortAction(@NotNull final Transport transport) {
        Optional<Runnable> selected = Optional.empty();
        MailTransportLifecycleAdapter selectedAdapter = null;
        for (final MailTransportLifecycleAdapter candidate : ServiceLoader.load(MailTransportLifecycleAdapter.class)) {
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
            throw new IllegalStateException("Couldn't create or close the Session's transport while checking the total send timeout. "
                    + "Check the cause, your Jakarta Mail provider dependency, and the Session's mail.transport.protocol setting.", failure);
        }
    }

    private static IllegalStateException conflictingAdapters(final String transportClassName,
            final MailTransportLifecycleAdapter first, final MailTransportLifecycleAdapter second) {
        return new IllegalStateException("Two MailTransportLifecycleAdapter implementations match transport '" + transportClassName
                + "': '" + first.getClass().getName() + "' and '" + second.getClass().getName()
                + "'. Keep only one matching adapter in your dependencies or service registrations.");
    }

    private static IllegalStateException unsupportedTimeout(final Transport transport) {
        return new IllegalStateException("Don't configure a total send timeout with transport '" + transport.getClass().getName()
                + "': Simple Java Mail can't stop its network calls. Remove the total timeout from your builder or configuration "
                + "and use the provider's own timeouts instead. If you can't change the configuration that sets it, "
                + "call resetMailSendTimeout() on the builder to disable it.");
    }
}
