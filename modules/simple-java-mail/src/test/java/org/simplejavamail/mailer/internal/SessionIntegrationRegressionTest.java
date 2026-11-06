package org.simplejavamail.mailer.internal;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import jakarta.mail.event.ConnectionAdapter;
import jakarta.mail.event.ConnectionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.SmtpConnectionReport;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.SessionDebugConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.mailer.internal.util.TransportConnectionHelper;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Jakarta Mail construction, event and provider boundaries for the Session integration contract. */
@Timeout(30)
class SessionIntegrationRegressionTest {
    @Test
    void ownedSessionDeliversSessionEventsThroughTheRequestedExecutor() throws Exception {
        final RecordingExecutor executor = new RecordingExecutor();
        final Properties extra = eventProperties(executor, "session");
        try (Mailer mailer = factory().mailerBuilder().withSMTPServer("localhost", 25).withProperties(extra).buildMailer()) {
            assertThat(mailer.getSession().getProperties().get("mail.event.executor")).isSameAs(executor);
            assertEventDelivery(mailer.getSession(), executor);
        }
    }

    @Test
    void suppliedSessionRejectsALateSessionQueueExecutorBeforeMutation() {
        final Session session = Session.getInstance(new Properties());
        final RecordingExecutor executor = new RecordingExecutor();
        assertThatThrownBy(() -> {
            try (Mailer ignored = factory().mailerBuilder(session).withProperties(eventProperties(executor, "session")).buildMailer()) {
                // Close the unexpected successful construction as well.
            }
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mail.event.executor").hasMessageContaining("Session");
        assertThat(session.getProperties()).isEmpty();
    }

    @Test
    void preconfiguredSessionAndLateServiceQueueKeepTheirBorrowedExecutor() throws Exception {
        final RecordingExecutor preconfigured = new RecordingExecutor();
        final Session supplied = Session.getInstance(eventProperties(preconfigured, "session"));
        try (Mailer mailer = factory().mailerBuilder(supplied).withProperties(eventProperties(preconfigured, "session")).buildMailer()) {
            assertThat(mailer.getSession()).isSameAs(supplied);
            assertEventDelivery(supplied, preconfigured);
        }
        final RecordingExecutor late = new RecordingExecutor();
        final Session service = Session.getInstance(new Properties());
        try (Mailer mailer = factory().mailerBuilder(service).withProperties(eventProperties(late, "folder")).buildMailer()) {
            assertThat(mailer.getSession()).isSameAs(service);
            assertEventDelivery(service, late);
        }
    }

    @Test
    void debugCapturePreservesNativeFlagAndPrinterWithoutWritingToEitherDestination() throws Exception {
        final ByteArrayOutputStream applicationBytes = new ByteArrayOutputStream();
        final PrintStream printer = new PrintStream(applicationBytes, true, StandardCharsets.UTF_8.name());
        final Session session = Session.getInstance(new Properties());
        session.setDebugOut(printer);
        session.setDebug(true);
        session.getProperties().setProperty("mail.debug", "false");
        applicationBytes.reset();
        final ByteArrayOutputStream standardBytes = new ByteArrayOutputStream();
        final PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(standardBytes, true, StandardCharsets.UTF_8.name()));
            try (Mailer mailer = factory().mailerBuilder(session).buildMailer()) {
                assertThat(mailer.getSession()).isSameAs(session);
                assertThat(mailer.getOperationalConfig().getDebugConfig().getEnabled()).isTrue();
                assertThat(session.getDebugOut()).isSameAs(printer);
                assertThat(session.getProperty("mail.debug")).isEqualTo("false");
            }
        } finally {
            System.setOut(original);
        }
        assertThat(standardBytes.toString(StandardCharsets.UTF_8.name())).isEmpty();
        assertThat(applicationBytes.toString(StandardCharsets.UTF_8.name())).isEmpty();
    }

    @Test
    void ownedRawDebugDoesNotBootstrapOnStandardOutput() throws Exception {
        final ByteArrayOutputStream selectedBytes = new ByteArrayOutputStream();
        final PrintStream printer = new PrintStream(selectedBytes, true, StandardCharsets.UTF_8.name());
        final ByteArrayOutputStream standardBytes = new ByteArrayOutputStream();
        final PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(standardBytes, true, StandardCharsets.UTF_8.name()));
            try (Mailer mailer = factory().mailerBuilder().withSMTPServer("localhost", 25)
                    .withProperty("mail.debug", "true")
                    .withDebugConfig(SessionDebugConfig.builder().withEnabled(true).withPrinter(printer).build()).buildMailer()) {
                assertThat(mailer.getSession().getDebug()).isTrue();
                assertThat(mailer.getSession().getDebugOut()).isSameAs(printer);
                assertThat(mailer.getSession().getProperty("mail.debug")).isEqualTo("true");
            }
        } finally {
            System.setOut(original);
        }
        assertThat(standardBytes.toString(StandardCharsets.UTF_8.name())).isEmpty();
    }

    @Test
    void missingProtocolDoesNotInventSmtpInspectionOrProbeFacts() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", "wrong.example.test");
        properties.setProperty("mail.smtp.timeout", "1234");
        final Session supplied = Session.getInstance(properties);
        supplied.setProtocolForAddress("rfc822", "smtps");
        try (Mailer mailer = factory().mailerBuilder(supplied).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getReadTimeout()).isNull();
            final SmtpConnectionReport report = mailer.sync().probeConnection();
            assertThat(report.isSupported()).isFalse();
            assertThat(report.getFailureDescription()).contains("mail.transport.protocol");
            assertThat(report.getProtocol()).isEqualTo("unknown");
            assertThat(report.getHost()).isEqualTo("unknown");
            assertThat(report.getPort()).isNull();
        }
        assertThat(properties.getProperty("mail.transport.protocol")).isNull();
    }

    @Test
    void missingProtocolRejectsOnlyAnOperationThatNeedsItsNamespace() {
        final Session supplied = Session.getInstance(new Properties());
        supplied.setProtocolForAddress("rfc822", "smtps");
        assertThatThrownBy(() -> {
            try (Mailer ignored = factory().mailerBuilder(supplied)
                    .withConnectionConfig(ConnectionConfig.builder().withReadTimeout(Duration.ofSeconds(2)).build()).buildMailer()) {
            }
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mail.transport.protocol");
        assertThat(supplied.getProperties()).isEmpty();
    }

    @Test
    void nativeSendingStillUsesTheSuppliedAddressMappingWithoutAProtocolProperty() throws Exception {
        final AtomicInteger sends = new AtomicInteger();
        final Properties properties = new Properties();
        properties.put("test.native.sends", sends);
        properties.setProperty("mail.smtps.host", "localhost");
        final Session supplied = Session.getInstance(properties);
        final Provider selected = provider("smtps", NativeSendingTransport.class);
        supplied.addProvider(selected);
        supplied.setProvider(selected);
        supplied.setProtocolForAddress("rfc822", "smtps");
        final SimpleJavaMail mail = factory();
        try (Mailer mailer = mail.mailerBuilder(supplied).buildMailer()) {
            assertThat(sends.get()).isZero();
            mailer.sync().sendMail(mail.emailBuilder().startingBlank().from("sender@example.test").to("receiver@example.test")
                    .withSubject("Native selection").withPlainText("Keep Session selection").buildEmail());
            assertThat(sends.get()).isEqualTo(1);
            assertThat(supplied.getProvider("smtps")).isSameAs(selected);
            assertThat(properties.getProperty("mail.transport.protocol")).isNull();
        }
    }

    @Test
    void directOauthLeavesUrlUsernameSelectionToTheAlreadySelectedProvider() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.smtp.user", "inactive-user");
        properties.setProperty("mail.smtps.user", "protocol-user");
        properties.setProperty(TransportStrategy.OAUTH2_TOKEN_PROPERTY, "current-token");
        final Session session = Session.getInstance(properties);
        session.setProtocolForAddress("rfc822", "smtps");
        try (EventTransport transport = new EventTransport(session, new URLName("smtps", "localhost", 465, null, "url-user", null))) {
            TransportConnectionHelper.connectTransport(transport, session);
            assertThat(transport.username).isEqualTo("url-user");
            assertThat(transport.password).isEqualTo("current-token");
        }
    }

    @Test
    void activeProviderSelectionParticipatesInStrictConflictsAndDeliberateOverrides() throws Exception {
        for (final String protocol : new String[] {"smtp", "smtps"}) {
            final Properties properties = new Properties();
            properties.setProperty("mail.transport.protocol", protocol);
            final Session session = Session.getInstance(properties);
            final Provider original = provider(protocol, EventTransport.class);
            final Provider replacement = provider(protocol, AlternateTransport.class);
            session.addProvider(original);
            session.addProvider(replacement);
            session.setProvider(original);
            final String key = "mail." + protocol + ".class";
            assertThatThrownBy(() -> {
                try (Mailer ignored = factory().mailerBuilder(session).withProperty(key, replacement.getClassName()).buildMailer()) {
                }
            }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key);
            assertThat(session.getProvider(protocol)).isSameAs(original);
            try (Mailer mailer = factory().mailerBuilder(session)
                    .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                    .withProperty(key, replacement.getClassName()).buildMailer()) {
                assertThat(mailer.getSession()).isSameAs(session);
                assertThat(session.getProvider(protocol)).isSameAs(replacement);
                try (Transport selected = session.getTransport(protocol)) {
                    assertThat(selected).isInstanceOf(AlternateTransport.class);
                }
            }
        }
    }

    private static SimpleJavaMail factory() {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    }

    private static Provider provider(final String protocol, final Class<? extends Transport> type) {
        return new Provider(Provider.Type.TRANSPORT, protocol, type.getName(), "Session integration regression", "1");
    }

    private static Properties eventProperties(final Executor executor, final String scope) {
        final Properties properties = new Properties();
        properties.setProperty("mail.event.scope", scope);
        properties.put("mail.event.executor", executor);
        return properties;
    }

    private static void assertEventDelivery(final Session session, final RecordingExecutor executor) throws Exception {
        final CountDownLatch opened = new CountDownLatch(1);
        final AtomicReference<Thread> deliveryThread = new AtomicReference<>();
        try (EventTransport transport = new EventTransport(session, new URLName("smtp", "localhost", 25, null, null, null))) {
            transport.addConnectionListener(new ConnectionAdapter() {
                @Override public void opened(final ConnectionEvent event) {
                    deliveryThread.set(Thread.currentThread());
                    opened.countDown();
                }
            });
            transport.connect();
            assertThat(opened.await(5, SECONDS)).isTrue();
            assertThat(executor.calls.get()).isEqualTo(1);
            assertThat(deliveryThread.get()).isSameAs(executor.worker);
        }
    }

    private static final class RecordingExecutor implements Executor {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile Thread worker;

        @Override public void execute(final Runnable command) {
            calls.incrementAndGet();
            worker = new Thread(command, "session-integration-events");
            worker.setDaemon(true);
            worker.start();
        }
    }

    public static class EventTransport extends Transport {
        private String username;
        private String password;

        public EventTransport(final Session session, final URLName url) { super(session, url); }

        @Override protected boolean protocolConnect(final String host, final int port, final String user, final String secret) {
            username = user;
            password = secret;
            return true;
        }

        @Override public void sendMessage(final Message message, final Address[] addresses) {
            // No network is needed to exercise native Session and provider construction.
        }
    }

    public static final class AlternateTransport extends EventTransport {
        public AlternateTransport(final Session session, final URLName url) { super(session, url); }
    }

    public static final class NativeSendingTransport extends EventTransport {
        private final AtomicInteger sends;

        public NativeSendingTransport(final Session session, final URLName url) {
            super(session, url);
            sends = (AtomicInteger) session.getProperties().get("test.native.sends");
        }

        @Override public void sendMessage(final Message message, final Address[] addresses) { sends.incrementAndGet(); }
    }
}
