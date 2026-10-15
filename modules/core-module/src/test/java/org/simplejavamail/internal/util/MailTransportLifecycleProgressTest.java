package org.simplejavamail.internal.util;

import jakarta.mail.Transport;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.spi.MailTransportLifecycleAdapter;
import org.simplejavamail.api.mailer.spi.MailTransportLifecycleAdapter.ConnectionProgressRegistration;
import org.simplejavamail.api.mailer.spi.MailTransportProgress;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class MailTransportLifecycleProgressTest {
    private final Transport transport = mock(Transport.class);
    private final MailTransportProgress sink = mock(MailTransportProgress.class);

    @Test
    void unsupportedAdapterLeavesSetupUnobservedWithoutCheckingAbortCapability() {
        final MailTransportLifecycleAdapter adapter = mock(MailTransportLifecycleAdapter.class, CALLS_REAL_METHODS);
        assertThat(MailTransportLifecycleResolver.observeConnectionSetup(transport, sink, Collections.singletonList(adapter))).isNull();
        verify(adapter, never()).createAbortAction(any());
    }

    @Test
    void setupSupportDoesNotRequireAbortOrUploadSupport() {
        final MailTransportLifecycleAdapter adapter = mock(MailTransportLifecycleAdapter.class);
        final ConnectionProgressRegistration registration = mock(ConnectionProgressRegistration.class);
        when(adapter.observeConnectionSetup(transport, sink)).thenReturn(registration);
        assertThat(MailTransportLifecycleResolver.observeConnectionSetup(transport, sink, Collections.singletonList(adapter))).isSameAs(registration);
        verify(adapter, never()).createAbortAction(any());
        verifyNoInteractions(sink, transport);
    }

    @Test
    void registrationAndCleanupFailuresDoNotBecomeConnectionFailures() {
        final MailTransportLifecycleAdapter adapter = mock(MailTransportLifecycleAdapter.class);
        when(adapter.observeConnectionSetup(transport, sink)).thenThrow(new IllegalStateException("private password in provider text"));
        assertThat(MailTransportLifecycleResolver.observeConnectionSetup(transport, sink, Collections.singletonList(adapter))).isNull();
        final ConnectionProgressRegistration registration = mock(ConnectionProgressRegistration.class);
        doThrow(new IllegalStateException("private connection URL")).when(registration).close();
        assertThatCode(() -> MailTransportLifecycleResolver.closeProgressRegistration(registration)).doesNotThrowAnyException();
    }

    @Test
    void ambiguousAndPartiallyInstalledObserversAreUnregistered() {
        final MailTransportLifecycleAdapter first = mock(MailTransportLifecycleAdapter.class);
        final MailTransportLifecycleAdapter second = mock(MailTransportLifecycleAdapter.class);
        final ConnectionProgressRegistration registration = mock(ConnectionProgressRegistration.class);
        final ConnectionProgressRegistration other = mock(ConnectionProgressRegistration.class);
        when(first.observeConnectionSetup(transport, sink)).thenReturn(registration);
        when(second.observeConnectionSetup(transport, sink)).thenReturn(other);
        assertThat(MailTransportLifecycleResolver.observeConnectionSetup(transport, sink, Arrays.asList(first, second))).isNull();
        verify(registration).close();
        verify(other).close();
        when(second.observeConnectionSetup(transport, sink)).thenThrow(new IllegalStateException());
        assertThat(MailTransportLifecycleResolver.observeConnectionSetup(transport, sink, Arrays.asList(first, second))).isNull();
        verify(registration, times(2)).close();
    }
}
