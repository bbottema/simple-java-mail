package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.eclipse.angus.mail.util.SocketFetcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.internal.util.SocketFactoryConfiguration;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

class AngusAuthenticatedSocksConfigurationTest {
    @Test
    void configurationPreservesCallerFactoriesAndProviderAndCreatesNothing() throws Exception {
        final Properties properties = new Properties();
        final SocketFactory raw = mock(SocketFactory.class);
        final SSLSocketFactory tls = mock(SSLSocketFactory.class);
        final SocketFactory transport = mock(SocketFactory.class);
        properties.put("mail.smtp.socketFactory", raw);
        properties.put("mail.smtp.ssl.socketFactory", tls);
        properties.put("mail.smtp.socketFactory.transport", transport);
        properties.setProperty("mail.smtp.socketFactory.transport.class", "unused.factory.class");
        properties.setProperty("mail.smtp.ssl.trust", "*");
        final Session session = Session.getInstance(properties);
        final Provider original = session.getProvider("smtp");
        final AngusMailTransportLifecycleAdapter adapter = new AngusMailTransportLifecycleAdapter();
        adapter.configureAuthenticatedSocks(session, "smtp", socket -> socket);
        assertThat(session.getProvider("smtp")).isSameAs(original);
        assertThat(properties.get("mail.smtp.socketFactory")).isSameAs(raw);
        assertThat(properties.get("mail.smtp.ssl.socketFactory")).isSameAs(tls);
        assertThat(properties.get("mail.smtp.socketFactory.transport")).isInstanceOf(AngusSocketFactory.class);
        assertThat(((SocketFactoryConfiguration) properties.get("mail.smtp.socketFactory.transport")).configuredInstance()).isSameAs(transport);
        assertThat(properties.getProperty("mail.smtp.socketFactory.transport.class")).isEqualTo("unused.factory.class");
        assertThat(properties.getProperty("mail.smtp.ssl.trust")).isEqualTo("*");
        verify(raw, never()).createSocket();
        verify(tls, never()).createSocket();
        verify(transport, never()).createSocket();
    }

    @Test
    void explicitTransportDelegateCreatesTheCapturedPhysicalSocketBeforeProxyWrapping() throws Exception {
        final Properties properties = new Properties();
        final SocketFactory raw = mock(SocketFactory.class);
        final SocketFactory transport = mock(SocketFactory.class);
        final Socket physical = mock(Socket.class);
        final Socket facade = mock(Socket.class);
        when(transport.createSocket()).thenReturn(physical);
        properties.put("mail.smtp.socketFactory", raw);
        properties.put("mail.smtp.socketFactory.transport", transport);
        final List<String> calls = new ArrayList<>();
        final AngusSocketFactory factory = new AngusSocketFactory(properties, "mail.smtp", socket -> {
            assertThat(socket).isSameAs(physical);
            calls.add("wrap");
            return facade;
        });
        factory.bind(socket -> {
            assertThat(socket).isSameAs(physical);
            calls.add("capture");
        });
        assertThat(factory.createSocket()).isSameAs(facade);
        assertThat(calls).containsExactly("capture", "wrap");
        verify(transport).createSocket();
        verify(raw, never()).createSocket();
        verify(physical, never()).connect(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ordinaryRawDelegateAndProbeStateRemainIndependent() throws Exception {
        final Properties source = new Properties();
        final SocketFactory raw = mock(SocketFactory.class);
        final Socket first = mock(Socket.class);
        final Socket second = mock(Socket.class);
        when(raw.createSocket()).thenReturn(first, second);
        source.put("mail.smtp.socketFactory", raw);
        final AngusSocketFactory factory = new AngusSocketFactory(source, "mail.smtp", socket -> socket);
        source.put("mail.smtp.socketFactory.transport", factory);
        final Properties probeProperties = (Properties) source.clone();
        final AngusSocketFactory probe = factory.forProbe(probeProperties);
        probeProperties.put("mail.smtp.socketFactory.transport", probe);
        factory.bind(socket -> { throw new IOException("source operation was stopped"); });
        assertThat(probe.createSocket()).isSameAs(first);
        assertThatThrownBy(factory::createSocket).isInstanceOf(IOException.class).hasMessageContaining("source operation was stopped");
        verify(second).close();
        verify(first, never()).close();
    }

    @Test
    void wrappingFailureClosesExactPhysicalSocketAndPreservesBothFailures() throws Exception {
        final Properties properties = new Properties();
        final SocketFactory raw = mock(SocketFactory.class);
        final Socket physical = mock(Socket.class);
        final IOException failure = new IOException("proxy wrapper rejected socket");
        final IOException cleanupFailure = new IOException("physical close failed");
        when(raw.createSocket()).thenReturn(physical);
        org.mockito.Mockito.doThrow(cleanupFailure).when(physical).close();
        properties.put("mail.smtp.socketFactory", raw);
        final AngusSocketFactory factory = new AngusSocketFactory(properties, "mail.smtp", socket -> { throw failure; });
        assertThatThrownBy(factory::createSocket).isSameAs(failure)
                .satisfies(rejected -> assertThat(rejected.getSuppressed()).containsExactly(cleanupFailure));
        verify(physical).close();
    }

    @Test
    void anHttpProxyKeepsNativePrecedenceAndDoesNotRunTheSocksHandshake() throws Exception {
        final Properties properties = new Properties();
        final SocketFactory raw = mock(SocketFactory.class);
        final Socket physical = mock(Socket.class);
        when(raw.createSocket()).thenReturn(physical);
        properties.put("mail.smtp.socketFactory", raw);
        properties.setProperty("mail.smtp.proxy.host", "http-proxy.invalid");
        final AngusSocketFactory factory = new AngusSocketFactory(properties, "mail.smtp", socket -> {
            throw new AssertionError("SOCKS must not replace the native HTTP CONNECT route");
        });
        assertThat(factory.createSocket()).isSameAs(physical);
    }

    @Test
    void transportClassResolutionIsLazyAndRetainsTheOriginalClassChoice() throws Exception {
        final Properties properties = new Properties();
        final SocketFactory delegate = mock(SocketFactory.class);
        final Socket physical = mock(Socket.class);
        when(delegate.createSocket()).thenReturn(physical);
        LazyTransportState.factory = delegate;
        properties.setProperty("mail.smtp.socketFactory.transport.class", LazyTransportFactory.class.getName());
        final AngusSocketFactory factory = new AngusSocketFactory(properties, "mail.smtp", socket -> socket);
        assertThat(LazyTransportState.initialized).isFalse();
        assertThat(LazyTransportState.calls).isZero();
        assertThat(factory.createSocket()).isSameAs(physical);
        assertThat(LazyTransportState.calls).isEqualTo(1);
        assertThat(properties.getProperty("mail.smtp.socketFactory.transport.class")).isEqualTo(LazyTransportFactory.class.getName());
    }

    @Test
    void ownedConfigurationTracksTheTransportHookWithoutChangingTlsFactorySelection() throws Exception {
        final Properties properties = new Properties();
        final SSLSocketFactory tls = mock(SSLSocketFactory.class);
        properties.put("mail.smtp.ssl.socketFactory", tls);
        properties.setProperty("mail.smtp.socketFactory.fallback", "true");
        final Session session = Session.getInstance(properties);
        final AngusMailTransportLifecycleAdapter adapter = new AngusMailTransportLifecycleAdapter();
        adapter.configureAuthenticatedSocks(session, "smtp", socket -> socket);
        adapter.configureOwnedSession(session, "smtp");
        try (Transport transport = session.getTransport("smtp")) {
            assertThat(adapter.createAbortAction(transport)).isEmpty();
            assertThat(properties.get("mail.smtp.ssl.socketFactory")).isSameAs(tls);
            assertThat(properties.getProperty("mail.smtp.socketFactory.fallback")).isEqualTo("true");
            assertThat(properties).doesNotContainKey("mail.smtp.socketFactory");
        }
        verify(tls, never()).createSocket();
    }

    @Test
    void aConventionalRawClassKeepsNativeSelectionAndCreatesOnePhysicalSocket() throws Exception {
        final Properties properties = new Properties();
        final RawFactory delegate = mock(RawFactory.class);
        final Socket physical = mock(Socket.class);
        when(delegate.createSocket()).thenReturn(physical);
        doThrow(new IOException("in-memory connection rejection")).when(physical).connect(any(SocketAddress.class), anyInt());
        RawClassState.factory = delegate;
        RawClassState.calls = 0;
        properties.setProperty("mail.smtp.socketFactory.class", RawClassFactory.class.getName());
        properties.setProperty("mail.smtp.connectiontimeout", "1");
        properties.setProperty("mail.smtp.socketFactory.fallback", "false");
        new AngusMailTransportLifecycleAdapter().configureAuthenticatedSocks(Session.getInstance(properties), "smtp", socket -> socket);
        assertThat(RawClassState.calls).isZero();
        assertThat(properties).doesNotContainKey("mail.smtp.socketFactory");
        assertThatThrownBy(() -> SocketFetcher.getSocket("127.0.0.1", 1, properties, "mail.smtp", false)).isInstanceOf(IOException.class);
        verify(delegate).createSocket();
    }

    @Test
    void implicitTlsSelectsTheRawFactoryInTheSslSlotBeforeTheRegularSlot() throws Exception {
        final Properties properties = new Properties();
        final SocketFactory sslSlot = mock(SocketFactory.class);
        final SocketFactory regular = mock(SocketFactory.class);
        final Socket physical = mock(Socket.class);
        when(sslSlot.createSocket()).thenReturn(physical);
        properties.put("mail.smtps.ssl.socketFactory", sslSlot);
        properties.put("mail.smtps.socketFactory", regular);
        final AngusSocketFactory factory = new AngusSocketFactory(properties, "mail.smtps", socket -> socket);
        assertThat(factory.createSocket()).isSameAs(physical);
        verify(regular, never()).createSocket();
    }

    @Test
    void implicitTlsDoesNotInvokeAnIgnoredRegularRawFactory() throws Exception {
        final Properties properties = new Properties();
        final SocketFactory regular = mock(SocketFactory.class);
        properties.put("mail.smtps.ssl.socketFactory", mock(SSLSocketFactory.class));
        properties.put("mail.smtps.socketFactory", regular);
        final AngusSocketFactory factory = new AngusSocketFactory(properties, "mail.smtps", socket -> socket);
        try (Socket ignored = factory.createSocket()) {
            verify(regular, never()).createSocket();
        }
    }

    @Test
    void aDefaultDirectCreatorRetainsOwnedAbortSupport() throws Exception {
        final Session session = Session.getInstance(new Properties());
        final AngusMailTransportLifecycleAdapter adapter = new AngusMailTransportLifecycleAdapter();
        adapter.configureAuthenticatedSocks(session, "smtp", socket -> socket);
        adapter.configureOwnedSession(session, "smtp");
        try (Transport transport = session.getTransport("smtp")) {
            assertThat(adapter.createAbortAction(transport)).isPresent();
        }
    }

    @Test
    void customRawTransportAndTlsFactoriesRetainTheirUnsupportedAbortBoundary() throws Exception {
        for (String key : new String[]{"mail.smtp.socketFactory", "mail.smtp.ssl.socketFactory", "mail.smtp.socketFactory.transport"}) {
            for (boolean classChoice : new boolean[]{false, true}) {
                final Properties properties = new Properties();
                if (classChoice) {
                    properties.setProperty(key + ".class", key.contains(".ssl.") ? SSLSocketFactory.class.getName() : RawClassFactory.class.getName());
                } else {
                    properties.put(key, key.contains(".ssl.") ? mock(SSLSocketFactory.class) : mock(SocketFactory.class));
                }
                final Session session = Session.getInstance(properties);
                final AngusMailTransportLifecycleAdapter adapter = new AngusMailTransportLifecycleAdapter();
                adapter.configureAuthenticatedSocks(session, "smtp", socket -> socket);
                adapter.configureOwnedSession(session, "smtp");
                try (Transport transport = session.getTransport("smtp")) {
                    assertThat(adapter.createAbortAction(transport)).as("opaque %s class=%s", key, classChoice).isEmpty();
                }
            }
        }
    }

    @Test
    void formerOwnedDefaultFactoryDoesNotAddAnAnonymousSocksLayerToTheDirectRoute() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.smtp.socks.host", "127.0.0.1");
        properties.setProperty("mail.smtp.socks.port", "1");
        properties.put("mail.smtp.socketFactory", new AngusSocketFactory(properties, "mail.smtp"));
        final AngusSocketFactory direct = new AngusSocketFactory(properties, "mail.smtp", socket -> socket);
        assertThat(direct.ownsPhysicalSocket()).isTrue();
        try (ServerSocket destination = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()); Socket socket = direct.createSocket()) {
            destination.setSoTimeout(1000);
            socket.connect(destination.getLocalSocketAddress(), 1000);
            try (Socket accepted = destination.accept()) {
                assertThat(socket.isConnected()).isTrue();
                assertThat(accepted.isConnected()).isTrue();
            }
        }
    }

    @Test
    void ownedDirectDefaultsFallbackToFalseWhileSuppliedConfigurationRemainsNative() throws Exception {
        final AngusMailTransportLifecycleAdapter adapter = new AngusMailTransportLifecycleAdapter();
        final Session supplied = Session.getInstance(new Properties());
        adapter.configureAuthenticatedSocks(supplied, "smtp", socket -> socket);
        assertThat(supplied.getProperties()).doesNotContainKey("mail.smtp.socketFactory.fallback");
        final Session owned = Session.getInstance(new Properties());
        adapter.configureAuthenticatedSocks(owned, "smtp", socket -> socket);
        adapter.configureOwnedSession(owned, "smtp");
        assertThat(owned.getProperty("mail.smtp.socketFactory.fallback")).isEqualTo("false");
    }

    @Test
    void ownedDirectFallbackPreservesExplicitBooleanStringAndInheritedChoices() {
        for (Object selected : new Object[]{Boolean.TRUE, Boolean.FALSE, "true", "false"}) {
            final Properties properties = new Properties();
            properties.put("mail.smtp.socketFactory.fallback", selected);
            final Session session = Session.getInstance(properties);
            final AngusMailTransportLifecycleAdapter adapter = new AngusMailTransportLifecycleAdapter();
            adapter.configureAuthenticatedSocks(session, "smtp", socket -> socket);
            adapter.configureOwnedSession(session, "smtp");
            assertThat(properties.get("mail.smtp.socketFactory.fallback")).isSameAs(selected);
        }
        for (String selected : new String[]{"true", "false"}) {
            final Properties defaults = new Properties();
            defaults.setProperty("mail.smtp.socketFactory.fallback", selected);
            final Properties properties = new Properties(defaults);
            final Session session = Session.getInstance(properties);
            final AngusMailTransportLifecycleAdapter adapter = new AngusMailTransportLifecycleAdapter();
            adapter.configureAuthenticatedSocks(session, "smtp", socket -> socket);
            adapter.configureOwnedSession(session, "smtp");
            assertThat(properties.getProperty("mail.smtp.socketFactory.fallback")).isEqualTo(selected);
            assertThat(properties).doesNotContainKey("mail.smtp.socketFactory.fallback");
        }
    }

    @Test
    void smtpsFalseSslFlagRetainsThePinnedNativeImplicitTlsFactorySelection() throws Exception {
        final Properties properties = new Properties();
        final SocketFactory sslSlot = mock(SocketFactory.class);
        final SocketFactory ignoredRegular = mock(SocketFactory.class);
        final Socket physical = mock(Socket.class);
        when(sslSlot.createSocket()).thenReturn(physical);
        properties.setProperty("mail.smtps.ssl.enable", "false");
        properties.put("mail.smtps.ssl.socketFactory", sslSlot);
        properties.put("mail.smtps.socketFactory", ignoredRegular);
        final AngusSocketFactory factory = new AngusSocketFactory(properties, "mail.smtps", socket -> socket);
        assertThat(factory.createSocket()).isSameAs(physical);
        verify(ignoredRegular, never()).createSocket();
    }

    @Test
    void aGenericLegacyClassCanReturnAnSslFactoryWithoutBecomingARawSocketCreator() throws Exception {
        final Properties properties = new Properties();
        final SSLSocketFactory tls = mock(SSLSocketFactory.class);
        GenericClassState.factory = tls;
        GenericClassState.calls = 0;
        properties.setProperty("mail.smtp.socketFactory.class", GenericFactory.class.getName());
        properties.setProperty("mail.smtp.socketFactory.fallback", "false");
        final Session session = Session.getInstance(properties);
        new AngusMailTransportLifecycleAdapter().configureAuthenticatedSocks(session, "smtp", socket -> socket);
        assertThat(properties).doesNotContainKey("mail.smtp.socketFactory");
        final AngusSocketFactory creator = (AngusSocketFactory) properties.get("mail.smtp.socketFactory.transport");
        assertThat(GenericClassState.calls).isZero();
        try (Socket socket = creator.createSocket()) {
            assertThat(socket.isConnected()).isFalse();
            assertThat(socket).isNotInstanceOf(javax.net.ssl.SSLSocket.class);
        }
        verify(tls, never()).createSocket();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aNullSslClassFallsThroughToTheOrdinaryRawFactory(final boolean fallback) throws Exception {
        final Properties properties = new Properties();
        final SocketFactory regular = mock(SocketFactory.class);
        final Socket physical = mock(Socket.class);
        when(regular.createSocket()).thenReturn(physical);
        properties.put("mail.smtps.socketFactory", regular);
        properties.setProperty("mail.smtps.ssl.socketFactory.class", NullFactory.class.getName());
        properties.setProperty("mail.smtps.socketFactory.fallback", Boolean.toString(fallback));
        new AngusMailTransportLifecycleAdapter().configureAuthenticatedSocks(Session.getInstance(properties), "smtps", socket -> socket);
        final SocketFactory creator = (SocketFactory) properties.get("mail.smtps.socketFactory.transport");
        assertThat(creator.createSocket()).isSameAs(physical);
        verify(regular).createSocket();
    }

    @Test
    void aStrictNullRawClassRetainsTheOriginalPortAndUsesTheDefaultPhysicalSocket() throws Exception {
        final Properties properties = new Properties();
        final AtomicInteger connectedPort = new AtomicInteger();
        properties.setProperty("mail.smtp.socketFactory.class", NullFactory.class.getName());
        properties.setProperty("mail.smtp.socketFactory.port", "42424");
        properties.setProperty("mail.smtp.socketFactory.fallback", "false");
        properties.setProperty("mail.smtp.connectiontimeout", "1");
        new AngusMailTransportLifecycleAdapter().configureAuthenticatedSocks(Session.getInstance(properties), "smtp", physical -> {
            assertThat(physical).isExactlyInstanceOf(Socket.class);
            final Socket memory = mock(Socket.class);
            doAnswer(call -> {
                connectedPort.set(((InetSocketAddress) call.getArgument(0)).getPort());
                throw new IOException("in-memory rejection before networking");
            }).when(memory).connect(any(SocketAddress.class), anyInt());
            doAnswer(call -> { physical.close(); return null; }).when(memory).close();
            return memory;
        });
        assertThatThrownBy(() -> SocketFetcher.getSocket("127.0.0.1", 2525, properties, "mail.smtp", false)).isInstanceOf(IOException.class);
        assertThat(connectedPort).hasValue(2525);
    }

    @Test
    void anExplicitNullTransportClassRemainsStrictEvenWhenLegacyFallbackIsEnabled() {
        final Properties properties = new Properties();
        properties.setProperty("mail.smtp.socketFactory.transport.class", NullFactory.class.getName());
        properties.setProperty("mail.smtp.socketFactory.fallback", "true");
        final AngusSocketFactory creator = new AngusSocketFactory(properties, "mail.smtp", socket -> {
            throw new AssertionError("A missing explicit transport must not select a default socket");
        });
        assertThatThrownBy(creator::createSocket).isInstanceOf(IOException.class).hasMessageContaining("did not return a SocketFactory");
    }

    @Test
    void aLegacyRawFactoryReturningNoSocketUsesDefaultPhysicalCreation() throws Exception {
        final Properties properties = new Properties();
        final SocketFactory regular = mock(SocketFactory.class);
        properties.put("mail.smtp.socketFactory", regular);
        properties.setProperty("mail.smtp.socketFactory.fallback", "false");
        final AtomicInteger wrapped = new AtomicInteger();
        final AngusSocketFactory creator = new AngusSocketFactory(properties, "mail.smtp", socket -> {
            wrapped.incrementAndGet();
            return socket;
        });
        try (Socket socket = creator.createSocket()) {
            assertThat(socket).isExactlyInstanceOf(Socket.class);
            assertThat(socket.isConnected()).isFalse();
        }
        assertThat(wrapped).hasValue(1);
        verify(regular).createSocket();
    }

    @Test
    void anExplicitTransportReturningNoSocketCannotChooseTheDefaultPhysicalCreator() {
        final Properties properties = new Properties();
        properties.put("mail.smtp.socketFactory.transport", mock(SocketFactory.class));
        properties.setProperty("mail.smtp.socketFactory.fallback", "true");
        final AngusSocketFactory creator = new AngusSocketFactory(properties, "mail.smtp", socket -> {
            throw new AssertionError("An explicit transport returning no socket must not select a default socket");
        });
        assertThatThrownBy(creator::createSocket).isInstanceOf(IOException.class).hasMessageContaining("returned no socket");
    }

    private static final class LazyTransportState {
        private static boolean initialized;
        private static int calls;
        private static SocketFactory factory;
    }

    public abstract static class LazyTransportFactory extends SocketFactory {
        static { LazyTransportState.initialized = true; }

        public static SocketFactory getDefault() {
            LazyTransportState.calls++;
            return LazyTransportState.factory;
        }
    }

    private static final class RawClassState {
        private static int calls;
        private static RawFactory factory;
    }

    public abstract static class RawFactory extends SocketFactory { }

    public abstract static class RawClassFactory extends SocketFactory {
        public static RawFactory getDefault() {
            RawClassState.calls++;
            return RawClassState.factory;
        }
    }

    private static final class GenericClassState {
        private static int calls;
        private static SocketFactory factory;
    }

    public abstract static class GenericFactory extends SocketFactory {
        public static SocketFactory getDefault() {
            GenericClassState.calls++;
            return GenericClassState.factory;
        }
    }

    public abstract static class NullFactory extends SocketFactory {
        public static RawFactory getDefault() { return null; }
    }
}
