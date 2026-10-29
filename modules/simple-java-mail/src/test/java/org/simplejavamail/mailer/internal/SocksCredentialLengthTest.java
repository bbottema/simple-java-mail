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
import testutil.ConfigLoaderTestHelper;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SocksCredentialLengthTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("oversizedCredentialSources")
    void rejectsOversizedEffectiveCredentialsBeforeSessionEffects(final String boundary, final ConfigurationRoute route,
            final String username, final String password, final String member) {
        final Properties supplied = suppliedProperties();
        final Properties before = (Properties) supplied.clone();
        final MailerGenericBuilder<?> builder = builder(route, username, password, supplied);

        assertThatThrownBy(() -> {
            try (Mailer ignored = builder.buildMailer()) { }
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(member).hasMessageContaining("255")
                .hasMessageNotContaining(username).hasMessageNotContaining(password);
        assertThat(supplied).as("Rejected construction must preserve a supplied Session").isEqualTo(before);
    }

    private static Stream<Arguments> oversizedCredentialSources() {
        return Arrays.stream(ConfigurationRoute.values()).flatMap(route -> Stream.of(
                Arguments.of(route + " / ASCII username", route, "u".repeat(256), "synthetic-secret", "username"),
                Arguments.of(route + " / ASCII password", route, "synthetic-user", "p".repeat(256), "password"),
                Arguments.of(route + " / multibyte username", route, "ü".repeat(128), "synthetic-secret", "username"),
                Arguments.of(route + " / multibyte password", route, "synthetic-user", "ü".repeat(128), "password")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("representableCredentialSources")
    void acceptsRepresentableCredentialsFromEachSource(final String boundary, final ConfigurationRoute route,
            final String username, final String password) throws Exception {
        try (Mailer mailer = builder(route, username, password, suppliedProperties()).buildMailer()) {
            assertThat(mailer.getProxyConfig().getUsername()).isEqualTo(username);
            assertThat(mailer.getProxyConfig().getPassword()).isEqualTo(password);
        }
    }

    private static Stream<Arguments> representableCredentialSources() {
        return Arrays.stream(ConfigurationRoute.values()).flatMap(route -> Stream.of(
                Arguments.of(route + " / one byte per member", route, "u", "p"),
                Arguments.of(route + " / 255 UTF-8 bytes per member", route, "ü".repeat(127) + "x", "€".repeat(85))));
    }

    @Test
    void suppliedChoicesRemainInspectableBeforeEffectiveValidation() {
        final SocksProxyConfig.Builder choices = SocksProxyConfig.builder().withUsername("ü".repeat(128));
        final SocksProxyConfig incomplete = choices.build();

        assertThat(choices.getUsername().getBytes(UTF_8)).hasSize(256);
        assertThat(choices.getPassword()).isNull();
        assertThat(incomplete.getUsername()).isEqualTo(choices.getUsername());
        assertThat(incomplete.toBuilder().getUsername()).isEqualTo(choices.getUsername());
        choices.withUsername("valid-user").withPassword("valid-secret");
        assertThat(incomplete.getUsername().getBytes(UTF_8)).hasSize(256);
    }

    @Test
    void validatesTheSelectedCredentialRatherThanAnOverriddenCapturedValue() throws Exception {
        final Map<String, Object> captured = new LinkedHashMap<>();
        captured.put("simplejavamail.proxy.host", "proxy.example.test");
        captured.put("simplejavamail.proxy.username", "ü".repeat(128));
        captured.put("simplejavamail.proxy.password", "captured-secret");
        try (Mailer mailer = factory(captured).mailerBuilder().withSMTPServer("smtp.example.test", 25)
                .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                .withProxyConfig(SocksProxyConfig.builder().withUsername("selected-user").build()).buildMailer()) {
            assertThat(mailer.getProxyConfig().getUsername()).isEqualTo("selected-user");
            assertThat(mailer.getProxyConfig().getPassword()).isEqualTo("captured-secret");
        }
    }

    @Test
    void inactiveProxyChoicesDoNotAcquireCredentialValidation() throws Exception {
        final SocksProxyConfig oversized = SocksProxyConfig.builder().withUsername("ü".repeat(128)).withPassword("p".repeat(256)).build();
        try (Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
                .withSMTPServer("smtp.example.test", 25).withProxyConfig(oversized).buildMailer()) {
            assertThat(mailer.getProxyConfig().requiresProxy()).isFalse();
        }
        try (Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
                .withProxyConfig(oversized.toBuilder().withHost("proxy.example.test").build())
                .withCustomMailer(mock(CustomMailer.class)).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getCustomMailer()).isNotNull();
        }
    }

    @Test
    void aRejectedLengthDoesNotClaimTheSuppliedSession() throws Exception {
        final Properties supplied = suppliedProperties();
        final Session session = Session.getInstance(supplied);
        final var factory = factory(Map.of());
        assertThatThrownBy(() -> {
            try (Mailer ignored = factory.mailerBuilder(session).withProxyConfig(SocksProxyConfig.builder()
                    .withUsername("ü".repeat(128)).withPassword("synthetic-secret").build()).buildMailer()) { }
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("255");

        try (Mailer mailer = factory.mailerBuilder(session).withProxyConfig(SocksProxyConfig.builder()
                .withUsername("selected-user").withPassword("selected-secret").build()).buildMailer()) {
            assertThat(mailer.getSession()).isSameAs(session);
            assertThat(mailer.getProxyConfig().getUsername()).isEqualTo("selected-user");
        }
    }

    private static MailerGenericBuilder<?> builder(final ConfigurationRoute route, final String username, final String password,
            final Properties supplied) {
        final Map<String, Object> captured = new LinkedHashMap<>();
        if (route != ConfigurationRoute.JAVA) {
            captured.put("simplejavamail.proxy.username", username);
            captured.put("simplejavamail.proxy.password", password);
        }
        if (route == ConfigurationRoute.CAPTURED) {
            captured.put("simplejavamail.proxy.host", "proxy.example.test");
            captured.put("simplejavamail.proxy.port", 1080);
        }
        if (route == ConfigurationRoute.SUPPLIED_SESSION) {
            return factory(captured).mailerBuilder(Session.getInstance(supplied));
        }
        final var builder = factory(captured).mailerBuilder().withSMTPServer("smtp.example.test", 25);
        if (route == ConfigurationRoute.JAVA) {
            builder.withProxyConfig(SocksProxyConfig.builder().withHost("proxy.example.test").withPort(1080)
                    .withUsername(username).withPassword(password).build());
        } else if (route == ConfigurationRoute.RAW_ENDPOINT) {
            builder.withProperty("mail.smtp.socks.host", "proxy.example.test").withProperty("mail.smtp.socks.port", "1080");
        }
        return builder;
    }

    private static SimpleJavaMail factory(final Map<String, Object> captured) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(captured).load());
    }

    private static Properties suppliedProperties() {
        final Properties supplied = new Properties();
        supplied.setProperty("mail.transport.protocol", "smtps");
        supplied.setProperty("mail.smtps.host", "smtp.example.test");
        supplied.setProperty("mail.smtps.port", "465");
        supplied.setProperty("mail.smtps.socks.host", "proxy.example.test");
        supplied.setProperty("mail.smtps.socks.port", "1080");
        return supplied;
    }

    private enum ConfigurationRoute { JAVA, CAPTURED, RAW_ENDPOINT, SUPPLIED_SESSION }
}
