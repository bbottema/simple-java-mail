package org.simplejavamail.internal.util;

import jakarta.mail.Provider;
import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.simplejavamail.api.mailer.spi.MailTransportLifecycleAdapter;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class OwnedSocksEndpointContextTest {
    @Test
    void nestedInitializationKeepsExactSessionProtocolAndRestoresTheOuterRoute() throws Exception {
        final Session outer = session("smtp");
        final Session inner = session("smtps");
        final InetSocketAddress outerEndpoint = InetSocketAddress.createUnresolved("::1", 1081);
        final InetSocketAddress innerEndpoint = InetSocketAddress.createUnresolved("proxy.example.test", 1082);
        final Properties original = (Properties) outer.getProperties().clone();
        final MailTransportLifecycleAdapter adapter = mock(MailTransportLifecycleAdapter.class);
        when(adapter.supportsProvider(any())).thenReturn(true);
        doAnswer(invocation -> {
            final Session selected = invocation.getArgument(0);
            final String protocol = invocation.getArgument(1);
            assertThat(MailTransportLifecycleResolver.ownedSocksEndpoint(selected, protocol))
                    .isSameAs(selected == outer ? outerEndpoint : innerEndpoint);
            assertThat(MailTransportLifecycleResolver.ownedSocksEndpoint(mock(Session.class), protocol)).isNull();
            assertThat(MailTransportLifecycleResolver.ownedSocksEndpoint(selected, "other")).isNull();
            if (selected == outer) {
                MailTransportLifecycleResolver.configureOwnedSession(inner, innerEndpoint);
                assertThat(MailTransportLifecycleResolver.ownedSocksEndpoint(outer, "smtp")).isSameAs(outerEndpoint);
            }
            return null;
        }).when(adapter).configureOwnedSession(any(), anyString());
        try (MockedStatic<MailProviderDiscovery> providers = mockStatic(MailProviderDiscovery.class)) {
            providers.when(() -> MailProviderDiscovery.newProviders(MailTransportLifecycleAdapter.class))
                    .thenReturn(Collections.singletonList(adapter));
            MailTransportLifecycleResolver.configureOwnedSession(outer, outerEndpoint);
        }
        assertThat(MailTransportLifecycleResolver.ownedSocksEndpoint(outer, "smtp")).isNull();
        assertThat(MailTransportLifecycleResolver.ownedSocksEndpoint(inner, "smtps")).isNull();
        assertThat(outer.getProperties()).isEqualTo(original);
        assertThat(outerEndpoint.isUnresolved()).isTrue();
    }

    @Test
    void failedInitializationRemovesItsRoute() throws Exception {
        final Session selected = session("smtp");
        final IllegalStateException failure = new IllegalStateException("adapter failed");
        final MailTransportLifecycleAdapter adapter = mock(MailTransportLifecycleAdapter.class);
        when(adapter.supportsProvider(any())).thenReturn(true);
        doAnswer(invocation -> { throw failure; }).when(adapter).configureOwnedSession(any(), anyString());
        try (MockedStatic<MailProviderDiscovery> providers = mockStatic(MailProviderDiscovery.class)) {
            providers.when(() -> MailProviderDiscovery.newProviders(MailTransportLifecycleAdapter.class))
                    .thenReturn(Collections.singletonList(adapter));
            assertThatThrownBy(() -> MailTransportLifecycleResolver.configureOwnedSession(selected,
                    InetSocketAddress.createUnresolved("::1", 1080))).isSameAs(failure);
        }
        assertThat(MailTransportLifecycleResolver.ownedSocksEndpoint(selected, "smtp")).isNull();
    }

    private static Session session(final String protocol) throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", protocol);
        final Session session = mock(Session.class);
        when(session.getProperty("mail.transport.protocol")).thenReturn(protocol);
        when(session.getProperties()).thenReturn(properties);
        when(session.getProvider(protocol)).thenReturn(new Provider(Provider.Type.TRANSPORT, protocol, "fixture.Transport", "fixture", null));
        return session;
    }
}
