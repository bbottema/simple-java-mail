package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import testutil.configcontracts.ConfigurationContractCoverage;

import javax.net.ssl.SSLSocketFactory;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Each public route is invoked on a distinguishable seed and checked at its concrete immutable value or captured consumer. */
class ConnectionConfigurationWiringTest {
    static Stream<Method> routes() {
        return Stream.of(MailerFromSessionBuilder.class, MailerGenericBuilder.class, MailerRegularBuilder.class,
                ConnectionConfig.Builder.class, SocksProxyConfig.Builder.class, TlsConfig.Builder.class)
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> ConfigurationAdoptionCatalogue.API_OWNERS.get(ConfigurationContractCoverage.signature(method))
                        == ConfigurationAdoptionCatalogue.Owner.MAILER_CONNECTION);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void everyConnectionRouteChangesItsIntendedChoice(final Method method) throws Exception {
        final Class<?> owner = method.getDeclaringClass();
        if (owner == ConnectionConfig.Builder.class) { connectionValue(method); }
        else if (owner == SocksProxyConfig.Builder.class) { proxyValue(method); }
        else if (owner == TlsConfig.Builder.class) { tlsValue(method); }
        else { mailerChoice(method); }
    }

    private static void connectionValue(final Method method) throws Exception {
        final ConnectionConfig.Builder draft = ConnectionConfig.builder().withSessionTimeout(Duration.ofSeconds(9))
                .withLocalBindAddress("seed", 99).withSmtpClientHostname("seed-client");
        final String name = method.getName();
        final Object[] args = arguments(method, null);
        ConfigurationContractCoverage.invoke(draft, method, args);
        final ConnectionConfig value = draft.build();
        if (name.contains("SessionTimeout")) {
            final Duration expected = name.startsWith("with") ? Duration.ofSeconds(3) : name.startsWith("reset") ? Duration.ofSeconds(60) : null;
            assertThat(value.getConnectTimeout()).isEqualTo(expected);
            assertThat(value.getReadTimeout()).isEqualTo(expected);
            assertThat(value.getWriteTimeout()).isEqualTo(expected);
            fields("connectTimeout", "readTimeout", "writeTimeout");
            return;
        }
        final String field;
        if (name.contains("ConnectTimeout")) { field = "ConnectTimeout"; }
        else if (name.contains("ReadTimeout")) { field = "ReadTimeout"; }
        else if (name.contains("WriteTimeout")) { field = "WriteTimeout"; }
        else if (name.contains("LocalBindAddress")) { field = "LocalBindAddress"; }
        else if (name.contains("LocalBindPort")) { field = "LocalBindPort"; }
        else if (name.contains("SmtpClientHostname")) { field = "SmtpClientHostname"; }
        else { throw new AssertionError("Unspecified connection route " + method); }
        final Object expected = name.startsWith("with") ? args[0]
                : name.startsWith("reset") ? Duration.ofSeconds(60) : null;
        assertGetter(value, "get" + field, expected);
        if (!field.endsWith("Timeout")) {
            assertGetter(value, "is" + field + "Cleared", name.startsWith("clear"));
        }
        if (field.equals("LocalBindAddress")) {
            assertThat(value.getLocalBindPort()).isEqualTo(name.startsWith("clear") ? null : args.length == 2 ? 123 : 99);
            if (name.startsWith("clear")) { assertThat(value.isLocalBindPortCleared()).isTrue(); }
        }
        fields(lowerFirst(field));
    }

    private static void proxyValue(final Method method) throws Exception {
        final SocksProxyConfig.Builder draft = SocksProxyConfig.builder().withHost("seed-host").withPort(1088)
                .withUsername("seed-user").withPassword("seed-password").withBridgePort(1090);
        final String name = method.getName();
        final Object[] args = arguments(method, null);
        ConfigurationContractCoverage.invoke(draft, method, args);
        final SocksProxyConfig value = draft.build();
        if (name.equals("clearProxy")) {
            assertThat(value.getHost()).isNull(); assertThat(value.isHostCleared()).isTrue();
            assertThat(value.getPort()).isNull(); assertThat(value.isPortCleared()).isTrue();
            assertThat(value.getUsername()).isNull(); assertThat(value.isUsernameCleared()).isTrue();
            assertThat(value.getPassword()).isNull(); assertThat(value.isPasswordCleared()).isTrue();
            assertThat(value.getBridgePort()).isZero();
            fields("proxyHost", "proxyPort", "proxyUsername", "proxyPassword", "proxyBridgePort");
            return;
        }
        final String field = name.replaceFirst("^(with|clear|reset|useConfigured)", "");
        if (!List.of("Host", "Port", "Username", "Password", "BridgePort").contains(field)) {
            throw new AssertionError("Unspecified proxy route " + method);
        }
        final Object expected = name.startsWith("with") ? args[0] : name.startsWith("reset")
                ? Integer.valueOf(field.equals("BridgePort") ? 0 : 1080) : null;
        assertGetter(value, "get" + field, expected);
        if (!field.equals("BridgePort")) { assertGetter(value, "is" + field + "Cleared", name.startsWith("clear")); }
        fields("proxy" + field);
    }

    private static void tlsValue(final Method method) throws Exception {
        final SSLSocketFactory seedFactory = mock(SSLSocketFactory.class);
        final SSLSocketFactory chosenFactory = mock(SSLSocketFactory.class);
        final TlsConfig.Builder draft = TlsConfig.builder().trustingSSLHosts("seed").trustingAllHosts(true)
                .verifyingServerIdentity(true).withOpportunisticTLS(true).withCustomSSLFactoryClass("seed-class").withCustomSSLFactoryInstance(seedFactory);
        final String name = method.getName();
        final Object[] args = arguments(method, chosenFactory);
        ConfigurationContractCoverage.invoke(draft, method, args);
        final TlsConfig value = draft.build();
        final String field;
        final Object expected;
        if (name.equals("trustingSSLHosts") || name.equals("clearTrustedSSLHosts") || name.equals("useConfiguredSslHostsToTrust")) {
            field = "SslHostsToTrust";
            expected = name.equals("trustingSSLHosts") ? List.of("target", "second") : name.startsWith("clear") ? List.of() : null;
        } else if (name.contains("TrustAllHosts") || name.equals("trustingAllHosts") || name.equals("resetTrustingAllHosts")) {
            field = "TrustAllHosts"; expected = name.startsWith("useConfigured") ? null : Boolean.FALSE;
        } else if (name.contains("VerifyingServerIdentity") || name.equals("verifyingServerIdentity")) {
            field = "VerifyingServerIdentity"; expected = name.startsWith("useConfigured") ? null : name.startsWith("reset") ? Boolean.TRUE : Boolean.FALSE;
        } else if (name.contains("OpportunisticTLS")) {
            field = "OpportunisticTLS"; expected = name.startsWith("useConfigured") ? null : name.startsWith("reset") ? Boolean.TRUE : Boolean.FALSE;
        } else if (name.contains("CustomSSLFactoryClass")) {
            field = "CustomSSLFactoryClass"; expected = name.startsWith("with") ? "target" : null;
            assertThat(value.isCustomSSLFactoryClassCleared()).isEqualTo(name.startsWith("clear"));
        } else if (name.contains("CustomSSLFactoryInstance")) {
            field = "CustomSSLFactoryInstance"; expected = name.startsWith("with") ? chosenFactory : null;
            assertThat(value.isCustomSSLFactoryInstanceCleared()).isEqualTo(name.startsWith("clear"));
        } else { throw new AssertionError("Unspecified TLS route " + method); }
        assertGetter(value, "get" + field, expected);
        if (field.equals("CustomSSLFactoryInstance") && expected != null) { assertThat(value.getCustomSSLFactoryInstance()).isSameAs(chosenFactory); }
        fields(lowerFirst(field));
    }

    private static void mailerChoice(final Method method) {
        final String name = method.getName();
        if (name.equals("usingSession")) {
            final MailerFromSessionBuilderImpl builder = new MailerFromSessionBuilderImpl(ConfigLoader.builder().load());
            final Session session = Session.getInstance(new Properties());
            ConfigurationContractCoverage.invoke(builder, method, session);
            assertThat(builder.getSession()).isSameAs(session);
            assertThat(builder.captureConnectionInputs(session).getTargetSession()).isSameAs(session);
            fields("session"); return;
        }
        final var config = ConfigLoader.builder().withMap(Map.of("simplejavamail.defaults.readtimeoutmillis", 2000,
                "simplejavamail.proxy.host", "127.0.0.2", "simplejavamail.proxy.port", 1088,
                "simplejavamail.defaults.trustedhosts", "127.0.0.2", "simplejavamail.connection.conflictpolicy", "ALLOW_EXPLICIT_OVERRIDES")).load();
        final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(config).withSMTPServer("seed", 99, "seed-user", "seed-password")
                .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
        if (name.startsWith("withSMTPServer") || name.startsWith("clearSMTPServer") || name.equals("withTransportStrategy")) {
            final Object[] args = arguments(method, null);
            if (name.equals("withTransportStrategy")) { args[0] = TransportStrategy.SMTPS; }
            ConfigurationContractCoverage.invoke(builder, method, args);
            if (name.equals("withSMTPServer")) {
                assertThat(builder.getHost()).isEqualTo("target-host"); assertThat(builder.getPort()).isEqualTo(123);
                assertThat(builder.getUsername()).isEqualTo(args.length > 2 ? "target-user" : "seed-user");
                assertThat(builder.getPassword()).isEqualTo(args.length > 3 ? "target-password" : "seed-password");
                fields("smtpHost", "smtpPort", "smtpUsername", "smtpPassword");
            } else if (name.equals("withTransportStrategy")) {
                assertThat(builder.captureConnectionInputs(null).getStrategy()).isEqualTo(TransportStrategy.SMTPS); fields("transportStrategy");
            } else {
                final boolean clear = name.startsWith("clear");
                final String field = name.replaceFirst("^(with|clear)SMTPServer", "");
                final Object actual = field.equals("Host") ? builder.getHost() : field.equals("Port") ? builder.getPort()
                        : field.equals("Username") ? builder.getUsername() : builder.getPassword();
                assertThat(actual).isEqualTo(clear ? null : args[0]); fields("smtp" + field);
            }
            return;
        }
        switch (name) {
            case "withConnectionConfig":
                final ConnectionConfig connection = ConnectionConfig.builder().withReadTimeout(Duration.ofSeconds(3)).build();
                ConfigurationContractCoverage.invoke(builder, method, connection);
                assertThat(builder.getConnectionConfig()).isSameAs(connection);
                assertThat(builder.buildSessionSettings(null).getReadTimeout()).isEqualTo(Duration.ofSeconds(3)); fields("readTimeout"); break;
            case "useConfiguredConnectionConfig":
                builder.withConnectionConfig(ConnectionConfig.builder().withReadTimeout(Duration.ofSeconds(7)).build());
                ConfigurationContractCoverage.invoke(builder, method);
                assertThat(builder.getConnectionConfig().getReadTimeout()).isNull();
                assertThat(builder.buildSessionSettings(null).getReadTimeout()).isEqualTo(Duration.ofSeconds(2)); fields("readTimeout"); break;
            case "withProxyConfig":
                final SocksProxyConfig proxy = SocksProxyConfig.builder().withHost("127.0.0.1").withPort(1083).build();
                ConfigurationContractCoverage.invoke(builder, method, proxy);
                assertThat(builder.getProxyConfig()).isSameAs(proxy);
                assertThat(builder.buildSessionSettings(null).getProxyConfig().getRemoteProxyHost()).isEqualTo("127.0.0.1"); fields("proxyHost"); break;
            case "useConfiguredProxyConfig":
                builder.withProxyConfig(SocksProxyConfig.builder().withHost("127.0.0.1").build());
                ConfigurationContractCoverage.invoke(builder, method);
                assertThat(builder.getProxyConfig().getHost()).isNull();
                assertThat(builder.buildSessionSettings(null).getProxyConfig().getRemoteProxyHost()).isEqualTo("127.0.0.2"); fields("proxyHost"); break;
            case "withTlsConfig":
                final TlsConfig tls = TlsConfig.builder().verifyingServerIdentity(false).build();
                ConfigurationContractCoverage.invoke(builder, method, tls);
                assertThat(builder.getTlsConfig()).isSameAs(tls);
                assertThat(builder.buildSessionSettings(null).isVerifyingServerIdentity()).isFalse(); fields("verifyingServerIdentity"); break;
            case "useConfiguredTlsConfig":
                builder.withTlsConfig(TlsConfig.builder().verifyingServerIdentity(false).build());
                ConfigurationContractCoverage.invoke(builder, method);
                assertThat(builder.getTlsConfig().getVerifyingServerIdentity()).isNull();
                assertThat(builder.buildSessionSettings(null).isVerifyingServerIdentity()).isTrue(); fields("verifyingServerIdentity"); break;
            case "withOAuth2AccessTokenProvider": case "clearOAuth2AccessTokenProvider":
                final OAuth2AccessTokenProvider provider = () -> { throw new AssertionError("Provider must remain lazy"); };
                if (name.startsWith("clear")) { builder.withOAuth2AccessTokenProvider(provider); }
                ConfigurationContractCoverage.invoke(builder, method, name.startsWith("clear") ? new Object[0] : new Object[] {provider});
                assertThat(builder.getOAuth2AccessTokenProvider()).isSameAs(name.startsWith("clear") ? null : provider);
                assertThat(builder.captureConnectionInputs(null).getTokenProvider()).isSameAs(name.startsWith("clear") ? null : provider); fields("oauth2AccessTokenProvider"); break;
            case "withConnectionConflictPolicy": case "resetConnectionConflictPolicy": case "useConfiguredConnectionConflictPolicy":
                builder.withConnectionConflictPolicy(ConnectionConflictPolicy.REJECT_CONFLICTS);
                ConfigurationContractCoverage.invoke(builder, method, name.startsWith("with")
                        ? new Object[] {ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES} : new Object[0]);
                final ConnectionConflictPolicy expected = name.startsWith("reset") ? ConnectionConflictPolicy.REJECT_CONFLICTS : ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES;
                assertThat(builder.captureConnectionInputs(null).getPolicy()).isEqualTo(expected); fields("conflictPolicy"); break;
            default: throw new AssertionError("Unspecified Mailer connection route " + method);
        }
    }

    private static Object[] arguments(final Method method, final SSLSocketFactory factory) {
        final Object[] arguments = Arrays.stream(method.getParameterTypes()).map(type -> type == String.class ? "target" : type == Integer.class ? 123
                : type == Duration.class ? Duration.ofSeconds(3) : type == boolean.class ? Boolean.FALSE
                : type == String[].class ? new String[] {"target", "second"} : type == SSLSocketFactory.class ? factory
                : type == TransportStrategy.class ? TransportStrategy.SMTPS : null).toArray();
        if (method.getName().equals("withSMTPServer")) {
            arguments[0] = "target-host";
            if (arguments.length > 2) { arguments[2] = "target-user"; }
            if (arguments.length > 3) { arguments[3] = "target-password"; }
        }
        return arguments;
    }

    private static void assertGetter(final Object value, final String getter, final Object expected) throws Exception {
        assertThat(value.getClass().getMethod(getter).invoke(value)).as(getter).isEqualTo(expected);
    }

    private static String lowerFirst(final String name) { return Character.toLowerCase(name.charAt(0)) + name.substring(1); }
    private static void fields(final String... names) {
        for (String name : names) { ConfigurationContractCoverage.record("field", "connection." + name); }
    }
}
