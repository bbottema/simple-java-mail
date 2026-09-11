package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AngusMailTransportLifecycleAdapterTest {

    @Test
    void failedProviderSelectionIdentifiesTheTransportAndProtocolAndRetainsTheCause() throws Exception {
        final Session session = mock(Session.class);
        final NoSuchProviderException failure = new NoSuchProviderException("provider selection failed");
        when(session.getProperties()).thenReturn(new Properties());
        doThrow(failure).when(session).setProvider(any(Provider.class));

        assertThatThrownBy(() -> new AngusMailTransportLifecycleAdapter().configureOwnedSession(session, "smtp"))
                .isInstanceOf(IllegalStateException.class).hasCause(failure)
                .hasMessageContaining(ManagedAngusTransport.class.getName())
                .hasMessageContaining("protocol 'smtp' after registering it")
                .hasMessageContaining("Please report this with the cause and your Simple Java Mail, Jakarta Mail and Angus versions");
        verify(session).addProvider(any(Provider.class));
    }
}
