package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.config.ConfigLoader;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SocksPortBoundaryTest {
    @ParameterizedTest(name = "{0}: {1}={2}")
    @MethodSource("invalidPorts")
    void rejectsInvalidActivePortsBeforeEffects(final Route route, final boolean bridge, final int port) {
        final Properties supplied = sessionProperties();
        final MailerGenericBuilder<?> builder = builder(route, bridge, port, supplied);
        final Properties original = (Properties) supplied.clone();
        assertThatThrownBy(() -> {
            try (Mailer ignored = builder.buildMailer()) { }
        }).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(bridge ? "bridge port" : "proxy port")
                .hasMessageContaining("65535");
        assertThat(supplied).isEqualTo(original);
    }

    private static Stream<Arguments> invalidPorts() {
        return Arrays.stream(Route.values()).flatMap(route -> Stream.of(
                Arguments.of(route, false, -1), Arguments.of(route, false, 0), Arguments.of(route, false, 65536),
                Arguments.of(route, true, -1), Arguments.of(route, true, 65536)));
    }

    @ParameterizedTest(name = "remote={0}, bridge={1}")
    @MethodSource("validPorts")
    void permitsTheBoundaryPortsWithoutOpeningTheBridge(final int remote, final int bridge) throws Exception {
        try (Mailer mailer = factory(Map.of()).mailerBuilder().withSMTPServer("smtp.example.test", 25)
                .withProxyConfig(SocksProxyConfig.builder().withHost("proxy.example.test").withPort(remote)
                        .withUsername("user").withPassword("secret").withBridgePort(bridge).build()).buildMailer()) {
            assertThat(mailer.getProxyConfig().getRemoteProxyPort()).isEqualTo(remote);
            assertThat(mailer.getProxyConfig().getProxyBridgePort()).isEqualTo(bridge);
        }
    }

    private static Stream<Arguments> validPorts() {
        return Stream.of(Arguments.of(1, 0), Arguments.of(65535, 1), Arguments.of(1080, 65535));
    }

    @Test
    void inspectingChoicesAndInactiveValuesRemainPassive() throws Exception {
        final SocksProxyConfig.Builder choices = SocksProxyConfig.builder().withPort(-1).withBridgePort(65536);
        final SocksProxyConfig captured = choices.build();
        assertThat(choices.getPort()).isEqualTo(-1);
        assertThat(captured.toBuilder().getBridgePort()).isEqualTo(65536);
        try (Mailer inactive = factory(Map.of()).mailerBuilder().withSMTPServer("smtp.example.test", 25)
                .withProxyConfig(captured).buildMailer()) {
            assertThat(inactive.getProxyConfig().requiresProxy()).isFalse();
        }
        try (Mailer anonymous = factory(Map.of()).mailerBuilder().withSMTPServer("smtp.example.test", 25)
                .withProxyConfig(captured.toBuilder().withHost("proxy.example.test").withPort(1080).build()).buildMailer()) {
            assertThat(anonymous.getProxyConfig().requiresAuthentication()).isFalse();
        }
        try (Mailer custom = factory(Map.of()).mailerBuilder().withProxyConfig(captured.toBuilder().withHost("proxy.example.test").build())
                .withCustomMailer(mock(CustomMailer.class)).buildMailer()) {
            assertThat(custom.getOperationalConfig().getCustomMailer()).isNotNull();
        }
    }

    @Test
    void selectedValidPortReplacesInvalidConfiguredValue() throws Exception {
        try (Mailer mailer = factory(Map.of("simplejavamail.proxy.host", "proxy.example.test", "simplejavamail.proxy.port", -1))
                .mailerBuilder().withSMTPServer("smtp.example.test", 25)
                .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                .withProxyConfig(SocksProxyConfig.builder().withPort(1080).build()).buildMailer()) {
            assertThat(mailer.getProxyConfig().getRemoteProxyPort()).isEqualTo(1080);
        }
    }

    @Test
    void rejectedConstructionDoesNotClaimTheSuppliedSession() throws Exception {
        final Session session = Session.getInstance(sessionProperties());
        assertThatThrownBy(() -> factory(Map.of()).mailerBuilder(session)
                .withProxyConfig(SocksProxyConfig.builder().withUsername("user").withPassword("secret").withBridgePort(-1).build())
                .buildMailer()).isInstanceOf(IllegalArgumentException.class);
        try (Mailer accepted = factory(Map.of()).mailerBuilder(session).buildMailer()) {
            assertThat(accepted.getSession()).isSameAs(session);
        }
    }

    private static MailerGenericBuilder<?> builder(final Route route, final boolean bridge, final int port, final Properties supplied) {
        final Map<String, Object> configured = new LinkedHashMap<>();
        final SocksProxyConfig.Builder proxy = SocksProxyConfig.builder().withUsername("user").withPassword("secret");
        if (bridge) {
            if (route == Route.CONFIGURED) { configured.put("simplejavamail.proxy.socks5bridge.port", port); }
            else { proxy.withBridgePort(port); }
        }
        if (route == Route.CONFIGURED) {
            configured.put("simplejavamail.proxy.host", "proxy.example.test");
            configured.put("simplejavamail.proxy.port", bridge ? 1080 : port);
        }
        final MailerGenericBuilder<?> builder;
        if (route == Route.SESSION) {
            if (!bridge) { supplied.setProperty("mail.smtp.socks.port", String.valueOf(port)); }
            builder = factory(configured).mailerBuilder(Session.getInstance(supplied));
        } else {
            builder = factory(configured).mailerBuilder().withSMTPServer("smtp.example.test", 25);
            if (route == Route.JAVA) { proxy.withHost("proxy.example.test").withPort(bridge ? 1080 : port); }
            else if (route == Route.RAW) {
                builder.withProperty("mail.smtp.socks.host", "proxy.example.test")
                        .withProperty("mail.smtp.socks.port", String.valueOf(bridge ? 1080 : port));
            }
        }
        return builder.withProxyConfig(proxy.build());
    }

    private static SimpleJavaMail factory(final Map<String, Object> values) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(values).load());
    }

    private static Properties sessionProperties() {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.host", "smtp.example.test");
        properties.setProperty("mail.smtp.port", "25");
        properties.setProperty("mail.smtp.socks.host", "proxy.example.test");
        properties.setProperty("mail.smtp.socks.port", "1080");
        return properties;
    }

    private enum Route { JAVA, CONFIGURED, RAW, SESSION }
}
