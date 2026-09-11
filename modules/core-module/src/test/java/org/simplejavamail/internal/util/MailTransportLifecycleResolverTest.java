package org.simplejavamail.internal.util;

import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.simplejavamail.api.mailer.spi.MailTransportLifecycleAdapter;
import org.simplejavamail.internal.util.concurrent.MailSendControl;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MailTransportLifecycleResolverTest {

    @Test
    void conflictingProviderAdaptersIdentifyBothImplementations() throws Exception {
        final Session session = mock(Session.class);
        final Provider provider = new Provider(Provider.Type.TRANSPORT, "smtp", "example.SmtpTransport", "test", null);
        final MailTransportLifecycleAdapter first = mock(FirstAdapter.class);
        final MailTransportLifecycleAdapter second = mock(SecondAdapter.class);
        when(session.getProvider("smtp")).thenReturn(provider);
        when(first.supportsProvider(provider)).thenReturn(true);
        when(second.supportsProvider(provider)).thenReturn(true);

        final Throwable failure = catchThrowable(() -> {
            try (MockedStatic<ServiceLoader> ignored = useAdapters(first, second)) {
                MailTransportLifecycleResolver.configureOwnedSession(session);
            }
        });
        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(provider.getClassName())
                .hasMessageContaining(first.getClass().getName())
                .hasMessageContaining(second.getClass().getName())
                .hasMessageContaining("Keep only one matching adapter in your dependencies or service registrations");
        verify(first, never()).configureOwnedSession(any(), anyString());
        verify(second, never()).configureOwnedSession(any(), anyString());
    }

    @Test
    void conflictingAbortAdaptersIdentifyBothImplementationsWithoutAborting() {
        final Transport transport = mock(Transport.class);
        final MailTransportLifecycleAdapter first = mock(FirstAdapter.class);
        final MailTransportLifecycleAdapter second = mock(SecondAdapter.class);
        final Runnable firstAbort = mock(Runnable.class);
        final Runnable secondAbort = mock(Runnable.class);
        when(first.createAbortAction(transport)).thenReturn(Optional.of(firstAbort));
        when(second.createAbortAction(transport)).thenReturn(Optional.of(secondAbort));

        final Throwable failure = catchThrowable(() -> {
            try (MockedStatic<ServiceLoader> ignored = useAdapters(first, second)) {
                MailTransportLifecycleResolver.findAbortAction(transport);
            }
        });
        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(transport.getClass().getName())
                .hasMessageContaining(first.getClass().getName())
                .hasMessageContaining(second.getClass().getName())
                .hasMessageContaining("Keep only one matching adapter");
        verifyNoInteractions(firstAbort, secondAbort);
    }

    @Test
    void oneMatchingAdapterStillReturnsItsExactAbortAction() {
        final Transport transport = mock(Transport.class);
        final MailTransportLifecycleAdapter unsupported = mock(FirstAdapter.class);
        final MailTransportLifecycleAdapter supported = mock(SecondAdapter.class);
        final Runnable abort = mock(Runnable.class);
        when(unsupported.createAbortAction(transport)).thenReturn(Optional.empty());
        when(supported.createAbortAction(transport)).thenReturn(Optional.of(abort));

        final Optional<Runnable> selected;
        try (MockedStatic<ServiceLoader> ignored = useAdapters(unsupported, supported)) {
            selected = MailTransportLifecycleResolver.findAbortAction(transport);
        }
        assertThat(selected).containsSame(abort);
        verifyNoInteractions(abort);
    }

    @Test
    void unsupportedTimeoutMessagesAgreeAndPresentResetAsTheFallback() throws Exception {
        final Session session = mock(Session.class);
        final Transport transport = mock(Transport.class);
        final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
        when(session.getTransport()).thenReturn(transport);
        try (MailSendControl control = new MailSendControl(Duration.ofMinutes(1), watcher)) {
            final Throwable validationFailure = catchThrowable(() -> {
                try (MockedStatic<ServiceLoader> ignored = useAdapters()) {
                    MailTransportLifecycleResolver.requireAbortSupport(session);
                }
            });
            final Throwable registrationFailure = catchThrowable(() -> {
                try (MockedStatic<ServiceLoader> ignored = useAdapters()) {
                    MailTransportLifecycleResolver.registerAbort(transport, control);
                }
            });

            assertThat(validationFailure).isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("Don't configure a total send timeout with transport '")
                    .hasMessageContaining(transport.getClass().getName())
                    .hasMessageContaining("Simple Java Mail can't stop its network calls")
                    .hasMessageContaining("Remove the total timeout from your builder or configuration")
                    .hasMessageContaining("If you can't change the configuration that sets it, call resetMailSendTimeout()");
            assertThat(registrationFailure).isInstanceOf(IllegalStateException.class).hasMessage(validationFailure.getMessage());
        } finally {
            watcher.shutdownNow();
        }
    }

    @Test
    void transportLookupFailureRetainsItsCauseAndPointsToProviderConfiguration() throws Exception {
        final Session session = mock(Session.class);
        final NoSuchProviderException failure = new NoSuchProviderException("missing provider");
        when(session.getTransport()).thenThrow(failure);

        assertThatThrownBy(() -> MailTransportLifecycleResolver.requireAbortSupport(session))
                .isInstanceOf(IllegalStateException.class).hasCause(failure)
                .hasMessageContaining("Couldn't create or close the Session's transport")
                .hasMessageContaining("Jakarta Mail provider dependency")
                .hasMessageContaining("mail.transport.protocol");
    }

    @Test
    void inspectionCleanupFailureIsNotMisreportedAsTransportCreationFailure() throws Exception {
        final Session session = mock(Session.class);
        final Transport transport = mock(Transport.class);
        final MailTransportLifecycleAdapter adapter = mock(FirstAdapter.class);
        final MessagingException failure = new MessagingException("close failed");
        when(session.getTransport()).thenReturn(transport);
        when(adapter.createAbortAction(transport)).thenReturn(Optional.of(() -> { }));
        doThrow(failure).when(transport).close();

        final Throwable reported = catchThrowable(() -> {
            try (MockedStatic<ServiceLoader> ignored = useAdapters(adapter)) {
                MailTransportLifecycleResolver.requireAbortSupport(session);
            }
        });
        assertThat(reported).isInstanceOf(IllegalStateException.class).hasCause(failure)
                .hasMessageContaining("Couldn't create or close the Session's transport");
    }

    @SuppressWarnings("unchecked")
    private static MockedStatic<ServiceLoader> useAdapters(final MailTransportLifecycleAdapter... adapters) {
        final ServiceLoader<MailTransportLifecycleAdapter> loader = mock(ServiceLoader.class);
        when(loader.iterator()).thenAnswer(ignored -> Arrays.asList(adapters).iterator());
        final MockedStatic<ServiceLoader> services = mockStatic(ServiceLoader.class);
        services.when(() -> ServiceLoader.load(MailTransportLifecycleAdapter.class)).thenReturn(loader);
        return services;
    }

    private interface FirstAdapter extends MailTransportLifecycleAdapter { }

    private interface SecondAdapter extends MailTransportLifecycleAdapter { }
}
