package org.simplejavamail.mailer.internal;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.spi.MailTransportLifecycleAdapter;
import testutil.ConfigLoaderTestHelper;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@Timeout(30)
public class SimpleBatchTransportSetupCleanupTest {
    private static final String STATE = SimpleBatchTransportSetupCleanupTest.class.getName() + ".state";
    private static final String LIFECYCLE_SERVICE = "META-INF/services/" + MailTransportLifecycleAdapter.class.getName();
    private static final RuntimeException CONSTRUCTOR_FAILURE = new IllegalStateException("adapter constructor unavailable");

    @TempDir Path temporary;

    @ParameterizedTest
    @EnumSource(SetupFailure.class)
    void closesAcquiredTransportWhenLifecycleSetupFails(final SetupFailure setup) throws Exception {
        final State state = new State();
        state.setupFailure = setup == SetupFailure.ACTION_ERROR ? new AssertionError("abort setup unavailable")
                : new IllegalStateException("abort setup unavailable");
        final Class<? extends MailTransportLifecycleAdapter> adapter = setup == SetupFailure.CONSTRUCTOR
                ? ConstructorFailureAdapter.class : AbortAdapter.class;

        final Throwable failure;
        try (ServiceScope ignored = new ServiceScope(adapter);
             Mailer mailer = mailer(state)) {
            failure = catchThrowable(() -> mailer.sync().sendMailsInSimpleBatch(List.of(email())));
        }

        if (setup == SetupFailure.CONSTRUCTOR) {
            assertThat(failure).isInstanceOf(ServiceConfigurationError.class).hasCause(CONSTRUCTOR_FAILURE);
        } else {
            assertThat(failure).isSameAs(state.setupFailure);
        }
        assertSetupClosedWithoutSending(state);
    }

    @ParameterizedTest
    @EnumSource(CloseFailure.class)
    void retainsSetupFailureWhenClosingAlsoFails(final CloseFailure cleanup) throws Exception {
        final State state = new State();
        state.setupFailure = new IllegalStateException("abort setup unavailable");
        switch (cleanup) {
            case CHECKED: state.closeFailure = new MessagingException("close unavailable"); break;
            case RUNTIME: state.closeFailure = new IllegalStateException("close unavailable"); break;
            case ERROR: state.closeFailure = new AssertionError("close unavailable"); break;
            case SAME: state.closeFailure = state.setupFailure; break;
            default: throw new AssertionError(cleanup);
        }

        final Throwable failure;
        try (ServiceScope ignored = new ServiceScope(AbortAdapter.class);
             Mailer mailer = mailer(state)) {
            failure = catchThrowable(() -> mailer.sync().sendMailsInSimpleBatch(List.of(email())));
        }

        assertThat(failure).isSameAs(state.setupFailure);
        if (cleanup == CloseFailure.SAME) {
            assertThat(failure.getSuppressed()).isEmpty();
        } else {
            assertThat(failure.getSuppressed()).containsExactly(state.closeFailure);
        }
        assertSetupClosedWithoutSending(state);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulBatchStillClosesItsDedicatedTransport(final boolean async) throws Exception {
        final State state = new State();
        try (ServiceScope ignored = new ServiceScope(AbortAdapter.class);
             Mailer mailer = mailer(state)) {
            if (async) {
                mailer.async().sendMailsInSimpleBatch(List.of(email())).getCompletion().get(10, SECONDS);
            } else {
                mailer.sync().sendMailsInSimpleBatch(List.of(email()));
            }
        }
        assertThat(state.acquired.get()).isEqualTo(1);
        assertThat(state.connected.get()).isEqualTo(1);
        assertThat(state.sent.get()).isEqualTo(1);
        assertThat(state.closed.get()).isEqualTo(1);
        assertThat(state.aborted.get()).isZero();
    }

    @Test
    void keepsAbortRegisteredWhileTransportCloseIsRunning() throws Exception {
        final State state = new State();
        state.blockClose = true;
        try (ServiceScope ignored = new ServiceScope(AbortAdapter.class);
             Mailer mailer = mailer(state)) {
            final MailSend<Void> batch = mailer.async().sendMailsInSimpleBatch(List.of(email()));
            try {
                assertThat(state.closeEntered.await(10, SECONDS)).isTrue();
                batch.requestCancellation();
                assertThat(state.aborted.get()).isEqualTo(1);
                final Throwable completion = batch.getCompletion().handle((value, failure) -> failure).get(10, SECONDS);
                if (completion != null) assertThat(completion).isInstanceOf(MailSendCancelledException.class);
            } finally {
                state.releaseClose.countDown();
            }
        }
        assertThat(state.connected.get()).isEqualTo(1);
        assertThat(state.sent.get()).isEqualTo(1);
        assertThat(state.closed.get()).isEqualTo(1);
    }

    private static void assertSetupClosedWithoutSending(final State state) {
        assertThat(state.acquired.get()).isEqualTo(1);
        assertThat(state.closed.get()).isEqualTo(1);
        assertThat(state.connected.get()).isZero();
        assertThat(state.sent.get()).isZero();
        assertThat(state.aborted.get()).isZero();
    }

    private static Mailer mailer(final State state) throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "cleanup");
        properties.put(STATE, state);
        final Session session = Session.getInstance(properties);
        session.setProvider(new Provider(Provider.Type.TRANSPORT, "cleanup", ProbeTransport.class.getName(), "test", null));
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder(session)
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).build())
                .buildMailer();
    }

    private static Email email() {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("sender@example.org").to("recipient@example.org").withSubject("batch cleanup").withPlainText("body").buildEmail();
    }

    private final class ServiceScope implements AutoCloseable {
        private final ClassLoader previous = Thread.currentThread().getContextClassLoader();
        private final URLClassLoader loader;

        private ServiceScope(final Class<? extends MailTransportLifecycleAdapter> adapter) throws IOException {
            final Path service = temporary.resolve(LIFECYCLE_SERVICE);
            Files.createDirectories(service.getParent());
            Files.writeString(service, adapter.getName());
            final URL serviceUrl = service.toUri().toURL();
            loader = new URLClassLoader(new URL[0], previous) {
                @Override public Enumeration<URL> getResources(final String name) throws IOException {
                    return name.equals(LIFECYCLE_SERVICE) ? Collections.enumeration(List.of(serviceUrl)) : super.getResources(name);
                }
            };
            Thread.currentThread().setContextClassLoader(loader);
        }

        @Override public void close() throws IOException {
            Thread.currentThread().setContextClassLoader(previous);
            loader.close();
        }
    }

    public static class AbortAdapter implements MailTransportLifecycleAdapter {
        @Override public boolean supportsProvider(final Provider provider) { return false; }
        @Override public void configureOwnedSession(final Session session, final String protocol) { throw new AssertionError("supplied Session"); }
        @Override public Optional<Runnable> createAbortAction(final Transport transport) {
            if (!(transport instanceof ProbeTransport)) return Optional.empty();
            final State state = ((ProbeTransport) transport).state;
            rethrow(state.setupFailure);
            return Optional.of(() -> {
                state.aborted.incrementAndGet();
                state.releaseClose.countDown();
            });
        }
    }

    public static final class ConstructorFailureAdapter extends AbortAdapter {
        public ConstructorFailureAdapter() { throw CONSTRUCTOR_FAILURE; }
    }

    public static final class ProbeTransport extends Transport {
        private final State state;
        public ProbeTransport(final Session session, final URLName urlName) {
            super(session, urlName);
            state = (State) session.getProperties().get(STATE);
            state.acquired.incrementAndGet();
        }

        @Override public void connect() {
            state.connected.incrementAndGet();
            setConnected(true);
        }

        @Override public void sendMessage(final Message message, final Address[] addresses) {
            state.sent.incrementAndGet();
        }

        @Override public void close() throws MessagingException {
            state.closed.incrementAndGet();
            state.closeEntered.countDown();
            if (state.blockClose) {
                try {
                    if (!state.releaseClose.await(10, SECONDS)) throw new MessagingException("abort did not release close");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new MessagingException("close interrupted", failure);
                }
            }
            if (state.closeFailure instanceof MessagingException) throw (MessagingException) state.closeFailure;
            rethrow(state.closeFailure);
            setConnected(false);
        }
    }

    private static void rethrow(final Throwable failure) {
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }

    private enum SetupFailure { CONSTRUCTOR, ACTION_RUNTIME, ACTION_ERROR }
    private enum CloseFailure { CHECKED, RUNTIME, ERROR, SAME }

    private static final class State {
        private final AtomicInteger acquired = new AtomicInteger();
        private final AtomicInteger connected = new AtomicInteger();
        private final AtomicInteger sent = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();
        private final AtomicInteger aborted = new AtomicInteger();
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);
        private Throwable setupFailure;
        private Throwable closeFailure;
        private boolean blockClose;
    }
}
