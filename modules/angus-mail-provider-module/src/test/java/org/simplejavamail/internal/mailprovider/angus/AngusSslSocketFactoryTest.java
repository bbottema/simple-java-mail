package org.simplejavamail.internal.mailprovider.angus;

import org.eclipse.angus.mail.util.MailSSLSocketFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AngusSslSocketFactoryTest {
    @Test
    void angusCanStillApplyTheOriginalFactorysTrustedHostCheck() throws Exception {
        final MailSSLSocketFactory application = mock(MailSSLSocketFactory.class);
        final Socket connected = mock(Socket.class);
        final SSLSocket wrapped = mock(SSLSocket.class);
        when(application.createSocket(connected, "localhost", 465, true)).thenReturn(wrapped);
        when(application.isServerTrusted("localhost", wrapped)).thenReturn(false);
        final AngusSslSocketFactory safe = new AngusSslSocketFactory(application, "mail.smtps.ssl.socketFactory");
        assertThat(safe).isInstanceOf(MailSSLSocketFactory.class);
        assertThat(safe.createSocket(connected, "localhost", 465, true)).isSameAs(wrapped);
        assertThat(safe.isServerTrusted("localhost", wrapped)).isFalse();
        verify(application).isServerTrusted("localhost", wrapped);
    }

    @Test
    void classFactoriesKeepEachConnectionsTrustDecisionEvenWhenChecksArriveInReverseOrder() throws Exception {
        final Socket firstConnection = mock(Socket.class);
        final Socket secondConnection = mock(Socket.class);
        final SSLSocket firstSocket = mock(SSLSocket.class);
        final SSLSocket secondSocket = mock(SSLSocket.class);
        final MailSSLSocketFactory firstFactory = mock(MailSSLSocketFactory.class);
        final MailSSLSocketFactory secondFactory = mock(MailSSLSocketFactory.class);
        when(firstFactory.createSocket(firstConnection, "localhost", 465, true)).thenReturn(firstSocket);
        when(secondFactory.createSocket(secondConnection, "localhost", 465, true)).thenReturn(secondSocket);
        when(firstFactory.isServerTrusted("localhost", firstSocket)).thenReturn(false);
        when(secondFactory.isServerTrusted("localhost", secondSocket)).thenReturn(true);
        SelectedSslFactory.factories.add(firstFactory);
        SelectedSslFactory.factories.add(secondFactory);
        final AngusSslSocketFactory safe = new AngusSslSocketFactory(SelectedSslFactory.class, "mail.smtps.ssl.socketFactory");
        assertThat(safe.createSocket(firstConnection, "localhost", 465, true)).isSameAs(firstSocket);
        assertThat(safe.createSocket(secondConnection, "localhost", 465, true)).isSameAs(secondSocket);
        assertThat(safe).isInstanceOf(MailSSLSocketFactory.class);
        assertThat(safe.isServerTrusted("localhost", secondSocket)).isTrue();
        assertThat(safe.isServerTrusted("localhost", firstSocket)).isFalse();
        assertThat(SelectedSslFactory.factories).isEmpty();
        verify(firstFactory).isServerTrusted("localhost", firstSocket);
        verify(secondFactory).isServerTrusted("localhost", secondSocket);
    }

    @Test
    void ordinaryFactoriesRetainHandshakeTrustAndUnknownSocketsAreNotAuthorized() throws Exception {
        final SSLSocketFactory application = mock(SSLSocketFactory.class);
        final Socket connected = mock(Socket.class);
        final SSLSocket wrapped = mock(SSLSocket.class);
        when(application.createSocket(connected, "localhost", 465, true)).thenReturn(wrapped);
        final AngusSslSocketFactory safe = new AngusSslSocketFactory(application, "mail.smtps.ssl.socketFactory");
        assertThat(safe.isServerTrusted("localhost", mock(SSLSocket.class))).isFalse();
        assertThat(safe.createSocket(connected, "localhost", 465, true)).isSameAs(wrapped);
        assertThat(safe.isServerTrusted("localhost", wrapped)).isTrue();
        assertThat(safe.isServerTrusted("localhost", wrapped)).as("the completed trust check retains no authorization").isFalse();
    }

    @ParameterizedTest
    @MethodSource("wrappingFailures")
    void wrappingFailureRetainsItsIdentityAndSuppressesCloseFailure(final Throwable rejection) throws Exception {
        final SSLSocketFactory application = mock(SSLSocketFactory.class);
        final Socket connected = mock(Socket.class);
        final IOException closeFailure = new IOException("synthetic close failure");
        when(application.createSocket(connected, "localhost", 465, true)).thenThrow(rejection);
        doThrow(closeFailure).when(connected).close();
        final AngusSslSocketFactory safe = new AngusSslSocketFactory(application, "mail.smtps.ssl.socketFactory");
        assertThatThrownBy(() -> safe.createSocket(connected, "localhost", 465, true))
                .isSameAs(rejection).satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(closeFailure));
        verify(connected).close();
    }

    private static Stream<Throwable> wrappingFailures() {
        return Stream.of(new IOException("synthetic wrapping failure"), new IllegalArgumentException("synthetic runtime failure"),
                new AssertionError("synthetic factory error"));
    }

    @Test
    void consumedInputWrappingAlsoClosesOnFailureWithoutTakingOwnershipOfTheInput() throws Exception {
        final SSLSocketFactory application = mock(SSLSocketFactory.class);
        final Socket connected = mock(Socket.class);
        final InputStream consumed = mock(InputStream.class);
        final UnsupportedOperationException rejection = new UnsupportedOperationException("consumed-input wrapping unavailable");
        when(application.createSocket(connected, consumed, true)).thenThrow(rejection);
        final AngusSslSocketFactory safe = new AngusSslSocketFactory(application, "mail.smtp.ssl.socketFactory");
        assertThatThrownBy(() -> safe.createSocket(connected, consumed, true)).isSameAs(rejection);
        verify(connected).close();
        verify(consumed, never()).close();
    }

    @Test
    void successfulWrappingAndCallerRetainedSocketsAreNotClosed() throws Exception {
        final SSLSocketFactory application = mock(SSLSocketFactory.class);
        final Socket connected = mock(Socket.class);
        final Socket wrapped = mock(Socket.class);
        when(application.createSocket(connected, "localhost", 465, true)).thenReturn(wrapped);
        when(application.createSocket(connected, "localhost", 465, false)).thenThrow(new IOException("caller retains ownership"));
        final AngusSslSocketFactory safe = new AngusSslSocketFactory(application, "mail.smtps.ssl.socketFactory");
        assertThat(safe.createSocket(connected, "localhost", 465, true)).isSameAs(wrapped);
        assertThatThrownBy(() -> safe.createSocket(connected, "localhost", 465, false)).isInstanceOf(IOException.class);
        verify(connected, never()).close();
        verify(wrapped, never()).close();
    }

    @Test
    void classFactoriesRemainLazyAndTheirOriginalConfigurationKeyIsRetained() throws Exception {
        LazyFactoryCalls.count = 0;
        assertThat(LazyFactoryCalls.initialized).isFalse();
        final Properties properties = new Properties();
        properties.setProperty("mail.smtps.ssl.socketFactory.class", LazySslFactory.class.getName());
        AngusSocketFactories.configure(properties, "mail.smtps");
        assertThat(LazyFactoryCalls.count).isZero();
        assertThat(LazyFactoryCalls.initialized).isFalse();
        assertThat(properties.getProperty("mail.smtps.ssl.socketFactory.class")).isEqualTo(LazySslFactory.class.getName());
        final SSLSocketFactory safe = (SSLSocketFactory) properties.get("mail.smtps.ssl.socketFactory");
        final Socket connected = mock(Socket.class);
        assertThatThrownBy(() -> safe.createSocket(connected, "localhost", 465, true)).isSameAs(LazyFactoryCalls.rejection);
        assertThat(LazyFactoryCalls.count).isEqualTo(1);
        assertThat(LazyFactoryCalls.initialized).isTrue();
        verify(connected).close();
    }

    @Test
    void explicitBooleanFallbackAndOrdinaryFactoriesKeepTheirSettings() {
        final Properties properties = new Properties();
        final javax.net.SocketFactory plain = javax.net.SocketFactory.getDefault();
        properties.put("mail.smtp.socketFactory", plain);
        properties.put("mail.smtp.socketFactory.fallback", Boolean.TRUE);
        properties.setProperty("mail.smtp.socketFactory.port", "2525");
        AngusSocketFactories.configure(properties, "mail.smtp");
        assertThat(properties.get("mail.smtp.socketFactory")).isSameAs(plain);
        assertThat(properties.get("mail.smtp.socketFactory.fallback")).isEqualTo(Boolean.TRUE);
        assertThat(properties.getProperty("mail.smtp.socketFactory.port")).isEqualTo("2525");
    }

    @Test
    void tlsInstancesInThePlainFactorySlotKeepTheirTlsType() {
        final Properties properties = new Properties();
        properties.put("mail.smtp.socketFactory", mock(SSLSocketFactory.class));
        AngusSocketFactories.configure(properties, "mail.smtp");
        assertThat(properties.get("mail.smtp.socketFactory")).isInstanceOf(SSLSocketFactory.class);
        assertThat(properties.getProperty("mail.smtp.socketFactory.fallback")).isEqualTo("false");
    }

    private static final class LazyFactoryCalls {
        private static int count;
        private static boolean initialized;
        private static final IOException rejection = new IOException("class factory rejected TLS wrapping");
    }

    public abstract static class SelectedSslFactory extends SSLSocketFactory {
        private static final Queue<SSLSocketFactory> factories = new ConcurrentLinkedQueue<>();

        public static SSLSocketFactory getDefault() {
            return factories.remove();
        }
    }

    public static final class LazySslFactory extends SSLSocketFactory {
        static {
            LazyFactoryCalls.initialized = true;
        }
        public static SSLSocketFactory getDefault() {
            LazyFactoryCalls.count++;
            final SSLSocketFactory factory = mock(SSLSocketFactory.class);
            try {
                when(factory.createSocket(any(Socket.class), anyString(), anyInt(), eq(true))).thenThrow(LazyFactoryCalls.rejection);
            } catch (IOException unexpected) {
                throw new AssertionError(unexpected);
            }
            return factory;
        }

        @Override public String[] getDefaultCipherSuites() { throw new UnsupportedOperationException(); }
        @Override public String[] getSupportedCipherSuites() { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(String host, int port) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(InetAddress host, int port) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) { throw new UnsupportedOperationException(); }
    }
}
