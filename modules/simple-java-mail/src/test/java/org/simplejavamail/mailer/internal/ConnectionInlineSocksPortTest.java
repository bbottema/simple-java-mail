package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.AuthenticatedSocksModule;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionInlineSocksPortTest {
    @ParameterizedTest(name = "{0} {1} suffix={2} separate={3} credentials={4}")
    @CsvSource(nullValues = "~", value = {
            // Inline suffixes follow SocketFetcher decimal parsing; the separate property has its own numeric grammar.
            "SESSION, SMTP, 0x43a, ~, false, 1080",
            "SESSION, SMTPS, 0x43a, ~, true, 1080",
            "JAVA_RAW, SMTP, 0x43a, ~, true, 1080",
            "FACTORY_RAW, SMTPS, 0x43a, ~, false, 1080",
            "SESSION, SMTP, 1082, ~, false, 1082",
            "JAVA_RAW, SMTPS, 1082, ~, true, 1082",
            "FACTORY_RAW, SMTP, 02000, ~, false, 2000",
            "SESSION, SMTPS, +1082, ~, true, 1082",
            "SESSION, SMTP, 2147483648, ~, false, 1080",
            "JAVA_RAW, SMTPS, invalid, ~, true, 1080",
            "SESSION, SMTP, 0x43a, 0x43c, true, 1084",
            "FACTORY_RAW, SMTPS, 1081, 1084, false, 1084"
    })
    void interpretationAndBridgeRoutingUseTheProviderPortGrammar(final String source, final TransportStrategy strategy,
            final String suffix, final String separatePort, final boolean credentials, final int expectedPort) throws Exception {
        final String hostKey = strategy.propertyNameSocksHost();
        final String portKey = strategy.propertyNameSocksPort();
        final String rawHost = "proxy.example.org:" + suffix;
        final Properties raw = new Properties();
        raw.setProperty(hostKey, rawHost);
        if (separatePort != null) { raw.setProperty(portKey, separatePort); }
        final Map<String, String> configured = new LinkedHashMap<>();
        if (source.equals("FACTORY_RAW")) {
            raw.forEach((key, value) -> configured.put("simplejavamail.extraproperties." + key, (String) value));
        }
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(configured).load());
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", strategy == TransportStrategy.SMTP ? "smtp" : "smtps");
        if (source.equals("SESSION")) { properties.putAll(raw); }
        final MailerGenericBuilder<?> builder = source.equals("SESSION") ? mail.mailerBuilder(Session.getInstance(properties))
                : mail.mailerBuilder().withSMTPServerHost("relay.example.org").withTransportStrategy(strategy);
        if (source.equals("JAVA_RAW")) { builder.withProperties(raw); }
        if (credentials) { builder.withProxyConfig(SocksProxyConfig.builder().withUsername("test-user").withPassword("test-password").build()); }
        final AuthenticatedSocksModule module = mock(AuthenticatedSocksModule.class);
        final AnonymousSocks5Server bridge = mock(AnonymousSocks5Server.class);
        when(module.createAnonymousSocks5Server(any())).thenReturn(bridge);
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            modules.when(ModuleLoader::loadAuthenticatedSocksModule).thenReturn(module);
            try (Mailer built = builder.buildMailer()) {
                assertThat(built.getProxyConfig().getRemoteProxyHost()).isEqualTo("proxy.example.org");
                assertThat(built.getProxyConfig().getRemoteProxyPort()).isEqualTo(expectedPort);
                if (source.equals("SESSION") && !credentials) {
                    assertThat(built.getSession().getProperty(hostKey)).isEqualTo(rawHost);
                    assertThat(built.getSession().getProperty(portKey)).isEqualTo(separatePort);
                }
                if (credentials) {
                    final ArgumentCaptor<ProxyConfig> endpoint = ArgumentCaptor.forClass(ProxyConfig.class);
                    verify(module).createAnonymousSocks5Server(endpoint.capture());
                    assertThat(endpoint.getValue().getRemoteProxyPort()).isEqualTo(expectedPort);
                }
                verify(bridge, never()).start();
            }
        }
    }
}
