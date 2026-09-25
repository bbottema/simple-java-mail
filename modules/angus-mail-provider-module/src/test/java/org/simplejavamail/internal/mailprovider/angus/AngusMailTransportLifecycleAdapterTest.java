package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import javax.net.SocketFactory;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AngusMailTransportLifecycleAdapterTest {

    @Test
    void customFactoryKeepsItsOwnershipWhileProtocolHandlingAndUtf8AreInstalled() throws Exception {
        final Properties properties = new Properties();
        final SocketFactory socketFactory = SocketFactory.getDefault();
        properties.put("mail.smtp.socketFactory", socketFactory);
        properties.setProperty("mail.smtp.socketFactory.fallback", "true");
        final Session session = Session.getInstance(properties);
        final AngusMailTransportLifecycleAdapter adapter = new AngusMailTransportLifecycleAdapter();
        adapter.configureOwnedSession(session, "smtp");
        try (Transport transport = session.getTransport("smtp")) {
            assertThat(transport).isInstanceOf(ManagedAngusTransport.class);
            assertThat(adapter.createAbortAction(transport)).isEmpty();
            assertThat(properties.get("mail.smtp.socketFactory")).isSameAs(socketFactory);
            assertThat(properties.getProperty("mail.smtp.socketFactory.fallback")).isEqualTo("true");
            assertThat(properties.getProperty("mail.mime.allowutf8")).isEqualTo("true");
        }
    }

    @Test
    void explicitBooleanUtf8DisablingIsNotReplacedByTheOwnedDefault() throws Exception {
        final Properties properties = new Properties();
        properties.put("mail.mime.allowutf8", false);
        final Session session = Session.getInstance(properties);
        new AngusMailTransportLifecycleAdapter().configureOwnedSession(session, "smtp");
        try (Transport transport = session.getTransport("smtp")) {
            assertThat(((ManagedAngusTransport) transport).isUtf8CommandEncodingEnabled()).isFalse();
        }
    }

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
