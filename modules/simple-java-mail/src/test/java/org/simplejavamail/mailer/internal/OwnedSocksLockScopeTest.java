package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedConstruction;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.config.ConfigLoader;

import javax.net.SocketFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockConstruction;

class OwnedSocksLockScopeTest {
    private static final String RAW_HOST_LOCK = "simplejavamail.locked.extraproperties.mail.smtp.socks.host";
    private static final String RAW_PORT_LOCK = "simplejavamail.locked.extraproperties.mail.smtp.socks.port";

    @Test
    void aLiteralRawLockRetainsItsTextWhileExplicitOverrideSelectsTheTypedRoute() throws Exception {
        final SimpleJavaMail factory = factory(Map.of(RAW_HOST_LOCK, "::1", RAW_PORT_LOCK, "1081"));
        try (Mailer mailer = factory.mailerBuilder().withSMTPServer("relay.example.test", 25)
                .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                .withProxyConfig(ipv6()).buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.socks.host")).isEqualTo("::1");
            assertThat(mailer.getSession().getProperty("mail.smtp.socks.port")).isEqualTo("1081");
            assertThat(mailer.getProxyConfig().getRemoteProxyHost()).isEqualTo("::1");
            assertThat(selectedProxy(mailer)).isEqualTo(new InetSocketAddress(InetAddress.getByName("::1"), 1081));
        }
    }

    @Test
    void strictResolutionStillRejectsTheDifferentRawAndTypedDeclarations() {
        final SimpleJavaMail factory = factory(Map.of(RAW_HOST_LOCK, "::1", RAW_PORT_LOCK, "1081"));
        assertThatThrownBy(() -> factory.mailerBuilder().withSMTPServer("relay.example.test", 25)
                .withProxyConfig(ipv6()).buildMailer()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mail.smtp.socks.host");
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void aLogicalIpv6LockConstrainsTheRemoteRouteUnderEitherPolicy(final ConnectionConflictPolicy policy) throws Exception {
        final SimpleJavaMail factory = factory(Map.of("simplejavamail.locked.proxy.host", "::1",
                "simplejavamail.locked.proxy.port", "1081"));
        try (Mailer mailer = factory.mailerBuilder().withSMTPServer("relay.example.test", 25)
                .withConnectionConflictPolicy(policy).withProxyConfig(ipv6()).buildMailer()) {
            assertThat(mailer.getProxyConfig().getRemoteProxyHost()).isEqualTo("::1");
            assertThat(selectedProxy(mailer)).isEqualTo(new InetSocketAddress(InetAddress.getByName("::1"), 1081));
        }
    }

    @Test
    void equivalentIpv6AddressSpellingDoesNotAuthorizeChangingLiteralLockedText() {
        final SimpleJavaMail factory = factory(Map.of(RAW_HOST_LOCK, "::1", RAW_PORT_LOCK, "1081"));
        assertThatThrownBy(() -> factory.mailerBuilder().withSMTPServer("relay.example.test", 25)
                .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                .withProxyConfig(SocksProxyConfig.builder().withHost("[::1]").withPort(1081).build()).buildMailer())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mail.smtp.socks.host");
    }

    private static InetSocketAddress selectedProxy(final Mailer mailer) throws Exception {
        final SocketFactory socketFactory = (SocketFactory) mailer.getSession().getProperties().get("mail.smtp.socketFactory");
        final AtomicReference<InetSocketAddress> selected = new AtomicReference<>();
        try (MockedConstruction<Socket> sockets = mockConstruction(Socket.class, (socket, context) -> {
            final Proxy proxy = (Proxy) context.arguments().get(0);
            selected.set((InetSocketAddress) proxy.address());
        }); Socket ignored = socketFactory.createSocket()) {
            assertThat(sockets.constructed()).hasSize(1);
            return selected.get();
        }
    }

    private static SocksProxyConfig ipv6() {
        return SocksProxyConfig.builder().withHost("::1").withPort(1081).build();
    }

    private static SimpleJavaMail factory(final Map<String, Object> values) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(values).load());
    }
}
