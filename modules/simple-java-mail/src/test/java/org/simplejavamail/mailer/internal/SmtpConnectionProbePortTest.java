package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.SmtpConnectionReport;
import org.simplejavamail.internal.util.SmtpProbeReports;
import testutil.ConfigLoaderTestHelper;
import testutil.smtp.SmtpConversation;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/** Endpoint facts must agree with the provider without rewriting a supplied Session. */
@Timeout(30)
class SmtpConnectionProbePortTest {

    @ParameterizedTest
    @EnumSource(PortRepresentation.class)
    void publicProbeReportsThePortActuallyUsedByAngus(final PortRepresentation representation) throws Exception {
        try (Peer peer = new Peer()) {
            final Properties defaults = new Properties();
            final Properties properties = connectionProperties(defaults);
            representation.configure(properties, defaults, peer.port());
            final Properties originalProperties = (Properties) properties.clone();
            final Properties originalDefaults = (Properties) defaults.clone();
            final Session session = Session.getInstance(properties);

            try (Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig())
                    .mailerBuilder(session).buildMailer()) {
                final Properties beforeProbe = (Properties) properties.clone();
                final SmtpConnectionReport report = mailer.sync().probeConnection();

                assertThat(report.isSuccessful()).as(report.toString()).isTrue();
                assertThat(report.isConnected()).isTrue();
                assertThat(report.getHost()).isEqualTo("localhost");
                assertThat(report.getPort()).isEqualTo(peer.port());
                assertThat(report.isAuthenticationRequested()).isFalse();
                assertThat(report.isAuthenticated()).isFalse();
                assertThat(session.getProperties()).isSameAs(properties);
                assertThat(properties).isEqualTo(beforeProbe);
                originalProperties.forEach((key, value) -> assertThat(properties.get(key)).isSameAs(value));
                assertThat(properties.get("mail.smtp.port")).isSameAs(originalProperties.get("mail.smtp.port"));
                assertThat(defaults).isEqualTo(originalDefaults);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"smtp,false,25", "smtp,true,465", "smtps,false,465", "other,false,-1"})
    void absentInvalidAndSentinelPortsRetainProtocolDefaults(final String protocol, final boolean implicitTls, final int expectedPort) {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", protocol);
        properties.setProperty("mail." + protocol + ".ssl.enable", Boolean.toString(implicitTls));
        assertPort(properties, expectedPort);
        for (final String invalid : new String[]{"invalid", "0X1234", "0x", " 1234", "-1", "0x-1", "2147483648"}) {
            properties.setProperty("mail." + protocol + ".port", invalid);
            assertPort(properties, expectedPort);
        }
        properties.put("mail." + protocol + ".port", -1);
        assertPort(properties, expectedPort);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void opaqueDirectPortMasksAnInheritedStringAsItDoesForAngus(final boolean implicitTls) {
        final Properties defaults = new Properties();
        defaults.setProperty("mail.smtp.port", "2523");
        final Properties properties = new Properties(defaults);
        properties.put("mail.smtp.port", new Object());
        properties.put("mail.smtp.ssl.enable", implicitTls);

        assertPort(properties, implicitTls ? 465 : 25);
        assertThat(properties.getProperty("mail.smtp.port")).isEqualTo("2523");
    }

    @Test
    void aDirectIntegerTakesPrecedenceOverInheritedStringPort() {
        final Properties defaults = new Properties();
        defaults.setProperty("mail.smtp.port", "0x9db");
        final Properties properties = new Properties(defaults);
        properties.put("mail.smtp.port", 2524);

        assertPort(properties, 2524);
    }

    private static void assertPort(final Properties properties, final int expectedPort) {
        final SmtpConnectionReport report = SmtpProbeReports.begin(Session.getInstance(properties), false)
                .completedAt(Instant.now()).build();
        assertThat(report.getPort()).isEqualTo(expectedPort == -1 ? null : expectedPort);
    }

    private static Properties connectionProperties(final Properties defaults) {
        final Properties properties = new Properties(defaults);
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.host", "localhost");
        properties.setProperty("mail.smtp.localhost", "probe.example.test");
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "5000");
        properties.setProperty("mail.smtp.starttls.enable", "false");
        return properties;
    }

    private enum PortRepresentation {
        DIRECT_HEXADECIMAL {
            @Override void configure(final Properties direct, final Properties defaults, final int port) {
                direct.setProperty("mail.smtp.port", "0x" + Integer.toHexString(port));
            }
        },
        INHERITED_HEXADECIMAL {
            @Override void configure(final Properties direct, final Properties defaults, final int port) {
                defaults.setProperty("mail.smtp.port", "0x" + Integer.toHexString(port));
            }
        },
        DIRECT_DECIMAL {
            @Override void configure(final Properties direct, final Properties defaults, final int port) {
                direct.setProperty("mail.smtp.port", Integer.toString(port));
            }
        },
        DIRECT_INTEGER {
            @Override void configure(final Properties direct, final Properties defaults, final int port) {
                direct.put("mail.smtp.port", port);
            }
        };

        abstract void configure(Properties direct, Properties defaults, int port);
    }

    private static final class Peer implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final CompletableFuture<Void> serving;

        private Peer() throws Exception {
            server = new ServerSocket(0, 1, InetAddress.getByName("localhost"));
            server.setSoTimeout(10000);
            serving = CompletableFuture.runAsync(() -> {
                try (SmtpConversation conversation = new SmtpConversation(server.accept())) {
                    conversation.greet("250-localhost\r\n250 SIZE 1024");
                    conversation.quit();
                } catch (Exception failure) {
                    throw new AssertionError("Scripted probe peer failed", failure);
                }
            }, worker);
        }

        private int port() { return server.getLocalPort(); }

        @Override
        public void close() throws Exception {
            try {
                serving.get(15, SECONDS);
            } finally {
                server.close();
                worker.shutdownNow();
                assertThat(worker.awaitTermination(15, SECONDS)).isTrue();
            }
        }
    }
}
