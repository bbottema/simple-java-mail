package org.simplejavamail.mailer;

import jakarta.mail.Session;
import jakarta.mail.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;
import org.simplejavamail.internal.util.SocketFactoryConfiguration;
import testutil.ConfigLoaderTestHelper;
import testutil.socks.AuthenticatedSocksTestPeer;
import testutil.socks.AuthenticatedSocksFactories;

import java.lang.reflect.Field;
import javax.net.ssl.HostnameVerifier;
import java.time.Duration;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static testutil.socks.AuthenticatedSocksRouteTestSupport.requireDirectRoute;

/** Real public-API witnesses for automatic direct routing in the explicit merged-Angus development lane. */
@Timeout(30)
class AuthenticatedSocksDirectRoutingTest {
    @BeforeEach
    void directWitnessRequiresTheExplicitMergedAngusLane() {
        requireDirectRoute();
    }

    @ParameterizedTest(name = "{0}, suppliedSession={1}")
    @MethodSource("defaultRoutes")
    void defaultOwnedAndSuppliedAngusRoutesSendWithoutABridge(final AuthenticatedSocksTestPeer.Protocol protocol,
            final boolean supplied) throws Exception {
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol);
             Mailer mailer = builder(proxy, protocol, supplied).buildMailer()) {
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(proxy.authenticated()).isEqualTo(1);
            assertThat(proxy.accepted()).isEqualTo(1);
            assertThat(proxy.messages()).isEqualTo(1);
            assertThat(proxy.tlsHandshakes()).isEqualTo(protocol == AuthenticatedSocksTestPeer.Protocol.SMTP ? 0 : 1);
            proxy.assertHealthy();
            assertDirectRoute(mailer, proxy, protocol);
        }
    }

    @Test
    void constructingAndInspectingTheDirectRouteCreatesNoNetworkActors() throws Exception {
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(AuthenticatedSocksTestPeer.Protocol.SMTP);
             Mailer mailer = builder(proxy, AuthenticatedSocksTestPeer.Protocol.SMTP, false).buildMailer()) {
            assertThat(proxy.accepted()).isZero();
            assertThat(mailer.getProxyConfig().getRemoteProxyHost()).isEqualTo("127.0.0.1");
            assertThat(mailer.getProxyConfig().getRemoteProxyPort()).isEqualTo(proxy.port());
            mailer.getOperationalConfig();
            mailer.getSession();
            assertThat(proxy.accepted()).isZero();
            assertDirectRoute(mailer, proxy, AuthenticatedSocksTestPeer.Protocol.SMTP);
        }
    }

    @ParameterizedTest(name = "probe {0}, suppliedSession={1}")
    @MethodSource("defaultRoutes")
    void aDedicatedProbeUsesFreshPhysicalStateAndLeavesThePooledSenderUsable(final AuthenticatedSocksTestPeer.Protocol protocol,
            final boolean supplied) throws Exception {
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol);
             Mailer mailer = builder(proxy, protocol, supplied).buildMailer()) {
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(proxy.accepted()).isEqualTo(1);
            assertThat(mailer.sync().probeConnection().isSuccessful()).isTrue();
            assertThat(proxy.accepted()).as("the dedicated probe opens another physical proxy socket").isEqualTo(2);
            assertThat(proxy.authenticated()).isEqualTo(2);
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(proxy.accepted()).as("probe closure cannot dispose the pooled sender's socket").isEqualTo(2);
            assertThat(proxy.messages()).isEqualTo(2);
            proxy.assertHealthy();
            assertDirectRoute(mailer, proxy, protocol);
        }
    }

    @ParameterizedTest(name = "TLS {0}, suppliedSession={1}, namedTrust={2}")
    @MethodSource("tlsFailures")
    void standardTrustAndRealServerIdentityRemainEnforced(final AuthenticatedSocksTestPeer.Protocol protocol,
            final boolean supplied, final boolean trustNamedHost) throws Exception {
        final String host = trustNamedHost ? "wrong.proxy-only.invalid" : "localhost";
        final Properties trust = new Properties();
        if (!trustNamedHost) { trust.put(prefix(protocol) + ".ssl.socketFactory", AuthenticatedSocksTestPeer.untrustedClientTlsFactory()); }
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol);
             Mailer mailer = builder(proxy, protocol, supplied, host, trustNamedHost).withProperties(trust).buildMailer()) {
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email()));
            assertThat(failure).isInstanceOf(MailException.class);
            assertThat(proxy.authenticated()).isEqualTo(1);
            assertThat(proxy.messages()).isZero();
            if (trustNamedHost) { assertThat(proxy.serverNames()).contains(host); }
        }
    }

    @Test
    void aCallerSuppliedStockSessionDoesNotGainTotalDeadlineSupport() throws Exception {
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(AuthenticatedSocksTestPeer.Protocol.SMTP)) {
            final Throwable failure = catchThrowable(() -> {
                try (Mailer mailer = builder(proxy, AuthenticatedSocksTestPeer.Protocol.SMTP, true)
                        .withMailSendTimeout(Duration.ofSeconds(1)).buildMailer()) {
                    mailer.sync().sendMail(email());
                }
            });
            assertThat(failure).isNotNull();
            assertThat(proxy.accepted()).as("unsupported timed sending is rejected before connecting").isZero();
        }
    }

    @ParameterizedTest(name = "factories {0}, suppliedSession={1}, {2}")
    @MethodSource("factoryRoutes")
    void applicationFactoriesRemainLazyAndComposeWithTheNativeTransportHook(
            final AuthenticatedSocksTestPeer.Protocol protocol, final boolean supplied, final Factories factories) throws Exception {
        final String prefix = prefix(protocol);
        final AuthenticatedSocksFactories.Raw raw = new AuthenticatedSocksFactories.Raw();
        final AuthenticatedSocksFactories.Raw ignoredRaw = new AuthenticatedSocksFactories.Raw();
        final AuthenticatedSocksFactories.Tls tls = new AuthenticatedSocksFactories.Tls(AuthenticatedSocksTestPeer.trustedClientTlsFactory());
        final AuthenticatedSocksFactories.Tls ignoredTls = new AuthenticatedSocksFactories.Tls(AuthenticatedSocksTestPeer.trustedClientTlsFactory());
        AuthenticatedSocksFactories.selectClassFactories(raw, tls);
        final Properties properties = factories.configure(prefix, raw, tls, ignoredRaw, ignoredTls);
        // A positive native write timeout adds Angus's WriteTimeoutSocket around the custom transport.
        properties.setProperty(prefix + ".writetimeout", "3000");
        properties.setProperty(prefix + ".socketFactory.fallback", "false");
        final Session original = supplied ? session(protocol, "localhost", properties) : null;
        final Provider originalProvider = original == null ? null : original.getProvider(transport(protocol));
        // Native Angus starts TLS immediately when an SSL factory occupies the regular slot, including with STARTTLS requested.
        final AuthenticatedSocksTestPeer.Protocol peerProtocol = factories == Factories.TLS_REGULAR_INSTANCE || factories == Factories.TLS_REGULAR_CLASS
                ? AuthenticatedSocksTestPeer.Protocol.SMTPS : protocol;
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(peerProtocol);
             Mailer mailer = builder(proxy, protocol, original, "localhost", !factories.tls)
                     .withProperties(supplied ? new Properties() : properties).buildMailer()) {
            assertThat(proxy.accepted()).as("configuration and class inspection remain offline").isZero();
            assertThat(raw.created).isEmpty();
            assertThat(tls.layeredOn).isEmpty();
            assertThat(AuthenticatedSocksFactories.RawClass.lookups).hasValue(0);
            assertThat(AuthenticatedSocksFactories.TlsClass.lookups).hasValue(0);
            if (factories.name().startsWith("TRANSPORT_") && !factories.rawClass) {
                assertThat(mailer.getSession().getProperties().get(prefix + ".socketFactory.transport"))
                        .isInstanceOf(SocketFactoryConfiguration.class);
                assertThat(((SocketFactoryConfiguration) mailer.getSession().getProperties().get(prefix + ".socketFactory.transport"))
                        .configuredInstance()).as("the transport decorator preserves the exact caller resource identity").isSameAs(raw);
            }
            if (original != null) {
                assertThat(mailer.getSession()).isSameAs(original);
                assertThat(original.getProvider(transport(protocol))).isSameAs(originalProvider);
            }
            mailer.rehearse(email());
            assertThat(raw.created).isEmpty();
            assertThat(tls.layeredOn).isEmpty();
            assertThat(proxy.accepted()).isZero();
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(mailer.sync().probeConnection().isSuccessful()).isTrue();
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(proxy.accepted()).as("pooled sender survives its independent probe").isEqualTo(2);
            mailer.withOpenConnection(sender -> {
                assertThat(sender.sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
                assertThat(sender.sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            });
            assertThat(proxy.accepted()).as("retained scope owns a separate physical connection").isEqualTo(3);
            assertThat(proxy.messages()).isEqualTo(4);
            final boolean selectedRaw = factories.raw && !(protocol == AuthenticatedSocksTestPeer.Protocol.SMTPS && factories.tls);
            if (selectedRaw) {
                assertThat(raw.created).hasSize(3).allSatisfy(socket -> {
                    assertThat(socket.getInetAddress().getHostAddress()).as("the application physical socket connects to the proxy").isEqualTo("127.0.0.1");
                    assertThat(socket.getPort()).isEqualTo(proxy.port());
                });
                assertThat(raw.created.get(0).getSoTimeout()).as("the SOCKS setup budget must not replace the ordinary read timeout").isEqualTo(5000);
            } else { assertThat(raw.created).isEmpty(); }
            assertThat(ignoredRaw.created).as("transport slot takes precedence over an ordinary raw factory").isEmpty();
            assertThat(ignoredTls.layeredOn).as("the SSL slot takes precedence over SSL in the regular slot").isEmpty();
            if (factories.tls) {
                assertThat(tls.layeredOn).hasSize(3).allSatisfy(socket -> assertThat(socket.isConnected()).isTrue());
                assertThat(tls.peers).containsOnly("localhost:2525");
                assertThat(tls.unconnectedCreations).as("TLS is layered after SOCKS CONNECT").hasValue(0);
                // JSSE can omit automatic SNI for a single-label localhost name; the dotted-host rejection above requires original SNI.
                assertThat(proxy.serverNames()).allMatch("localhost"::equals);
            }
            // Only the independent transport class promises one lookup per physical connection. Legacy getters remain native.
            if (factories.rawClass && factories.name().startsWith("TRANSPORT_")) {
                assertThat(AuthenticatedSocksFactories.RawClass.lookups).hasValue(3);
            }
            if (original != null) { assertThat(original.getProvider(transport(protocol))).isSameAs(originalProvider); }
            proxy.assertHealthy();
            assertDirectRoute(mailer, proxy, protocol);
        } finally {
            AuthenticatedSocksFactories.selectClassFactories(null, null);
        }
    }

    @ParameterizedTest(name = "hostname callback {0}, suppliedSession={1}")
    @MethodSource("tlsRoutes")
    void aCustomHostnameVerifierReceivesTheOriginalHostAndCanRejectWithoutARetry(
            final AuthenticatedSocksTestPeer.Protocol protocol, final boolean supplied) throws Exception {
        final Properties properties = new Properties();
        final java.util.List<String> checked = new java.util.ArrayList<>();
        properties.put(prefix(protocol) + ".ssl.hostnameverifier", (HostnameVerifier) (host, session) -> {
            checked.add(host);
            return false;
        });
        properties.setProperty(prefix(protocol) + ".socketFactory.fallback", "true");
        properties.put(prefix(protocol) + ".ssl.socketFactory", AuthenticatedSocksTestPeer.trustedClientTlsFactory());
        final Session original = supplied ? session(protocol, "localhost", properties) : null;
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol);
             Mailer mailer = builder(proxy, protocol, original, "localhost", false)
                     .withProperties(supplied ? new Properties() : properties).buildMailer()) {
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email()));
            assertThat(failure).isInstanceOf(MailException.class).hasStackTraceContaining("Unable to check server identity for: localhost");
            assertThat(checked).containsExactly("localhost");
            assertThat(proxy.accepted()).isEqualTo(1);
            assertThat(proxy.authenticated()).isEqualTo(1);
            assertThat(proxy.messages()).isZero();
            assertDirectRoute(mailer, proxy, protocol);
        }
    }

    @ParameterizedTest(name = "authentication rejection {0}, suppliedSession={1}")
    @MethodSource("defaultRoutes")
    void proxyAuthenticationFailureNeverFallsBackOrConnectsAgain(final AuthenticatedSocksTestPeer.Protocol protocol,
            final boolean supplied) throws Exception {
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol, false);
             Mailer mailer = builder(proxy, protocol, supplied)
                     .withProperty(prefix(protocol) + ".socketFactory.fallback", "true").buildMailer()) {
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email()));
            assertThat(failure).isInstanceOf(MailException.class);
            assertThat(proxy.accepted()).isEqualTo(1);
            assertThat(proxy.authenticationAttempts()).isEqualTo(1);
            assertThat(proxy.authenticated()).isZero();
            assertThat(proxy.destinations()).isEmpty();
            assertThat(proxy.messages()).isZero();
            proxy.assertHealthy();
            assertDirectRoute(mailer, proxy, protocol);
        }
    }

    @ParameterizedTest(name = "transport class lock {0}, suppliedSession={1}")
    @MethodSource("defaultRoutes")
    void aLockedTransportFactoryClassSurvivesDecorationAndCannotBeReplaced(final AuthenticatedSocksTestPeer.Protocol protocol,
            final boolean supplied) throws Exception {
        final String key = prefix(protocol) + ".socketFactory.transport.class";
        final String className = AuthenticatedSocksFactories.RawClass.class.getName();
        final AuthenticatedSocksFactories.Raw application = new AuthenticatedSocksFactories.Raw();
        AuthenticatedSocksFactories.selectClassFactories(application, null);
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder()
                .withMap(java.util.Map.of("simplejavamail.locked.extraproperties." + key, className)).load());
        final Properties properties = new Properties();
        properties.setProperty(key, className);
        final Session original = supplied ? session(protocol, "localhost", properties) : null;
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol);
             Mailer mailer = builder(mail, proxy, protocol, original, "localhost", true)
                     .withProperties(supplied ? new Properties() : properties).buildMailer()) {
            assertThat(application.created).isEmpty();
            assertThat(AuthenticatedSocksFactories.RawClass.lookups).hasValue(0);
            assertThat(mailer.getSession().getProperty(key)).isEqualTo(className);
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(application.created).hasSize(1);
            assertThat(mailer.sync().probeConnection().isSuccessful()).isTrue();
            assertThat(application.created).hasSize(2);
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            final int before = proxy.accepted();
            mailer.getSession().getProperties().setProperty(key, "application.ReplacedLockedTransportFactory");
            try {
                final Throwable rejected = catchThrowable(() -> mailer.sync().sendMail(email()));
                assertThat(rejected).isNotNull().hasStackTraceContaining("simplejavamail.locked.extraproperties." + key);
                assertThat(proxy.accepted()).isEqualTo(before);
                assertThat(proxy.messages()).isEqualTo(2);
            } finally { mailer.getSession().getProperties().setProperty(key, className); }
            proxy.assertHealthy();
        } finally { AuthenticatedSocksFactories.selectClassFactories(null, null); }
    }

    @ParameterizedTest(name = "explicit caller provider {0}")
    @MethodSource("protocols")
    void anExplicitlyRegisteredAngusProviderRetainsTheCallerSessionAndProviderIdentity(
            final AuthenticatedSocksTestPeer.Protocol protocol) throws Exception {
        final Session original = session(protocol, "localhost", new Properties());
        final Provider selected = new Provider(Provider.Type.TRANSPORT, transport(protocol), protocol == AuthenticatedSocksTestPeer.Protocol.SMTPS
                ? "org.eclipse.angus.mail.smtp.SMTPSSLTransport" : "org.eclipse.angus.mail.smtp.SMTPTransport", "application selection", "explicit");
        original.addProvider(selected);
        original.setProvider(selected);
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol);
             Mailer mailer = builder(proxy, protocol, original, "localhost", true).buildMailer()) {
            assertThat(mailer.getSession()).isSameAs(original);
            assertThat(original.getProvider(transport(protocol))).isSameAs(selected);
            assertThat(original.getProperties()).doesNotContainKeys("mail.mime.allowutf8", prefix(protocol) + ".executor.writetimeout");
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(mailer.sync().probeConnection().isSuccessful()).isTrue();
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(original.getProvider(transport(protocol))).isSameAs(selected);
            assertThat(proxy.messages()).isEqualTo(2);
            assertThat(proxy.accepted()).isEqualTo(2);
            proxy.assertHealthy();
            assertDirectRoute(mailer, proxy, protocol);
        }
    }

    @ParameterizedTest(name = "independent failover proxy {0}")
    @MethodSource("protocols")
    void configuredFailoverAndPrimaryDiagnosticsKeepTheirOwnProxyAndPoolState(final AuthenticatedSocksTestPeer.Protocol protocol) throws Exception {
        try (AuthenticatedSocksTestPeer unavailable = new AuthenticatedSocksTestPeer(protocol);
             AuthenticatedSocksTestPeer healthy = new AuthenticatedSocksTestPeer(protocol);
             Mailer alternate = builder(healthy, protocol, false).buildMailer()) {
            unavailable.refuseConnections();
            try (Mailer initiating = builder(unavailable, protocol, false).withFailoverMailer(alternate,
                    FailoverPolicy.builder().withPrimaryConnectionAttempts(1).withPrimaryRecheckInterval(Duration.ofHours(1)).build()).buildMailer()) {
                assertThat(initiating.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
                assertThat(unavailable.accepted()).as("the primary's actual proxy endpoint refused TCP before authentication").isZero();
                assertThat(unavailable.messages()).isZero();
                assertThat(healthy.accepted()).isEqualTo(1);
                assertThat(healthy.messages()).isEqualTo(1);
                assertThat(initiating.sync().probeConnection().isSuccessful()).as("a dedicated probe still examines its configured primary").isFalse();
                assertThat(unavailable.accepted()).isZero();
                assertThat(healthy.accepted()).as("primary probe closure cannot dispose the selected alternate's pooled socket").isEqualTo(1);
                assertThat(initiating.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
                assertThat(healthy.accepted()).isEqualTo(1);
                assertThat(healthy.messages()).isEqualTo(2);
                assertDirectRoute(initiating, unavailable, protocol);
                assertDirectRoute(alternate, healthy, protocol);
            }
            assertThat(alternate.sync().sendMail(email()).getStatus()).as("the borrowed failover Mailer remains usable after the initiator closes")
                    .isEqualTo(ACCEPTED);
            assertThat(healthy.accepted()).isEqualTo(1);
            assertThat(healthy.messages()).isEqualTo(3);
            unavailable.assertHealthy();
            healthy.assertHealthy();
        }
    }

    @ParameterizedTest(name = "proxy failure preserves failover evidence boundary {0}")
    @MethodSource("protocols")
    void aGenericProxySetupFailureCannotAuthorizeSendingToAnotherDestination(final AuthenticatedSocksTestPeer.Protocol protocol) throws Exception {
        try (AuthenticatedSocksTestPeer unavailable = new AuthenticatedSocksTestPeer(protocol, true, 4);
             AuthenticatedSocksTestPeer healthy = new AuthenticatedSocksTestPeer(protocol);
             Mailer alternate = builder(healthy, protocol, false).buildMailer();
             Mailer initiating = builder(unavailable, protocol, false).withFailoverMailer(alternate,
                     FailoverPolicy.builder().withPrimaryConnectionAttempts(1).build()).buildMailer()) {
            final Throwable failure = catchThrowable(() -> initiating.sync().sendMail(email()));
            assertThat(failure).isInstanceOf(MailException.class).hasStackTraceContaining("Host unreachable");
            assertThat(unavailable.accepted()).isEqualTo(1);
            assertThat(unavailable.messages()).isZero();
            assertThat(healthy.accepted()).as("a generic SOCKS protocol failure does not invent failover availability evidence").isZero();
            assertThat(alternate.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(healthy.messages()).isEqualTo(1);
            unavailable.assertHealthy();
            healthy.assertHealthy();
        }
    }

    @ParameterizedTest(name = "generic-return legacy TLS {0}, slot={1}, suppliedSession={2}")
    @MethodSource("genericTlsClasses")
    void aGenericLegacyClassCanSelectAnSslFactoryAtRuntimeWithoutBeingShadowed(final AuthenticatedSocksTestPeer.Protocol protocol,
            final String slot, final boolean supplied) throws Exception {
        final AuthenticatedSocksFactories.Tls tls = new AuthenticatedSocksFactories.Tls(AuthenticatedSocksTestPeer.trustedClientTlsFactory());
        AuthenticatedSocksFactories.GenericTlsClass.application = tls;
        AuthenticatedSocksFactories.GenericTlsClass.lookups.set(0);
        final String key = prefix(protocol) + "." + slot;
        final Properties properties = new Properties();
        properties.setProperty(key + ".class", AuthenticatedSocksFactories.GenericTlsClass.class.getName());
        final Session original = supplied ? session(protocol, "localhost", properties) : null;
        final AuthenticatedSocksTestPeer.Protocol peerProtocol = slot.equals("socketFactory")
                ? AuthenticatedSocksTestPeer.Protocol.SMTPS : protocol;
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(peerProtocol);
             Mailer mailer = builder(proxy, protocol, original, "localhost", false)
                     .withProperties(supplied ? new Properties() : properties).buildMailer()) {
            assertThat(mailer.getSession().getProperties().get(key)).as("a generic-return legacy class keeps native runtime selection").isNull();
            assertThat(AuthenticatedSocksFactories.GenericTlsClass.lookups).hasValue(0);
            assertThat(proxy.accepted()).isZero();
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(mailer.sync().probeConnection().isSuccessful()).isTrue();
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(proxy.accepted()).isEqualTo(2);
            assertThat(proxy.messages()).isEqualTo(2);
            assertThat(tls.layeredOn).hasSize(2);
            assertThat(tls.peers).containsOnly("localhost:2525");
            assertThat(tls.unconnectedCreations).hasValue(0);
            assertDirectRoute(mailer, proxy, protocol);
            proxy.assertHealthy();
        } finally { AuthenticatedSocksFactories.GenericTlsClass.application = null; }
    }

    @ParameterizedTest(name = "legacy fallback {0}, slot={1}, suppliedSession={2}, explicitFallback={3}")
    @MethodSource("legacyFallbackClasses")
    void nativeLegacyResolutionFallbackKeepsTheOriginalPortAndAuthenticatedProxy(final AuthenticatedSocksTestPeer.Protocol protocol,
            final String slot, final boolean supplied, final boolean explicitFallback) throws Exception {
        final Properties properties = new Properties();
        properties.setProperty(prefix(protocol) + "." + slot + ".class", "application.MissingLegacySocketFactory");
        properties.setProperty(prefix(protocol) + "." + slot + ".port", "42424");
        if (explicitFallback) { properties.setProperty(prefix(protocol) + ".socketFactory.fallback", "true"); }
        final Session original = supplied ? session(protocol, "localhost", properties) : null;
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol);
             Mailer mailer = builder(proxy, protocol, original, "localhost", true)
                     .withProperties(supplied ? new Properties() : properties).buildMailer()) {
            assertThat(mailer.getSession().getProperty(prefix(protocol) + ".socketFactory.fallback"))
                    .as("supplied absence keeps native fallback; explicit true remains explicit").isEqualTo(explicitFallback ? "true" : null);
            assertThat(proxy.accepted()).isZero();
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(mailer.sync().probeConnection().isSuccessful()).isTrue();
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(proxy.accepted()).as("resolution fallback still uses one authenticated physical socket per connection").isEqualTo(2);
            assertThat(proxy.authenticated()).isEqualTo(2);
            assertThat(proxy.messages()).isEqualTo(2);
            assertThat(proxy.destinations()).hasSize(2).allMatch(destination -> destination.endsWith(":2525"));
            assertDirectRoute(mailer, proxy, protocol);
            proxy.assertHealthy();
        }
    }

    @ParameterizedTest(name = "owned legacy fallback default {0}")
    @MethodSource("protocols")
    void ownedLegacyFactoryResolutionKeepsItsHistoricalStrictFallbackDefault(final AuthenticatedSocksTestPeer.Protocol protocol) throws Exception {
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol);
             Mailer mailer = builder(proxy, protocol, false)
                     .withProperty(prefix(protocol) + ".socketFactory.class", "application.MissingOwnedSocketFactory").buildMailer()) {
            assertThat(mailer.getSession().getProperty(prefix(protocol) + ".socketFactory.fallback")).isEqualTo("false");
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email()));
            assertThat(failure).isInstanceOf(MailException.class).hasRootCauseInstanceOf(ClassNotFoundException.class);
            assertThat(proxy.accepted()).as("the owned strict default fails before creating the proxy transport").isZero();
            assertDirectRoute(mailer, proxy, protocol);
        }
    }

    @ParameterizedTest(name = "null SSL factory falls through, suppliedSession={0}, fallback={1}, rawClass={2}")
    @MethodSource("nullSslFactoryClasses")
    void aNullSslFactoryClassSelectsTheActualRegularRawFactory(final boolean supplied, final boolean fallback,
            final boolean rawClass) throws Exception {
        final AuthenticatedSocksTestPeer.Protocol protocol = AuthenticatedSocksTestPeer.Protocol.SMTPS;
        final String prefix = prefix(protocol);
        final AuthenticatedSocksFactories.Raw raw = new AuthenticatedSocksFactories.Raw();
        AuthenticatedSocksFactories.selectClassFactories(raw, null);
        AuthenticatedSocksFactories.NullClass.lookups.set(0);
        final Properties properties = new Properties();
        properties.setProperty(prefix + ".ssl.socketFactory.class", AuthenticatedSocksFactories.NullClass.class.getName());
        properties.setProperty(prefix + ".ssl.socketFactory.port", "42424");
        properties.setProperty(prefix + ".socketFactory.fallback", Boolean.toString(fallback));
        if (rawClass) { properties.setProperty(prefix + ".socketFactory.class", AuthenticatedSocksFactories.RawClass.class.getName()); }
        else { properties.put(prefix + ".socketFactory", raw); }
        final Session original = supplied ? session(protocol, "localhost", properties) : null;
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol);
             Mailer mailer = builder(proxy, protocol, original, "localhost", true)
                     .withProperties(supplied ? new Properties() : properties).buildMailer()) {
            assertThat(AuthenticatedSocksFactories.NullClass.lookups).hasValue(0);
            assertThat(AuthenticatedSocksFactories.RawClass.lookups).hasValue(0);
            assertThat(raw.created).isEmpty();
            assertThat(proxy.accepted()).isZero();
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(mailer.sync().probeConnection().isSuccessful()).isTrue();
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(raw.created).as("a successful null SSL lookup falls through to the real regular raw delegate").hasSize(2)
                    .allSatisfy(socket -> assertThat(socket.getPort()).isEqualTo(proxy.port()));
            assertThat(proxy.accepted()).isEqualTo(2);
            assertThat(proxy.authenticated()).isEqualTo(2);
            assertThat(proxy.messages()).isEqualTo(2);
            assertThat(proxy.tlsHandshakes()).isEqualTo(2);
            assertThat(proxy.destinations()).hasSize(2).allMatch(destination -> destination.endsWith(":2525"));
            assertDirectRoute(mailer, proxy, protocol);
            proxy.assertHealthy();
        } finally { AuthenticatedSocksFactories.selectClassFactories(null, null); }
    }

    @ParameterizedTest(name = "null regular factory {0}, suppliedSession={1}, fallback={2}")
    @MethodSource("nullRegularFactoryClasses")
    void aNullRegularFactoryClassKeepsTheDefaultSocketAndOriginalPort(final AuthenticatedSocksTestPeer.Protocol protocol,
            final boolean supplied, final boolean fallback) throws Exception {
        AuthenticatedSocksFactories.NullClass.lookups.set(0);
        final Properties properties = new Properties();
        properties.setProperty(prefix(protocol) + ".socketFactory.class", AuthenticatedSocksFactories.NullClass.class.getName());
        properties.setProperty(prefix(protocol) + ".socketFactory.port", "42424");
        properties.setProperty(prefix(protocol) + ".socketFactory.fallback", Boolean.toString(fallback));
        final Session original = supplied ? session(protocol, "localhost", properties) : null;
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol);
             Mailer mailer = builder(proxy, protocol, original, "localhost", true)
                     .withProperties(supplied ? new Properties() : properties).buildMailer()) {
            assertThat(AuthenticatedSocksFactories.NullClass.lookups).hasValue(0);
            assertThat(proxy.accepted()).isZero();
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(mailer.sync().probeConnection().isSuccessful()).isTrue();
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(proxy.accepted()).isEqualTo(2);
            assertThat(proxy.authenticated()).isEqualTo(2);
            assertThat(proxy.messages()).isEqualTo(2);
            assertThat(proxy.tlsHandshakes()).isEqualTo(protocol == AuthenticatedSocksTestPeer.Protocol.SMTP ? 0 : 2);
            assertThat(proxy.destinations()).as("a null factory ignores that unselected factory's port override").hasSize(2)
                    .allMatch(destination -> destination.endsWith(":2525"));
            assertDirectRoute(mailer, proxy, protocol);
            proxy.assertHealthy();
        }
    }

    @ParameterizedTest(name = "successful null raw socket {0}, suppliedSession={1}, classFactory={2}, fallback={3}")
    @MethodSource("nullLegacySocketFactories")
    void aSuccessfulNullLegacySocketUsesTheDefaultPhysicalSocket(final AuthenticatedSocksTestPeer.Protocol protocol,
            final boolean supplied, final boolean classFactory, final boolean fallback) throws Exception {
        final AuthenticatedSocksFactories.NullSocket application = new AuthenticatedSocksFactories.NullSocket();
        AuthenticatedSocksFactories.NullSocketClass.application = application;
        AuthenticatedSocksFactories.NullSocketClass.lookups.set(0);
        final Properties properties = new Properties();
        properties.setProperty(prefix(protocol) + ".socketFactory.fallback", Boolean.toString(fallback));
        if (classFactory) { properties.setProperty(prefix(protocol) + ".socketFactory.class", AuthenticatedSocksFactories.NullSocketClass.class.getName()); }
        else { properties.put(prefix(protocol) + ".socketFactory", application); }
        final Session original = supplied ? session(protocol, "localhost", properties) : null;
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol);
             Mailer mailer = builder(proxy, protocol, original, "localhost", true)
                     .withProperties(supplied ? new Properties() : properties).buildMailer()) {
            assertThat(AuthenticatedSocksFactories.NullSocketClass.lookups).hasValue(0);
            assertThat(application.calls).hasValue(0);
            assertThat(proxy.accepted()).isZero();
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(mailer.sync().probeConnection().isSuccessful()).isTrue();
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(application.calls).as("the legacy creator declines exactly once for each actual physical connection").hasValue(2);
            assertThat(proxy.accepted()).isEqualTo(2);
            assertThat(proxy.authenticated()).isEqualTo(2);
            assertThat(proxy.messages()).isEqualTo(2);
            assertThat(proxy.tlsHandshakes()).isEqualTo(protocol == AuthenticatedSocksTestPeer.Protocol.SMTP ? 0 : 2);
            assertThat(proxy.destinations()).hasSize(2).allMatch(destination -> destination.endsWith(":2525"));
            assertDirectRoute(mailer, proxy, protocol);
            proxy.assertHealthy();
        } finally { AuthenticatedSocksFactories.NullSocketClass.application = null; }
    }

    @ParameterizedTest(name = "strict independent null {0}, classFactory={1}, legacyFallback={2}")
    @MethodSource("nullIndependentTransportFactories")
    void anIndependentTransportCannotDeclineItsSocketThroughLegacyFallback(final AuthenticatedSocksTestPeer.Protocol protocol,
            final boolean classFactory, final boolean fallback) throws Exception {
        final AuthenticatedSocksFactories.NullSocket application = new AuthenticatedSocksFactories.NullSocket();
        AuthenticatedSocksFactories.NullClass.lookups.set(0);
        final Properties properties = new Properties();
        properties.setProperty(prefix(protocol) + ".socketFactory.fallback", Boolean.toString(fallback));
        final String key = prefix(protocol) + ".socketFactory.transport";
        if (classFactory) { properties.setProperty(key + ".class", AuthenticatedSocksFactories.NullClass.class.getName()); }
        else { properties.put(key, application); }
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol);
             Mailer mailer = builder(proxy, protocol, false).withProperties(properties).buildMailer()) {
            assertThat(AuthenticatedSocksFactories.NullClass.lookups).hasValue(0);
            assertThat(application.calls).hasValue(0);
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email()));
            assertThat(failure).isInstanceOf(MailException.class)
                    .hasStackTraceContaining(classFactory ? key + ".class" : "returned no socket");
            assertThat(AuthenticatedSocksFactories.NullClass.lookups).hasValue(classFactory ? 1 : 0);
            assertThat(application.calls).hasValue(classFactory ? 0 : 1);
            assertThat(proxy.accepted()).as("legacy fallback cannot substitute another independent physical transport").isZero();
            assertDirectRoute(mailer, proxy, protocol);
            proxy.assertHealthy();
        }
    }

    @ParameterizedTest(name = "opaque abort boundary {0}, slot={1}, classFactory={2}")
    @MethodSource("opaqueRawFactories")
    void anOpaqueApplicationFactoryDoesNotGainPhysicalAbortSupport(final AuthenticatedSocksTestPeer.Protocol protocol,
            final String slot, final boolean classFactory) throws Exception {
        final AuthenticatedSocksFactories.Opaque opaque = new AuthenticatedSocksFactories.Opaque();
        AuthenticatedSocksFactories.OpaqueClass.application = opaque;
        AuthenticatedSocksFactories.OpaqueClass.lookups.set(0);
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol);
             Mailer mailer = builder(proxy, protocol, false).withProperty(prefix(protocol) + "." + slot + (classFactory ? ".class" : ""),
                     classFactory ? AuthenticatedSocksFactories.OpaqueClass.class.getName() : opaque).buildMailer();
             jakarta.mail.Transport transport = mailer.getSession().getTransport()) {
            assertThat(MailTransportLifecycleResolver.findAbortAction(transport))
                    .as("a transport decorator cannot promise physical abort for opaque application creation/connect behavior").isEmpty();
            assertThat(opaque.calls).hasValue(0);
            assertThat(AuthenticatedSocksFactories.OpaqueClass.lookups).hasValue(0);
            assertThat(proxy.accepted()).isZero();
        } finally { AuthenticatedSocksFactories.OpaqueClass.application = null; }
    }

    @ParameterizedTest(name = "opaque deadline boundary {0}, slot={1}, classFactory={2}")
    @MethodSource("opaqueRawFactories")
    void anOpaqueApplicationFactoryDeadlineIsRejectedBeforeItsCodeRuns(final AuthenticatedSocksTestPeer.Protocol protocol,
            final String slot, final boolean classFactory) throws Exception {
        final AuthenticatedSocksFactories.Opaque opaque = new AuthenticatedSocksFactories.Opaque();
        AuthenticatedSocksFactories.OpaqueClass.application = opaque;
        AuthenticatedSocksFactories.OpaqueClass.lookups.set(0);
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol)) {
            final Throwable failure = catchThrowable(() -> {
                try (Mailer mailer = builder(proxy, protocol, false).withProperty(prefix(protocol) + "." + slot + (classFactory ? ".class" : ""),
                        classFactory ? AuthenticatedSocksFactories.OpaqueClass.class.getName() : opaque)
                        .withMailSendTimeout(Duration.ofSeconds(1)).buildMailer()) {
                    mailer.sync().sendMail(email());
                }
            });
            assertThat(failure).isInstanceOf(MailException.class).hasMessageContaining("can't stop its network calls");
            assertThat(((MailException) failure).getKind()).isEqualTo(MailFailureKind.CONFIGURATION);
            assertThat(opaque.calls).hasValue(0);
            assertThat(AuthenticatedSocksFactories.OpaqueClass.lookups).hasValue(0);
            assertThat(proxy.accepted()).isZero();
        } finally { AuthenticatedSocksFactories.OpaqueClass.application = null; }
    }

    @ParameterizedTest(name = "custom TLS ownership boundary {0}, slot={1}, classFactory={2}")
    @MethodSource("customTlsOwnership")
    void customTlsLayeringDoesNotAcquirePhysicalAbortOrTotalDeadlineSupport(final AuthenticatedSocksTestPeer.Protocol protocol,
            final String slot, final boolean classFactory) throws Exception {
        final AuthenticatedSocksFactories.Tls application = new AuthenticatedSocksFactories.Tls(AuthenticatedSocksTestPeer.trustedClientTlsFactory());
        AuthenticatedSocksFactories.selectClassFactories(null, application);
        final Properties properties = new Properties();
        properties.put(prefix(protocol) + "." + slot + (classFactory ? ".class" : ""),
                classFactory ? AuthenticatedSocksFactories.TlsClass.class.getName() : application);
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol)) {
            try (Mailer mailer = builder(proxy, protocol, false).withProperties(properties).buildMailer();
                 jakarta.mail.Transport transport = mailer.getSession().getTransport()) {
                assertThat(MailTransportLifecycleResolver.findAbortAction(transport))
                        .as("arbitrary application TLS layering retains its existing ownership boundary").isEmpty();
            }
            final Throwable failure = catchThrowable(() -> {
                try (Mailer mailer = builder(proxy, protocol, false).withProperties(properties)
                        .withMailSendTimeout(Duration.ofSeconds(1)).buildMailer()) {
                    mailer.sync().sendMail(email());
                }
            });
            assertThat(failure).isInstanceOf(MailException.class).hasMessageContaining("can't stop its network calls");
            assertThat(((MailException) failure).getKind()).isEqualTo(MailFailureKind.CONFIGURATION);
            assertThat(application.layeredOn).isEmpty();
            assertThat(application.unconnectedCreations).hasValue(0);
            assertThat(AuthenticatedSocksFactories.TlsClass.lookups).hasValue(0);
            assertThat(proxy.accepted()).isZero();
        } finally { AuthenticatedSocksFactories.selectClassFactories(null, null); }
    }

    @ParameterizedTest(name = "MailSSLSocketFactory {0}, suppliedSession={1}, classFactory={2}, trusted={3}")
    @MethodSource("trustedHostFactories")
    void theActualMailSslFactoryTrustCallbackStillControlsAcceptance(final AuthenticatedSocksTestPeer.Protocol protocol,
            final boolean supplied, final boolean classFactory, final boolean trusted) throws Exception {
        final AuthenticatedSocksFactories.TrustedHosts application = new AuthenticatedSocksFactories.TrustedHosts(trusted);
        AuthenticatedSocksFactories.selectClassFactories(null, application);
        final Properties properties = new Properties();
        final String key = prefix(protocol) + ".ssl.socketFactory";
        if (classFactory) { properties.setProperty(key + ".class", AuthenticatedSocksFactories.TlsClass.class.getName()); }
        else { properties.put(key, application); }
        // Deliberate fallback still cannot retry after the selected socket has authenticated or begun TLS.
        properties.setProperty(prefix(protocol) + ".socketFactory.fallback", "true");
        final Session original = supplied ? session(protocol, "localhost", properties) : null;
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol);
             Mailer mailer = builder(proxy, protocol, original, "localhost", false)
                     .withProperties(supplied ? new Properties() : properties).buildMailer()) {
            if (trusted) {
                assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
                assertThat(proxy.messages()).isEqualTo(1);
                proxy.assertHealthy();
            } else {
                final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email()));
                assertThat(failure).isInstanceOf(MailException.class).hasStackTraceContaining("Server is not trusted: localhost");
                assertThat(proxy.messages()).isZero();
            }
            assertThat(application.checkedHosts).as("Angus must retain the application's real trust callback").containsExactly("localhost");
            assertThat(proxy.accepted()).as("neither trust rejection nor declared fallback can reopen an authenticated connection").isEqualTo(1);
            assertThat(proxy.authenticated()).isEqualTo(1);
            assertDirectRoute(mailer, proxy, protocol);
        } finally {
            AuthenticatedSocksFactories.selectClassFactories(null, null);
        }
    }

    private static void assertDirectRoute(final Mailer mailer, final AuthenticatedSocksTestPeer proxy,
            final AuthenticatedSocksTestPeer.Protocol protocol) throws Exception {
        assertThat(MailTransportLifecycleResolver.supportsDirectAuthenticatedSocks(mailer.getSession()))
                .as("the merged-source lane must recognize the actual Angus transport capability").isTrue();
        final String prefix = protocol == AuthenticatedSocksTestPeer.Protocol.SMTPS ? "mail.smtps" : "mail.smtp";
        assertThat(mailer.getSession().getProperty(prefix + ".socks.host")).as("the Session exposes the real remote proxy").isEqualTo("127.0.0.1");
        assertThat(mailer.getSession().getProperty(prefix + ".socks.port")).as("no generated local port replaces the remote endpoint")
                .isEqualTo(Integer.toString(proxy.port()));
        assertThat(mailer.getSession().getProperties().get(prefix + ".socketFactory.transport"))
                .as("the merged Angus transport slot contains the tracked direct factory").isNotNull()
                .extracting(factory -> factory.getClass().getName()).isEqualTo("org.simplejavamail.internal.mailprovider.angus.AngusSocketFactory");
        try {
            final Field legacy = mailer.getClass().getDeclaredField("proxyServer");
            legacy.setAccessible(true);
            assertThat(legacy.get(mailer)).as("no anonymous loopback bridge object").isNull();
        } catch (NoSuchFieldException retired) {
            // The Session route and native hook assertions also cover implementations without this bookkeeping field.
        }
        assertThat(Thread.getAllStackTraces().keySet()).noneSatisfy(thread ->
                assertThat(thread.getName()).startsWith("simple-java-mail SOCKS5"));
    }

    private static MailerGenericBuilder<?> builder(final AuthenticatedSocksTestPeer proxy,
            final AuthenticatedSocksTestPeer.Protocol protocol, final boolean supplied) {
        return builder(proxy, protocol, supplied, "localhost", true);
    }

    private static MailerGenericBuilder<?> builder(final AuthenticatedSocksTestPeer proxy,
            final AuthenticatedSocksTestPeer.Protocol protocol, final boolean supplied,
            final String smtpHost, final boolean trustNamedHost) {
        return builder(proxy, protocol, supplied ? session(protocol, smtpHost, new Properties()) : null, smtpHost, trustNamedHost);
    }

    private static MailerGenericBuilder<?> builder(final AuthenticatedSocksTestPeer proxy,
            final AuthenticatedSocksTestPeer.Protocol protocol, final Session supplied,
            final String smtpHost, final boolean trustNamedHost) {
        return builder(SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()), proxy, protocol, supplied, smtpHost, trustNamedHost);
    }

    private static MailerGenericBuilder<?> builder(final SimpleJavaMail mail, final AuthenticatedSocksTestPeer proxy,
            final AuthenticatedSocksTestPeer.Protocol protocol, final Session supplied,
            final String smtpHost, final boolean trustNamedHost) {
        final MailerGenericBuilder<?> builder;
        if (supplied != null) {
            builder = mail.mailerBuilder(supplied);
        } else {
            builder = mail.mailerBuilder().withSMTPServer(smtpHost, 2525)
                    .withTransportStrategy(protocol == AuthenticatedSocksTestPeer.Protocol.SMTPS ? TransportStrategy.SMTPS
                            : protocol == AuthenticatedSocksTestPeer.Protocol.STARTTLS ? TransportStrategy.SMTP_TLS : TransportStrategy.SMTP);
        }
        return builder.withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("direct-route.example.test")
                        .withConnectTimeout(Duration.ofSeconds(5)).withReadTimeout(Duration.ofSeconds(5)).build())
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(2)
                        .withExpireAfter(Duration.ofMinutes(1)).build())
                .withTlsConfig((trustNamedHost ? TlsConfig.builder().trustingSSLHosts(smtpHost) : TlsConfig.builder())
                        .verifyingServerIdentity(true).build())
                .withProxyConfig(SocksProxyConfig.builder().withHost("127.0.0.1").withPort(proxy.port())
                        .withUsername("proxy-user").withPassword("proxy-secret").build());
    }

    private static Session session(final AuthenticatedSocksTestPeer.Protocol protocol, final String smtpHost, final Properties properties) {
        final String transport = transport(protocol);
        properties.setProperty("mail.transport.protocol", transport);
        properties.setProperty("mail." + transport + ".host", smtpHost);
        properties.setProperty("mail." + transport + ".port", "2525");
        if (protocol == AuthenticatedSocksTestPeer.Protocol.STARTTLS) {
            properties.setProperty("mail.smtp.starttls.enable", "true");
            properties.setProperty("mail.smtp.starttls.required", "true");
        }
        return Session.getInstance(properties);
    }

    private static String transport(final AuthenticatedSocksTestPeer.Protocol protocol) {
        return protocol == AuthenticatedSocksTestPeer.Protocol.SMTPS ? "smtps" : "smtp";
    }

    private static String prefix(final AuthenticatedSocksTestPeer.Protocol protocol) { return "mail." + transport(protocol); }

    private static Email email() {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("sender@example.org").to("reader@example.org").withSubject("Direct authenticated route")
                .withPlainText("The configured proxy is the actual physical route").buildEmail();
    }

    private static Stream<Arguments> defaultRoutes() {
        return Stream.of(AuthenticatedSocksTestPeer.Protocol.values()).flatMap(protocol ->
                Stream.of(Arguments.of(protocol, false), Arguments.of(protocol, true)));
    }

    private static Stream<AuthenticatedSocksTestPeer.Protocol> protocols() { return Stream.of(AuthenticatedSocksTestPeer.Protocol.values()); }

    private static Stream<Arguments> tlsFailures() {
        return Stream.of(AuthenticatedSocksTestPeer.Protocol.STARTTLS, AuthenticatedSocksTestPeer.Protocol.SMTPS).flatMap(protocol ->
                Stream.of(false, true).flatMap(supplied -> Stream.of(Arguments.of(protocol, supplied, false), Arguments.of(protocol, supplied, true))));
    }

    private static Stream<Arguments> factoryRoutes() {
        return Stream.of(AuthenticatedSocksTestPeer.Protocol.values()).flatMap(protocol -> Stream.of(Factories.values())
                .filter(factories -> !factories.tls || protocol != AuthenticatedSocksTestPeer.Protocol.SMTP)
                .filter(factories -> !factories.name().contains("RAW_SSL") || protocol == AuthenticatedSocksTestPeer.Protocol.SMTPS)
                .filter(factories -> factories != Factories.SSL_SLOT_WINS || protocol == AuthenticatedSocksTestPeer.Protocol.SMTPS)
                .flatMap(factories -> Stream.of(Arguments.of(protocol, false, factories), Arguments.of(protocol, true, factories))));
    }

    private static Stream<Arguments> trustedHostFactories() {
        return Stream.of(AuthenticatedSocksTestPeer.Protocol.STARTTLS, AuthenticatedSocksTestPeer.Protocol.SMTPS).flatMap(protocol ->
                Stream.of(false, true).flatMap(supplied -> Stream.of(false, true).flatMap(classFactory ->
                        Stream.of(false, true).map(trusted -> Arguments.of(protocol, supplied, classFactory, trusted)))));
    }

    private static Stream<Arguments> tlsRoutes() { return defaultRoutes().filter(arguments ->
            arguments.get()[0] != AuthenticatedSocksTestPeer.Protocol.SMTP); }

    private static Stream<Arguments> opaqueRawFactories() {
        return Stream.of(AuthenticatedSocksTestPeer.Protocol.values()).flatMap(protocol ->
                Stream.of("socketFactory", "socketFactory.transport", "ssl.socketFactory")
                        .filter(slot -> !slot.startsWith("ssl.") || protocol == AuthenticatedSocksTestPeer.Protocol.SMTPS)
                        .flatMap(slot -> Stream.of(Arguments.of(protocol, slot, false), Arguments.of(protocol, slot, true))));
    }

    private static Stream<Arguments> customTlsOwnership() {
        return Stream.of(AuthenticatedSocksTestPeer.Protocol.STARTTLS, AuthenticatedSocksTestPeer.Protocol.SMTPS).flatMap(protocol ->
                Stream.of("socketFactory", "ssl.socketFactory").flatMap(slot ->
                        Stream.of(Arguments.of(protocol, slot, false), Arguments.of(protocol, slot, true))));
    }

    private static Stream<Arguments> genericTlsClasses() {
        return Stream.of(AuthenticatedSocksTestPeer.Protocol.values()).flatMap(protocol -> Stream.of("socketFactory", "ssl.socketFactory")
                .filter(slot -> !slot.startsWith("ssl.") || protocol != AuthenticatedSocksTestPeer.Protocol.SMTP)
                .flatMap(slot -> Stream.of(Arguments.of(protocol, slot, false), Arguments.of(protocol, slot, true))));
    }

    private static Stream<Arguments> legacyFallbackClasses() {
        return Stream.of(AuthenticatedSocksTestPeer.Protocol.SMTP, AuthenticatedSocksTestPeer.Protocol.SMTPS).flatMap(protocol ->
                Stream.of("socketFactory", "ssl.socketFactory")
                        .filter(slot -> !slot.startsWith("ssl.") || protocol == AuthenticatedSocksTestPeer.Protocol.SMTPS)
                        .flatMap(slot -> Stream.of(Arguments.of(protocol, slot, false, true), Arguments.of(protocol, slot, true, true),
                                Arguments.of(protocol, slot, true, false))));
    }

    private static Stream<Arguments> nullSslFactoryClasses() {
        return Stream.of(false, true).flatMap(supplied -> Stream.of(false, true).flatMap(fallback ->
                Stream.of(false, true).map(rawClass -> Arguments.of(supplied, fallback, rawClass))));
    }

    private static Stream<Arguments> nullRegularFactoryClasses() {
        return Stream.of(AuthenticatedSocksTestPeer.Protocol.values()).flatMap(protocol -> Stream.of(false, true).flatMap(supplied ->
                Stream.of(false, true).map(fallback -> Arguments.of(protocol, supplied, fallback))));
    }

    private static Stream<Arguments> nullLegacySocketFactories() {
        return Stream.of(AuthenticatedSocksTestPeer.Protocol.SMTP, AuthenticatedSocksTestPeer.Protocol.SMTPS).flatMap(protocol ->
                Stream.of(false, true).flatMap(supplied -> Stream.of(false, true).flatMap(classFactory ->
                        Stream.of(false, true).map(fallback -> Arguments.of(protocol, supplied, classFactory, fallback)))));
    }

    private static Stream<Arguments> nullIndependentTransportFactories() {
        return Stream.of(AuthenticatedSocksTestPeer.Protocol.values()).flatMap(protocol -> Stream.of(false, true).flatMap(classFactory ->
                Stream.of(false, true).map(fallback -> Arguments.of(protocol, classFactory, fallback))));
    }

    private enum Factories {
        RAW_INSTANCE(true, false, false, false), RAW_CLASS(true, true, false, false), RAW_INSTANCE_OVER_CLASS(true, false, false, false),
        TLS_INSTANCE(false, false, true, false), TLS_CLASS(false, false, true, true), TLS_INSTANCE_OVER_CLASS(false, false, true, false),
        TLS_REGULAR_INSTANCE(false, false, true, false), TLS_REGULAR_CLASS(false, false, true, true),
        BOTH_INSTANCES(true, false, true, false), BOTH_CLASSES(true, true, true, true), SSL_SLOT_WINS(false, false, true, false),
        TRANSPORT_INSTANCE(true, false, false, false), TRANSPORT_CLASS(true, true, false, false),
        TRANSPORT_INSTANCE_OVER_CLASS(true, false, false, false), TRANSPORT_OVER_RAW(true, false, false, false),
        RAW_SSL_INSTANCE(true, false, false, false), RAW_SSL_CLASS(true, true, false, false),
        RAW_SSL_OVER_REGULAR(true, false, false, false), TRANSPORT_OVER_RAW_SSL(true, false, false, false);

        private final boolean raw;
        private final boolean rawClass;
        private final boolean tls;
        private final boolean tlsClass;
        Factories(final boolean raw, final boolean rawClass, final boolean tls, final boolean tlsClass) {
            this.raw = raw; this.rawClass = rawClass; this.tls = tls; this.tlsClass = tlsClass;
        }

        private Properties configure(final String prefix, final AuthenticatedSocksFactories.Raw raw,
                final AuthenticatedSocksFactories.Tls tls, final AuthenticatedSocksFactories.Raw ignoredRaw,
                final AuthenticatedSocksFactories.Tls ignoredTls) {
            final Properties properties = new Properties();
            if (this.raw) {
                final String key = prefix + (name().startsWith("RAW_SSL_") ? ".ssl.socketFactory" : ".socketFactory")
                        + (name().startsWith("TRANSPORT_") ? ".transport" : "");
                if (rawClass) { properties.setProperty(key + ".class", AuthenticatedSocksFactories.RawClass.class.getName()); }
                else { properties.put(key, raw); }
                if (this == RAW_INSTANCE_OVER_CLASS || this == TRANSPORT_INSTANCE_OVER_CLASS) {
                    properties.setProperty(key + ".class", "application.MissingUnusedFactory");
                }
                if (this == TRANSPORT_OVER_RAW) { properties.put(prefix + ".socketFactory", ignoredRaw); }
                if (this == RAW_SSL_OVER_REGULAR) { properties.put(prefix + ".socketFactory", ignoredRaw); }
                if (this == TRANSPORT_OVER_RAW_SSL) { properties.put(prefix + ".ssl.socketFactory", ignoredRaw); }
            }
            if (this.tls) {
                final String key = prefix + (this == TLS_REGULAR_INSTANCE || this == TLS_REGULAR_CLASS ? ".socketFactory" : ".ssl.socketFactory");
                if (tlsClass) { properties.setProperty(key + ".class", AuthenticatedSocksFactories.TlsClass.class.getName()); }
                else { properties.put(key, tls); }
                if (this == TLS_INSTANCE_OVER_CLASS) { properties.setProperty(key + ".class", "application.MissingUnusedTlsFactory"); }
                if (this == SSL_SLOT_WINS) { properties.put(prefix + ".socketFactory", ignoredTls); }
            }
            return properties;
        }
    }
}
