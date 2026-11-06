package org.simplejavamail.mailer.internal;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import jakarta.mail.event.ConnectionAdapter;
import jakarta.mail.event.ConnectionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import testutil.ConfigLoaderTestHelper;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static testutil.EmailHelper.createDummyOperationalConfig;

@Timeout(30)
class SessionConstructionContractTest {
    @Test
    void effectiveScopeDeterminesWhetherARequestedExecutorCanStillTakeEffect() throws Exception {
        final RecordingExecutor original = new RecordingExecutor();
        final RecordingExecutor replacement = new RecordingExecutor();
        final Session supplied = Session.getInstance(properties(original, "session"));
        for (ConnectionConflictPolicy policy : ConnectionConflictPolicy.values()) {
            assertThatThrownBy(() -> {
                try (Mailer ignored = factory().mailerBuilder(supplied).withConnectionConflictPolicy(policy)
                        .withProperties(properties(replacement, "SESSION")).buildMailer()) { }
            }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mail.event.executor");
            assertThat(supplied.getProperties().get("mail.event.executor")).isSameAs(original);
        }
        try (Mailer mailer = factory().mailerBuilder(supplied).withProperties(properties(replacement, "folder")).buildMailer()) {
            assertDelivery(mailer.getSession(), replacement);
        }
        final RecordingExecutor initiallyUnused = new RecordingExecutor();
        final Session folder = Session.getInstance(properties(initiallyUnused, "folder"));
        try (Mailer mailer = factory().mailerBuilder(folder).withProperty("mail.event.scope", "session").buildMailer()) {
            assertDelivery(mailer.getSession(), initiallyUnused);
        }
    }

    @Test
    void inheritedExecutorObjectsStayUnusedInsteadOfBeingFlattenedIntoADirectEntry() throws Exception {
        final RecordingExecutor inherited = new RecordingExecutor();
        final Properties defaults = properties(inherited, "session");
        final Session supplied = Session.getInstance(new Properties(defaults));
        try (Mailer mailer = factory().mailerBuilder(supplied).buildMailer()) {
            assertThat(mailer.getSession().getProperties().get("mail.event.executor")).isNull();
            assertThat(mailer.getSession().getProperty("mail.event.scope")).isEqualTo("session");
        }
        assertThat(inherited.calls).hasValue(0);
    }

    @Test
    void configuredAndLockedScopeAreCompletedBeforeTheEventExecutorIsAppliedOrGuarded() throws Exception {
        final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(
                Map.of("simplejavamail.extraproperties.mail.event.scope", "session")).load());
        final RecordingExecutor ownedEvents = new RecordingExecutor();
        final Properties ownedOverlay = new Properties();
        ownedOverlay.put("mail.event.executor", ownedEvents);
        try (Mailer mailer = configured.mailerBuilder().withSMTPServer("localhost", 25).withProperties(ownedOverlay).buildMailer()) {
            assertDelivery(mailer.getSession(), ownedEvents);
        }

        final RecordingExecutor preconfiguredEvents = new RecordingExecutor();
        final Properties baseline = new Properties();
        baseline.put("mail.event.executor", preconfiguredEvents);
        final Session preconfigured = Session.getInstance(baseline);
        try (Mailer mailer = configured.mailerBuilder(preconfigured).buildMailer()) {
            assertDelivery(mailer.getSession(), preconfiguredEvents);
        }

        final SimpleJavaMail locked = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(
                Map.of("simplejavamail.locked.extraproperties.mail.event.scope", "session")).load());
        final Session unconfigured = Session.getInstance(new Properties());
        assertThatThrownBy(() -> {
            try (Mailer ignored = locked.mailerBuilder(unconfigured)
                    .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                    .withProperties(ownedOverlay).buildMailer()) { }
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mail.event.executor");
        assertThat(unconfigured.getProperties()).isEmpty();
    }

    @Test
    void internalCompletedFactsConstructorAlsoAppliesPropertiesBeforeNativeSessionCreation() throws Exception {
        final RecordingExecutor executor = new RecordingExecutor();
        final OperationalConfig operational = operational(properties(executor, "session"), false);
        try (Mailer mailer = internal(operational)) {
            assertDelivery(mailer.getSession(), executor);
        }
        assertThat(operational.getExecutorService().isShutdown()).isTrue();
    }

    @Test
    void invalidConstructorConsumedValuesFailBeforeActivationAndPreserveBorrowedResources() {
        final Properties invalid = properties("not-an-executor", "session");
        assertThatThrownBy(() -> {
            try (Mailer ignored = factory().mailerBuilder().withSMTPServer("localhost", 25).withProperties(invalid).buildMailer()) { }
        }).isInstanceOf(ClassCastException.class);
        for (boolean borrowed : new boolean[] {false, true}) {
            final OperationalConfig operational = operational(invalid, borrowed);
            final ExecutorService executor = operational.getExecutorService();
            try {
                assertThatThrownBy(() -> internal(operational)).isInstanceOf(ClassCastException.class);
                assertThat(executor.isShutdown()).isEqualTo(!borrowed);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static OperationalConfig operational(final Properties properties, final boolean borrowed) {
        final OperationalConfig operational = spy(createDummyOperationalConfig(List.of(), false, true));
        doReturn(properties).when(operational).getProperties();
        doReturn(borrowed).when(operational).isExecutorServiceIsUserProvided();
        return operational;
    }

    private static Mailer internal(final OperationalConfig operational) {
        return new MailerImpl(new ServerConfigImpl("localhost", 25, null, null, null, null), TransportStrategy.SMTP,
                EmailGovernanceImpl.NO_GOVERNANCE(), new ProxyConfigImpl(null, null, null, null, null), null, operational);
    }

    private static Properties properties(final Object executor, final String scope) {
        final Properties properties = new Properties();
        properties.put("mail.event.executor", executor);
        properties.setProperty("mail.event.scope", scope);
        return properties;
    }

    private static SimpleJavaMail factory() { return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()); }

    private static void assertDelivery(final Session session, final RecordingExecutor executor) throws Exception {
        final CountDownLatch opened = new CountDownLatch(1);
        try (EventTransport transport = new EventTransport(session)) {
            transport.addConnectionListener(new ConnectionAdapter() {
                @Override public void opened(final ConnectionEvent event) { opened.countDown(); }
            });
            transport.connect();
            assertThat(opened.await(5, SECONDS)).isTrue();
            assertThat(executor.calls).hasValue(1);
        }
    }

    private static final class RecordingExecutor implements Executor {
        private final AtomicInteger calls = new AtomicInteger();
        @Override public void execute(final Runnable command) {
            calls.incrementAndGet();
            final Thread thread = new Thread(command, "session-construction-events");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static final class EventTransport extends Transport {
        private EventTransport(final Session session) { super(session, new URLName("smtp", "localhost", 25, null, null, null)); }
        @Override protected boolean protocolConnect(final String host, final int port, final String user, final String password) { return true; }
        @Override public void sendMessage(final Message message, final Address[] addresses) { }
    }
}
