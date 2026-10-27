package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.eclipse.angus.mail.util.MailSSLSocketFactory;
import org.eclipse.angus.mail.util.SocketFetcher;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.config.ConfigLoader;
import testutil.configcontracts.ConfigurationContractCoverage;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

class ConnectionProviderGrammarContractTest {
    @Test
    void actualSocketFetcherInterpretsInlineAndSeparateSocksPortsLikeThePreparedRoute() throws Exception {
        // Inline ports use decimal only; separate ports use Angus PropUtil's decimal/hex grammar.
        final Object[][] cases = {{"1082", null, 1082}, {"0x43a", null, 1080}, {"02000", null, 2000},
                {"invalid", null, 1080}, {"2147483648", null, 1080}, {"", null, 1080},
                {"1082", "0x43c", 1084}, {"1082", "02000", 2000}, {"1082", "invalid", 1082},
                {"1082", "2147483648", 1082}};
        for (String protocol : new String[] {"smtp", "smtps"}) {
            final String prefix = "mail." + protocol;
            for (Object[] sample : cases) {
                final Properties properties = ConnectionProviderAccessContractTest.base(protocol);
                properties.setProperty(prefix + ".connectiontimeout", "1");
                properties.setProperty(prefix + ".socks.host", "127.0.0.1:" + sample[0]);
                if (sample[1] != null) { properties.setProperty(prefix + ".socks.port", (String) sample[1]); }
                final int expected = (Integer) sample[2];
                assertThat(ConnectionProviderSocketWitness.socksEndpoints(properties, prefix)).allSatisfy(endpoint -> {
                    assertThat(endpoint.getHostString()).isEqualTo("127.0.0.1");
                    assertThat(endpoint.getPort()).isEqualTo(expected);
                });
                if ("invalid".equals(sample[1]) || "2147483648".equals(sample[1])) {
                    // SJM rejects an explicitly unusable separate port before resource allocation; Angus itself falls back.
                    assertThatThrownBy(() -> ConnectionProviderAccessContractTest.prepare(properties))
                            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("port");
                    continue;
                }
                final MailerSessionSettings prepared = ConnectionProviderAccessContractTest.prepare(properties);
                assertThat(prepared.getProxyConfig().getRemoteProxyHost()).isEqualTo("127.0.0.1");
                assertThat(prepared.getProxyConfig().getRemoteProxyPort()).isEqualTo(expected);
                assertThat(ConnectionProviderSocketWitness.socksEndpoints(prepared.copyEffectiveProperties(), prefix))
                        .allSatisfy(endpoint -> assertThat(endpoint.getPort()).isEqualTo(expected));
            }
        }
        ConfigurationContractCoverage.record("rule", "provider.grammar.socks-endpoint");
    }

    @Test
    void nativeFactoryLookupPreservesDirectIdentityAndInheritedClassLookup() throws Exception {
        for (String protocol : new String[] {"smtp", "smtps"}) {
            for (String member : new String[] {"socketFactory", "ssl.socketFactory"}) {
                final String prefix = "mail." + protocol;
                final String key = prefix + "." + member;
                final Properties defaults = new Properties();
                defaults.setProperty(key + ".class", ConnectionProviderSocketWitness.RecordingFactory.class.getName());
                defaults.put(key, new ConnectionProviderSocketWitness.RecordingFactory());
                final Properties properties = new Properties(defaults);
                properties.setProperty("mail.transport.protocol", protocol);
                properties.setProperty(prefix + ".connectiontimeout", "1");
                properties.setProperty(prefix + ".socketFactory.fallback", "false");
                ConnectionProviderSocketWitness.RecordingFactory.CLASS_LOOKUPS.set(0);
                ConnectionProviderSocketWitness.RecordingFactory.SOCKET_CREATIONS.set(0);
                ConnectionProviderSocketWitness.exerciseFactoryLookup(properties, prefix, member.startsWith("ssl."));
                assertThat(ConnectionProviderSocketWitness.RecordingFactory.CLASS_LOOKUPS).hasValue(1);
                assertThat(ConnectionProviderSocketWitness.RecordingFactory.SOCKET_CREATIONS).hasValue(1);
                final Properties preparedInherited = ConnectionProviderAccessContractTest.prepare(properties).copyEffectiveProperties();
                assertThat(preparedInherited.get(key)).isNull();
                ConnectionProviderSocketWitness.exerciseFactoryLookup(preparedInherited, prefix, member.startsWith("ssl."));
                assertThat(ConnectionProviderSocketWitness.RecordingFactory.CLASS_LOOKUPS).hasValue(2);

                final ConnectionProviderSocketWitness.RecordingFactory direct = new ConnectionProviderSocketWitness.RecordingFactory();
                properties.put(key, direct);
                final Properties preparedDirect = ConnectionProviderAccessContractTest.prepare(properties).copyEffectiveProperties();
                assertThat(preparedDirect.get(key)).isSameAs(direct);
                ConnectionProviderSocketWitness.exerciseFactoryLookup(preparedDirect, prefix, member.startsWith("ssl."));
                assertThat(ConnectionProviderSocketWitness.RecordingFactory.CLASS_LOOKUPS).hasValue(2);
                assertThat(ConnectionProviderSocketWitness.RecordingFactory.SOCKET_CREATIONS).hasValue(3);
            }
        }
        ConfigurationContractCoverage.record("rule", "provider.access.direct-resource");
        ConfigurationContractCoverage.record("rule", "provider.grammar.factory-lookup");
    }

    @Test
    void nativeTrustTokenizationAndRealFactoryPolicyAgreeWithPreparedInspection() throws Exception {
        final String[][] lists = {{"*"}, {"127.0.0.1", "127.0.0.2"}, {"127.0.0.2", "127.0.0.1", "127.0.0.1"},
                {"*", "127.0.0.1"}, {""}, {"127.0.0.1", "127.0.0.2"}, {"127.0.0.1\u0000"}};
        final String[] texts = {"*", "127.0.0.1\t127.0.0.2", "127.0.0.2 127.0.0.1 127.0.0.1", "* 127.0.0.1", "",
                "127.0.0.1\u000b127.0.0.2", "127.0.0.1\u0000"};
        final boolean[] trustsOrdinaryHost = {true, true, true, true, false, true, false};
        for (String protocol : new String[] {"smtp", "smtps"}) {
            final String prefix = "mail." + protocol;
            for (int i = 0; i < texts.length; i++) {
                final Properties properties = ConnectionProviderAccessContractTest.base(protocol);
                properties.setProperty(prefix + ".connectiontimeout", "1");
                properties.setProperty(prefix + ".ssl.trust", texts[i]);
                final boolean wildcard = i == 0;
                final List<String> expectedNativeTokens = wildcard ? List.of() : Arrays.asList(lists[i]);
                assertThat(nativeTrustedHosts(properties, prefix, wildcard)).containsExactlyElementsOf(expectedNativeTokens);
                final MailerSessionSettings prepared = ConnectionProviderAccessContractTest.prepare(properties);
                assertThat(prepared.isTrustAllSSLHost()).isEqualTo(wildcard);
                // Empty tokens do not identify a host; inspection may omit them without changing membership.
                final List<String> expectedInspection = wildcard || texts[i].isEmpty() ? List.of() : Arrays.asList(lists[i]);

                // The real policy object, separately from the intercepted resource handoff, proves membership semantics.
                final MailSSLSocketFactory factory = new MailSSLSocketFactory();
                factory.setTrustAllHosts(wildcard);
                if (!wildcard) { factory.setTrustedHosts(lists[i]); }
                assertThat(factory.isServerTrusted("127.0.0.1", mock(SSLSocket.class)))
                        .isEqualTo(trustsOrdinaryHost[i]);
                assertThat(factory.isServerTrusted("127.0.0.3", mock(SSLSocket.class))).isEqualTo(wildcard);
                final MailSSLSocketFactory inspectedPolicy = new MailSSLSocketFactory();
                inspectedPolicy.setTrustAllHosts(prepared.isTrustAllSSLHost());
                inspectedPolicy.setTrustedHosts(prepared.getSslHostsToTrust().toArray(new String[0]));
                assertThat(inspectedPolicy.isServerTrusted("127.0.0.1", mock(SSLSocket.class)))
                        .isEqualTo(trustsOrdinaryHost[i]);
                assertThat(prepared.getSslHostsToTrust()).containsExactlyElementsOf(expectedInspection);
            }
        }
        ConfigurationContractCoverage.record("rule", "provider.grammar.trust-hosts");
    }

    @Test
    void strictTrustComparisonDoesNotEraseNonseparatorControlCharacters() {
        final Properties properties = ConnectionProviderAccessContractTest.base("smtp");
        properties.setProperty("mail.smtp.ssl.trust", "127.0.0.1\u0000");
        final Session session = Session.getInstance(properties);
        final MailerFromSessionBuilderImpl builder = new MailerFromSessionBuilderImpl(ConfigLoader.builder().load()).usingSession(session)
                .withTlsConfig(TlsConfig.builder().trustingSSLHosts("127.0.0.1").build());
        assertThatThrownBy(() -> builder.buildSessionSettings(session)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mail.smtp.ssl.trust");
        assertThat(properties.getProperty("mail.smtp.ssl.trust")).isEqualTo("127.0.0.1\u0000");
    }

    private static List<String> nativeTrustedHosts(final Properties properties, final String prefix, final boolean wildcard) throws Exception {
        mock(Object.class);
        final List<String> parsed = new ArrayList<>();
        final List<Boolean> wildcardChoices = new ArrayList<>();
        try (MockedConstruction<Socket> sockets = mockConstruction(Socket.class);
             MockedConstruction<MailSSLSocketFactory> factories = mockConstruction(MailSSLSocketFactory.class, (factory, context) -> {
                 doAnswer(call -> { parsed.addAll(Arrays.asList((String[]) call.getRawArguments()[0])); return null; })
                         .when(factory).setTrustedHosts(any(String[].class));
                 doAnswer(call -> { wildcardChoices.add(call.getArgument(0)); return null; }).when(factory).setTrustAllHosts(anyBoolean());
                 when(factory.createSocket(any(Socket.class), anyString(), anyInt(), anyBoolean()))
                         .thenThrow(new IOException("TLS resource handoff intercepted"));
             })) {
            try { SocketFetcher.getSocket("127.0.0.1", 25, properties, prefix, true); }
            catch (IOException intercepted) { /* Parser ran; no TLS or network connection is attempted. */ }
            assertThat(sockets.constructed()).isNotEmpty();
            assertThat(factories.constructed()).hasSize(1);
            assertThat(wildcardChoices).containsExactlyElementsOf(wildcard ? List.of(true) : List.of());
        }
        return parsed;
    }
}
