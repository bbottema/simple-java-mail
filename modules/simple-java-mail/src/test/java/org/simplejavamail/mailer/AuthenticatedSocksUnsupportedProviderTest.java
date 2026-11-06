package org.simplejavamail.mailer;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import testutil.ConfigLoaderTestHelper;
import testutil.socks.AuthenticatedSocksTestPeer;

import javax.net.SocketFactory;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Unsupported providers cannot turn a requested authenticated proxy into an anonymous or direct connection. */
@Timeout(15)
class AuthenticatedSocksUnsupportedProviderTest {
    private static final AtomicInteger constructed = new AtomicInteger();

    @ParameterizedTest
    @EnumSource(AuthenticatedSocksTestPeer.Protocol.class)
    void unsupportedProviderIsRejectedBeforeFactoriesNetworkAndCallerSessionChanges(final AuthenticatedSocksTestPeer.Protocol protocol) throws Exception {
        constructed.set(0);
        final String selectedProtocol = protocol == AuthenticatedSocksTestPeer.Protocol.SMTPS ? "smtps" : "smtp";
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", selectedProtocol);
        properties.setProperty("mail." + selectedProtocol + ".host", "smtp.example.invalid");
        if (protocol == AuthenticatedSocksTestPeer.Protocol.STARTTLS) {
            properties.setProperty("mail.smtp.starttls.enable", "true");
            properties.setProperty("mail.smtp.starttls.required", "true");
        }
        final SocketFactory factory = mock(SocketFactory.class);
        properties.put("mail." + selectedProtocol + ".socketFactory.transport", factory);
        final Session session = Session.getInstance(properties);
        final Provider provider = new Provider(Provider.Type.TRANSPORT, selectedProtocol, UnsupportedTransport.class.getName(), "test", "1");
        session.addProvider(provider);
        session.setProvider(provider);
        final Properties original = (Properties) properties.clone();
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol)) {
            assertThatThrownBy(() -> SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder(session)
                    .withProxyConfig(SocksProxyConfig.builder().withHost("127.0.0.1").withPort(proxy.port())
                            .withUsername("proxy-user").withPassword("proxy-secret").build()).buildMailer())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Authenticated SOCKS").hasMessageContaining("transport socket support");
            assertThat(proxy.accepted()).isZero();
            assertThat(constructed).hasValue(0);
            assertThat(session.getProperties()).isEqualTo(original);
            assertThat(session.getProvider(selectedProtocol)).isSameAs(provider);
            verifyNoInteractions(factory);
        }
    }

    public static final class UnsupportedTransport extends Transport {
        public UnsupportedTransport(final Session session, final URLName name) {
            super(session, name);
            constructed.incrementAndGet();
        }

        @Override
        protected boolean protocolConnect(final String host, final int port, final String user, final String password) {
            throw new AssertionError("An unsupported provider must not connect");
        }

        @Override
        public void sendMessage(final Message message, final Address[] addresses) {
            throw new AssertionError("An unsupported provider must not receive a submission");
        }
    }
}
