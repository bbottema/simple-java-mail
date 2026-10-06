package org.simplejavamail.mailer.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.eclipse.angus.mail.util.WriteTimeoutSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.SmtpConnectionReport;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;
import testutil.ConfigLoaderTestHelper;
import testutil.smtp.ScriptedSmtpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.acceptMessage;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.finishConnection;

/** Verifies managed sharing and the existing Angus socket/external-executor ownership boundaries; peers are local. */
@Timeout(30)
class SmtpWriteTimeoutOwnershipCharacterizationTest {

    @Test
    void managedConnectionsShareOneSchedulerThreadUntilTheLastPhysicalConnectionCloses() throws Exception {
        final List<ScheduledThreadPoolExecutor> schedulers = new ArrayList<>();
        try (ScriptedSmtpServer server = connectionPeer(4); Mailer mailer = builder(server.port()).buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.writetimeout")).isEqualTo("60000");
            final Object shared = mailer.getSession().getProperties().get("mail.smtp.executor.writetimeout");
            assertThat(shared).isInstanceOf(ScheduledExecutorService.class);
            final List<Transport> connections = new ArrayList<>();
            try {
                for (int index = 0; index < 4; index++) {
                    final Transport connection = connect(mailer, server.port());
                    connections.add(connection);
                    final ScheduledThreadPoolExecutor scheduler = scheduler(socket(connection));
                    assertThat(configuredScheduler(socket(connection))).isSameAs(shared);
                    if (schedulers.isEmpty()) {
                        schedulers.add(scheduler);
                    }
                    assertThat(scheduler).isSameAs(schedulers.get(0));
                    assertThat(scheduler.getPoolSize()).isEqualTo(1);
                    assertThat(scheduler.getRemoveOnCancelPolicy()).isTrue();
                    assertThat(scheduler.getQueue()).isEmpty();
                }
                connections.remove(0).close();
                assertThat(schedulers.get(0).isShutdown()).as("three physical connections still own the generation").isFalse();
            } finally {
                closeConnections(connections);
            }
        }
        for (final ScheduledThreadPoolExecutor scheduler : schedulers) {
            assertStopped(scheduler);
        }
    }

    @Test
    void anObjectValuedExternalSchedulerCanBeSharedAndIsNotClosedByTheMailer() throws Exception {
        final ScheduledThreadPoolExecutor shared = new ScheduledThreadPoolExecutor(1);
        shared.setRemoveOnCancelPolicy(true);
        final Properties properties = new Properties();
        properties.put("mail.smtp.executor.writetimeout", shared);
        try {
            try (ScriptedSmtpServer server = connectionPeer(4);
                 Mailer mailer = builder(server.port()).withProperties(properties).buildMailer()) {
                assertThat(mailer.getSession().getProperties().get("mail.smtp.executor.writetimeout")).isSameAs(shared);
                final List<Transport> connections = new ArrayList<>();
                try {
                    for (int index = 0; index < 4; index++) {
                        final Transport connection = connect(mailer, server.port());
                        connections.add(connection);
                        assertThat(scheduler(socket(connection))).isSameAs(shared);
                    }
                    assertThat(shared.getPoolSize()).isEqualTo(1);
                    assertThat(shared.getQueue()).isEmpty();
                } finally {
                    closeConnections(connections);
                }
            }
            assertThat(shared.isShutdown()).isFalse();
            assertThat(shared.submit(() -> "still owned by the caller").get(5, SECONDS)).isEqualTo("still owned by the caller");
        } finally {
            shared.shutdownNow();
            assertStopped(shared);
        }
    }

    @Test
    void rawSocketAbortLeavesSchedulerCleanupToTransportClose() throws Exception {
        ScheduledThreadPoolExecutor scheduler = null;
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250 localhost");
            peer.expectClosed();
        }); Mailer mailer = builder(server.port()).buildMailer(); Transport connection = connect(mailer, server.port())) {
            scheduler = scheduler(socket(connection));
            MailTransportLifecycleResolver.findAbortAction(connection).orElseThrow(AssertionError::new).run();
            assertThat(scheduler.isShutdown()).as("closing the tracked raw socket is not wrapper cleanup").isFalse();
        }
        assertStopped(scheduler);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void simultaneousPooledSendsShareOneWorkerAndRespectSchedulerOwnership(final boolean supplied) throws Exception {
        final ScheduledThreadPoolExecutor shared = new ScheduledThreadPoolExecutor(1);
        shared.setRemoveOnCancelPolicy(true);
        final Properties properties = new Properties();
        if (supplied) {
            properties.put("mail.smtp.executor.writetimeout", shared);
        }
        ScheduledThreadPoolExecutor owned = null;
        final CountDownLatch connectionsReady = new CountDownLatch(3);
        final Email email = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("sender@example.test").withRecipients(new Recipient(null, "receiver@example.test", TO, null))
                .fixingMessageId("scheduler-characterization@example.test").withPlainText("Synthetic loopback test only.").buildEmail();
        try {
            try (ScriptedSmtpServer server = new ScriptedSmtpServer(3, peer -> {
                peer.greet("250 localhost");
                connectionsReady.countDown();
                assertThat(connectionsReady.await(5, SECONDS)).as("all three physical pool connections were opened").isTrue();
                acceptMessage(peer, "MAIL FROM:<sender@example.test>", "", "receiver@example.test");
                finishConnection(peer);
            }); Mailer mailer = builder(server.port()).withProperties(properties).withThreadPoolSize(3)
                    .withConnectionPoolMaxSize(3).buildMailer()) {
                final List<MailSend<MailSubmissionReceipt>> sends = new ArrayList<>();
                for (int index = 0; index < 3; index++) {
                    sends.add(mailer.async().sendMail(email));
                }
                for (final MailSend<MailSubmissionReceipt> send : sends) {
                    assertThat(send.getCompletion().get(10, SECONDS).getStatus()).isEqualTo(ACCEPTED);
                }
                final Object configured = mailer.getSession().getProperties().get("mail.smtp.executor.writetimeout");
                final ScheduledThreadPoolExecutor generation = schedulerGeneration(configured);
                if (!supplied) {
                    owned = generation;
                }
                assertThat(generation.getPoolSize()).isEqualTo(1);
                assertThat(generation.getQueue()).isEmpty();
            }
            assertThat(shared.isShutdown()).isFalse();
            assertThat(shared.submit(() -> "usable after pooled Mailer close").get(5, SECONDS))
                    .isEqualTo("usable after pooled Mailer close");
        } finally {
            shared.shutdownNow();
            assertStopped(shared);
            if (owned != null) {
                assertStopped(owned);
            }
        }
    }

    @Test
    void failedConnectionSetupDisposesItsGenerationAndCanReconnectOnTheSameTransport() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicReference<Object> controller = new AtomicReference<>();
        final AtomicReference<ScheduledThreadPoolExecutor> rejectedGeneration = new AtomicReference<>();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(2, peer -> {
            if (attempts.incrementAndGet() == 1) {
                rejectedGeneration.set(schedulerGeneration(controller.get()));
                peer.reply("421 setup refused");
                peer.expectClosed();
            } else {
                peer.greet("250 localhost");
                finishConnection(peer);
            }
        }); Mailer mailer = builder(server.port()).buildMailer(); Transport transport = mailer.getSession().getTransport()) {
            controller.set(mailer.getSession().getProperties().get("mail.smtp.executor.writetimeout"));
            assertThatThrownBy(() -> transport.connect("localhost", server.port(), null, null)).isInstanceOf(MessagingException.class);
            assertStopped(rejectedGeneration.get());
            assertThat(schedulerGeneration(controller.get())).isNull();
            transport.connect("localhost", server.port(), null, null);
            assertThat(scheduler(socket(transport))).isNotSameAs(rejectedGeneration.get());
        }
    }

    @Test
    void aPrivateProbeNeverBorrowsOrStopsTheMailersTimeoutGeneration() throws Exception {
        try (ScriptedSmtpServer server = connectionPeer(3); Mailer mailer = builder(server.port()).buildMailer();
             Transport retained = connect(mailer, server.port())) {
            final ScheduledThreadPoolExecutor active = scheduler(socket(retained));
            final Object owner = mailer.getSession().getProperties().get("mail.smtp.executor.writetimeout");
            for (int index = 0; index < 2; index++) {
                final SmtpConnectionReport report = mailer.sync().probeConnection();
                assertThat(report.isSuccessful()).isTrue();
                assertThat(active.isShutdown()).isFalse();
                assertThat(schedulerGeneration(owner)).isSameAs(active);
            }
            assertThat(active.getQueue()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retainedScopesKeepTheGenerationAliveUntilTheirFinalTeardown(final boolean simpleBatch) throws Exception {
        final AtomicReference<Object> controller = new AtomicReference<>();
        final AtomicReference<ScheduledThreadPoolExecutor> observed = new AtomicReference<>();
        final AtomicInteger callbacks = new AtomicInteger();
        final Email email = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("sender@example.test").withRecipients(new Recipient(null, "receiver@example.test", TO, null))
                .fixingMessageId("retained-scheduler@example.test").withPlainText("Synthetic retained-connection test.").buildEmail();
        try (ScriptedSmtpServer server = sendingPeer(2); Mailer mailer = builder(server.port()).withMailSendObserver(outcome -> {
            try {
                final ScheduledThreadPoolExecutor current = schedulerGeneration(controller.get());
                assertThat(current).isNotNull();
                observed.compareAndSet(null, current);
                assertThat(current).isSameAs(observed.get());
                assertThat(current.isShutdown()).as("per-email completion is not shared-connection disposal").isFalse();
                assertThat(outcome.isSuccessful()).isTrue();
                callbacks.incrementAndGet();
            } catch (Exception failure) {
                throw new AssertionError("Couldn't inspect the retained connection's timeout controller", failure);
            }
        }).buildMailer()) {
            controller.set(mailer.getSession().getProperties().get("mail.smtp.executor.writetimeout"));
            if (simpleBatch) {
                mailer.sync().sendMailsInSimpleBatch(List.of(email, email));
            } else {
                mailer.withOpenConnection(sender -> {
                    sender.sendMail(email);
                    sender.sendMail(email);
                });
            }
            assertThat(callbacks.get()).isEqualTo(2);
            assertStopped(observed.get());
            assertThat(schedulerGeneration(controller.get())).isNull();
        }
    }

    @Test
    void clusteredConnectionsUseTheirSelectedSessionsControllerAndCloseIndependently() throws Exception {
        final UUID cluster = UUID.randomUUID();
        final Email email = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("sender@example.test").withRecipients(new Recipient(null, "receiver@example.test", TO, null))
                .fixingMessageId("cluster-ownership@example.test").withPlainText("Synthetic cluster ownership test.").buildEmail();
        ScheduledThreadPoolExecutor secondGeneration = null;
        try (ScriptedSmtpServer firstPeer = sendingPeer(1); ScriptedSmtpServer secondPeer = sendingPeer(2);
             Mailer first = builder(firstPeer.port()).withClusterKey(cluster)
                     .withConnectionPoolExpireAfterMillis(60000).withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy.ROUND_ROBIN).buildMailer();
             Mailer second = builder(secondPeer.port()).withClusterKey(cluster).withConnectionPoolExpireAfterMillis(60000).buildMailer()) {
            assertThat(second.sync().sendMail(email).getStatus()).isEqualTo(ACCEPTED);
            assertThat(second.sync().sendMail(email).getStatus()).isEqualTo(ACCEPTED);
            final Object firstController = first.getSession().getProperties().get("mail.smtp.executor.writetimeout");
            final Object secondController = second.getSession().getProperties().get("mail.smtp.executor.writetimeout");
            assertThat(firstController).isNotSameAs(secondController);
            final ScheduledThreadPoolExecutor firstGeneration = schedulerGeneration(firstController);
            secondGeneration = schedulerGeneration(secondController);
            assertThat(firstGeneration).as("the fixture retains both physical connections instead of testing idle expiry").isNotNull();
            assertThat(secondGeneration).isNotNull();
            assertThat(firstGeneration).isNotSameAs(secondGeneration);
            first.close();
            assertStopped(firstGeneration);
            assertThat(secondGeneration.isShutdown()).isFalse();
            assertThat(second.sync().sendMail(email).getStatus()).isEqualTo(ACCEPTED);
            assertThat(schedulerGeneration(secondController)).isSameAs(secondGeneration);
        }
        assertStopped(secondGeneration);
    }

    private static ScriptedSmtpServer sendingPeer(final int messages) throws Exception {
        return new ScriptedSmtpServer(1, peer -> {
            peer.greet("250 localhost");
            for (int index = 0; index < messages; index++) {
                acceptMessage(peer, "MAIL FROM:<sender@example.test>", "", "receiver@example.test");
            }
            finishConnection(peer);
        });
    }

    @Test
    void aFailedUnderlyingCloseStillShutsDownTheSocketOwnedScheduler() throws Exception {
        final Socket delegate = mock(Socket.class);
        final IOException failure = new IOException("synthetic close failure");
        doThrow(failure).when(delegate).close();
        final WriteTimeoutSocket socket = new WriteTimeoutSocket(delegate, 60000);
        final ScheduledThreadPoolExecutor scheduler = scheduler(socket);

        assertThatThrownBy(socket::close).isSameAs(failure);
        assertStopped(scheduler);
    }

    @Test
    void successfulWritesRemoveTheirCancelledTimeoutTasks() throws Exception {
        final ScheduledThreadPoolExecutor shared = new ScheduledThreadPoolExecutor(1);
        shared.setRemoveOnCancelPolicy(true);
        final ByteArrayOutputStream content = new ByteArrayOutputStream();
        try (WriteTimeoutSocket socket = new WriteTimeoutSocket(socketWritingTo(content), 60000, shared)) {
            final OutputStream output = socket.getOutputStream();
            for (int index = 0; index < 1000; index++) {
                output.write(42);
                assertThat(shared.getQueue()).isEmpty();
            }
            assertThat(content.size()).isEqualTo(1000);
            assertThat(shared.getPoolSize()).isEqualTo(1);
        } finally {
            shared.shutdownNow();
            assertStopped(shared);
        }
    }

    @Test
    void aBlockedWriteTimesOutWithoutClosingAnotherSocketUsingTheSharedScheduler() throws Exception {
        final ScheduledThreadPoolExecutor shared = new ScheduledThreadPoolExecutor(1);
        shared.setRemoveOnCancelPolicy(true);
        final ExecutorService writer = Executors.newSingleThreadExecutor();
        final BlockingOutputStream blocked = new BlockingOutputStream();
        final ByteArrayOutputStream healthy = new ByteArrayOutputStream();
        try (WriteTimeoutSocket blockedSocket = new WriteTimeoutSocket(socketWritingTo(blocked), 100, shared);
             WriteTimeoutSocket healthySocket = new WriteTimeoutSocket(socketWritingTo(healthy), 60000, shared)) {
            final OutputStream output = blockedSocket.getOutputStream();
            final CompletableFuture<IOException> attempt = CompletableFuture.supplyAsync(() -> {
                try {
                    output.write(42);
                    throw new AssertionError("The blocked write should not succeed");
                } catch (IOException failure) {
                    return failure;
                }
            }, writer);
            assertThat(blocked.started.await(5, SECONDS)).isTrue();
            assertThat(attempt.get(5, SECONDS)).isInstanceOf(IOException.class);
            assertThat(blocked.closed.get()).isTrue();
            healthySocket.getOutputStream().write(43);
            assertThat(healthy.toByteArray()).containsExactly((byte) 43);
            assertThat(shared.isShutdown()).isFalse();
        } finally {
            blocked.close();
            writer.shutdownNow();
            assertThat(writer.awaitTermination(5, SECONDS)).isTrue();
            shared.shutdownNow();
            assertStopped(shared);
        }
    }

    @Test
    void aStoppedExternalSchedulerRejectsWritesBeforeAnyPayloadIsWritten() throws Exception {
        final ScheduledThreadPoolExecutor shared = new ScheduledThreadPoolExecutor(1);
        shared.shutdownNow();
        final ByteArrayOutputStream content = new ByteArrayOutputStream();
        try (WriteTimeoutSocket socket = new WriteTimeoutSocket(socketWritingTo(content), 60000, shared)) {
            assertThatThrownBy(() -> socket.getOutputStream().write(42)).isInstanceOf(IOException.class)
                    .hasMessage("Write aborted due to timeout not enforced");
            assertThat(content.size()).isZero();
        }
        assertStopped(shared);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void aNonPositiveWriteTimeoutDoesNotStartSchedulerThreads(final int timeout) throws Exception {
        final ByteArrayOutputStream content = new ByteArrayOutputStream();
        try (WriteTimeoutSocket socket = new WriteTimeoutSocket(socketWritingTo(content), timeout)) {
            socket.getOutputStream().write(42);
            assertThat(scheduler(socket).getPoolSize()).isZero();
            assertThat(scheduler(socket).getQueue()).isEmpty();
            assertThat(content.toByteArray()).containsExactly((byte) 42);
        }
    }

    private static MailerRegularBuilder<?> builder(final int port) {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
                .withSMTPServer("localhost", port).withTransportStrategy(SMTP).withOpportunisticTLS(false)
                .withSmtpClientHostname("probe.example.test").withConnectionPoolCoreSize(0);
    }

    private static ScriptedSmtpServer connectionPeer(final int connections) throws Exception {
        return new ScriptedSmtpServer(connections, peer -> {
            peer.greet("250 localhost");
            finishConnection(peer);
        });
    }

    private static Transport connect(final Mailer mailer, final int port) throws Exception {
        final Transport transport = mailer.getSession().getTransport("smtp");
        try {
            transport.connect("localhost", port, null, null);
            return transport;
        } catch (Exception failure) {
            transport.close();
            throw failure;
        }
    }

    private static void closeConnections(final List<Transport> connections) throws Exception {
        Exception failure = null;
        for (final Transport connection : connections) {
            try {
                connection.close();
            } catch (Exception closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    // These private Angus fields are inspected only to characterize resources; production never relies on reflective access.
    private static Socket socket(final Transport transport) throws Exception {
        final Field socket = SMTPTransport.class.getDeclaredField("serverSocket");
        socket.setAccessible(true);
        return (Socket) socket.get(transport);
    }

    private static ScheduledThreadPoolExecutor scheduler(final Socket socket) throws Exception {
        return schedulerGeneration(configuredScheduler(socket));
    }

    private static ScheduledThreadPoolExecutor schedulerGeneration(final Object configured) throws Exception {
        if (configured instanceof ScheduledThreadPoolExecutor) {
            return (ScheduledThreadPoolExecutor) configured;
        }
        synchronized (configured) {
            final Field current = configured.getClass().getDeclaredField("current");
            current.setAccessible(true);
            return (ScheduledThreadPoolExecutor) current.get(configured);
        }
    }

    private static ScheduledExecutorService configuredScheduler(final Socket socket) throws Exception {
        assertThat(socket).isInstanceOf(WriteTimeoutSocket.class);
        final Field executor = WriteTimeoutSocket.class.getDeclaredField("ses");
        executor.setAccessible(true);
        return (ScheduledExecutorService) executor.get(socket);
    }

    private static Socket socketWritingTo(final OutputStream output) throws Exception {
        final Socket socket = mock(Socket.class);
        when(socket.getOutputStream()).thenReturn(output);
        return socket;
    }

    private static void assertStopped(final ScheduledThreadPoolExecutor scheduler) throws InterruptedException {
        assertThat(scheduler.isShutdown()).isTrue();
        assertThat(scheduler.awaitTermination(5, SECONDS)).isTrue();
    }

    private static final class BlockingOutputStream extends OutputStream {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void write(final int value) throws IOException {
            started.countDown();
            try {
                if (!released.await(5, SECONDS)) {
                    throw new AssertionError("The timeout task did not close the blocked stream");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException(failure);
            }
            throw new SocketException("Synthetic blocked socket was closed");
        }

        @Override
        public void close() {
            closed.set(true);
            released.countDown();
        }
    }
}
