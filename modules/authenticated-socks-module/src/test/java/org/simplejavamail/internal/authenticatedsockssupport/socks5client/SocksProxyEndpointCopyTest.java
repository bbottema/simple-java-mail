package org.simplejavamail.internal.authenticatedsockssupport.socks5client;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SocksProxyEndpointCopyTest {

    @Test
    void unresolvedCopyPreservesTheHostPortAndExistingConfigurationWithoutSharingTheSocket() {
        final InetSocketAddress endpoint = InetSocketAddress.createUnresolved("configured-proxy.invalid", 19080);
        final Socks5 original = new Socks5(endpoint);
        final Socket existingSocket = mock(Socket.class);
        configure(original, existingSocket);

        final Socks5 copy = original.copy();

        assertThat(copy.getInetAddress()).isNull();
        assertThat(copy.getProxyHost()).isEqualTo("configured-proxy.invalid");
        assertThat(copy.getPort()).isEqualTo(19080);
        assertThat(copy.toString()).startsWith("[SOCKS5:" + endpoint + "]");
        assertCopiedConfiguration(original, copy, existingSocket);
    }

    @Test
    void changingAResolvedEndpointKeepsTheCopiedHostAndPhysicalConnectionCoherent() throws Exception {
        final Socks5 original = new Socks5(InetSocketAddress.createUnresolved("old-proxy.invalid", 1080));
        final InetAddress updated = InetAddress.getByAddress(new byte[] {127, 0, 0, 17});
        original.setInetAddress(updated).setPort(19081);

        assertThat(original.getProxyHost()).isEqualTo("127.0.0.17");
        final Socks5 copy = original.copy();
        assertThat(copy.getProxyHost()).isEqualTo("127.0.0.17");
        assertThat(copy.getInetAddress()).isSameAs(updated);
        assertThat(copy.getPort()).isEqualTo(19081);
        assertThat(original.toString()).contains("127.0.0.17:19081").doesNotContain("old-proxy.invalid");
        final InetSocketAddress connected = connectWithScriptedPhysicalSocket(original);
        assertThat(connected.getAddress()).isEqualTo(updated);
        assertThat(connected.getPort()).isEqualTo(19081);
    }

    @Test
    void unresolvedSslCopyIsInertAndRetainsTheSameConfigurationAndDelegates() throws Exception {
        final SSLConfiguration configuration = mock(SSLConfiguration.class);
        final InetSocketAddress endpoint = InetSocketAddress.createUnresolved("ssl-proxy.invalid", 19443);
        final SSLSocks5 original = new SSLSocks5(endpoint, configuration);
        final Socket existingSocket = mock(Socket.class);
        configure(original, existingSocket);

        final Socks5 copy = original.copy();

        assertThat(copy).isInstanceOf(SSLSocks5.class);
        assertThat(copy.getInetAddress()).isNull();
        assertThat(copy.getProxyHost()).isEqualTo("ssl-proxy.invalid");
        assertThat(copy.getPort()).isEqualTo(19443);
        assertThat(copy.toString()).startsWith("[SOCKS5:" + endpoint + "]");
        assertCopiedConfiguration(original, copy, existingSocket);
        verifyNoInteractions(configuration);

        final SSLSocketFactory factory = mock(SSLSocketFactory.class);
        final Socket createdOnlyWhenRequested = mock(Socket.class);
        when(configuration.getSSLSocketFactory()).thenReturn(factory);
        when(factory.createSocket()).thenReturn(createdOnlyWhenRequested);
        assertThat(copy.createProxySocket()).isSameAs(createdOnlyWhenRequested);
        verify(configuration).getSSLSocketFactory();
        verify(factory).createSocket();
    }

    @Test
    void resolvedCopyPreservesTheKnownAddressAndCachedHostWithoutResolving() throws Exception {
        final InetAddress knownAddress = InetAddress.getByAddress("cached-proxy.invalid", new byte[] {127, 0, 0, 18});
        final Socks5 original = new Socks5(new InetSocketAddress(knownAddress, 19082));

        final Socks5 copy = original.copy();

        assertThat(copy.getInetAddress()).isSameAs(knownAddress);
        assertThat(copy.getProxyHost()).isEqualTo("cached-proxy.invalid");
        assertThat(copy.getPort()).isEqualTo(19082);
        assertThat(copy.toString()).isEqualTo(original.toString()).contains("cached-proxy.invalid");
        assertThat(copy.getProxySocket()).isNull();
    }

    @Test
    void physicalConnectionRetainsTheKnownAddressRatherThanReinterpretingItsCachedHost() throws Exception {
        // Both literals are numeric, so even the regression path needs no DNS or network connection.
        final InetAddress knownAddress = InetAddress.getByAddress("127.0.0.23", new byte[] {127, 0, 0, 19});
        final Socks5 proxy = new Socks5(new InetSocketAddress(knownAddress, 19083));

        final InetSocketAddress connected = connectWithScriptedPhysicalSocket(proxy);

        assertThat(connected.getAddress()).isSameAs(knownAddress);
        assertThat(connected.getHostString()).isEqualTo("127.0.0.23");
        assertThat(connected.getPort()).isEqualTo(19083);
    }

    private static void configure(final Socks5 original, final Socket existingSocket) {
        original.setAlwaysResolveAddressLocally(true)
                .setCredentials(new ProxyCredentials("proxy-user", "proxy-secret"))
                .setSocksAuthenticationHelper(new SocksAuthenticationHelper())
                .setChainProxy(new Socks5(InetSocketAddress.createUnresolved("chain-proxy.invalid", 1081)));
        original.setProxySocket(existingSocket);
    }

    private static void assertCopiedConfiguration(final Socks5 original, final Socks5 copy, final Socket existingSocket) {
        assertThat(copy).isNotSameAs(original);
        assertThat(copy.getCredentials()).isSameAs(original.getCredentials());
        assertThat(copy.getSocksAuthenticationHelper()).isSameAs(original.getSocksAuthenticationHelper());
        assertThat(copy.getChainProxy()).isSameAs(original.getChainProxy());
        assertThat(copy.isAlwaysResolveAddressLocally()).isTrue();
        assertThat(copy.getProxySocket()).isNull();
        assertThat(original.getProxySocket()).isSameAs(existingSocket);
        verifyNoInteractions(existingSocket);
    }

    private static InetSocketAddress connectWithScriptedPhysicalSocket(final Socks5 proxy) throws Exception {
        final Socket physical = mock(Socket.class);
        when(physical.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[] {
                5, 0, 5, 0, 0, 1, 127, 0, 0, 1, 0, 25}));
        when(physical.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        try (Socket socket = new SocksSocket(proxy, physical)) {
            socket.connect(InetSocketAddress.createUnresolved("smtp.remote-only.invalid", 2525));
            final ArgumentCaptor<SocketAddress> endpoint = ArgumentCaptor.forClass(SocketAddress.class);
            verify(physical).connect(endpoint.capture(), anyInt());
            return (InetSocketAddress) endpoint.getValue();
        }
    }
}
