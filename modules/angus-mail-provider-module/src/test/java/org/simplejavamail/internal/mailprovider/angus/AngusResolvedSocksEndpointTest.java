package org.simplejavamail.internal.mailprovider.angus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockConstruction;

class AngusResolvedSocksEndpointTest {
    @ParameterizedTest
    @ValueSource(strings = {"::1", "127.0.0.1:bad:port"})
    void rawMultiColonValuesKeepFirstColonProviderGrammar(final String host) throws Exception {
        final Properties properties = properties(host, 1082);
        assertThat(proxyAddress(new AngusSocketFactory(properties, "mail.smtp")))
                .isEqualTo(new InetSocketAddress(host.startsWith(":") ? "" : "127.0.0.1", 1082));
    }

    @Test
    void resolvedLiteralIsUsedWithoutChangingItsVisibleProperties() throws Exception {
        final Properties properties = properties("::1", 1082);
        final Properties original = (Properties) properties.clone();
        final AngusSocketFactory factory = resolved(properties);
        assertThat(proxyAddress(factory)).isEqualTo(new InetSocketAddress(InetAddress.getByName("::1"), 1082));
        assertThat(properties).isEqualTo(original);
    }

    @Test
    void changedRawHostAndIntegerPortReplaceTheCapturedRoute() throws Exception {
        final Properties properties = properties("::1", 1082);
        final AngusSocketFactory factory = resolved(properties);
        properties.setProperty("mail.smtp.socks.host", "127.0.0.1:bad:port");
        properties.put("mail.smtp.socks.port", 1083);
        assertThat(proxyAddress(factory)).isEqualTo(new InetSocketAddress("127.0.0.1", 1083));
    }

    @Test
    void integerOnlyPortChangeReturnsToRawProviderGrammar() throws Exception {
        final Properties properties = properties("::1", 1082);
        final AngusSocketFactory factory = resolved(properties);
        properties.put("mail.smtp.socks.port", 1083);
        assertThat(proxyAddress(factory)).isEqualTo(new InetSocketAddress("", 1083));
    }

    @Test
    void privateProbeCopiesRetainOnlyAnUnchangedResolvedRoute() throws Exception {
        final Properties properties = properties("::1", 1082);
        final AngusSocketFactory factory = resolved(properties);
        final Properties probe = (Properties) properties.clone();
        assertThat(proxyAddress(factory.rebind(probe))).isEqualTo(new InetSocketAddress(InetAddress.getByName("::1"), 1082));
        probe.setProperty("mail.smtp.socks.host", "127.0.0.1:bad:port");
        probe.put("mail.smtp.socks.port", 1083);
        assertThat(proxyAddress(factory.rebind(probe))).isEqualTo(new InetSocketAddress("127.0.0.1", 1083));
        assertThat(properties.getProperty("mail.smtp.socks.host")).isEqualTo("::1");
    }

    @Test
    void httpProxyStillTakesPrecedenceOverTheResolvedSocksRoute() throws Exception {
        final Properties properties = properties("::1", 1082);
        final AngusSocketFactory factory = resolved(properties);
        properties.setProperty("mail.smtp.proxy.host", "http.example.test");
        final List<List<?>> arguments = new ArrayList<>();
        try (MockedConstruction<Socket> sockets = mockConstruction(Socket.class,
                (socket, context) -> arguments.add(context.arguments()))) {
            factory.createSocket();
            assertThat(sockets.constructed()).hasSize(1);
            assertThat(arguments.get(0)).isEmpty();
        }
    }

    private static AngusSocketFactory resolved(final Properties properties) {
        return new AngusSocketFactory(properties, "mail.smtp", InetSocketAddress.createUnresolved("::1", 1082));
    }

    private static Properties properties(final String host, final int port) {
        final Properties properties = new Properties();
        properties.setProperty("mail.smtp.socks.host", host);
        properties.setProperty("mail.smtp.socks.port", String.valueOf(port));
        return properties;
    }

    private static InetSocketAddress proxyAddress(final AngusSocketFactory factory) throws Exception {
        final List<Proxy> proxies = new ArrayList<>();
        try (MockedConstruction<Socket> sockets = mockConstruction(Socket.class,
                (socket, context) -> proxies.add((Proxy) context.arguments().get(0)))) {
            factory.createSocket();
            assertThat(sockets.constructed()).hasSize(1);
            assertThat(proxies).hasSize(1);
            return (InetSocketAddress) proxies.get(0).address();
        }
    }
}
