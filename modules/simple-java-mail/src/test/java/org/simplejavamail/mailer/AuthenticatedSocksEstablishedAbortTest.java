package org.simplejavamail.mailer;

import jakarta.activation.DataSource;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.authenticatedsockssupport.common.AuthenticatedSocksSocketProvider;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendTimeoutException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.internal.modules.AuthenticatedSocksModule;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.ConfigLoaderTestHelper;
import testutil.socks.AuthenticatedSocksTestPeer.Protocol;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.ContentTransferEncoding.BASE_64;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.ArgumentMatchers.any;
import static testutil.socks.AuthenticatedSocksRouteTestSupport.requireDirectRoute;
import static testutil.smtp.SmtpTestTls.testKeyStore;
import static testutil.smtp.SmtpTestTls.tlsContext;

/** Uses actual TCP backpressure without confusing detached future cancellation with physical connection disposal. */
@Timeout(45)
class AuthenticatedSocksEstablishedAbortTest {
    private enum Stop { CANCELLATION, DEADLINE, WRITE_TIMEOUT }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("stops")
    void stopClosesTheExactBlockedUpstreamBeforeTheFixtureDrains(final Protocol protocol, final Stop stop) throws Exception {
        assertWithNormalLogging(protocol, stop);
    }

    @ParameterizedTest(name = "native write timeout {0}")
    @EnumSource(Protocol.class)
    void nativeWriteTimeoutClosesTheExactBlockedUpstreamBeforeTheFixtureDrains(final Protocol protocol) throws Exception {
        assertWithNormalLogging(protocol, Stop.WRITE_TIMEOUT);
    }

    private void assertWithNormalLogging(final Protocol protocol, final Stop stop) throws Exception {
        requireDirectRoute();
        final org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger)
                LogManager.getLogger("org.simplejavamail.mailer.internal.MailerImpl");
        final Level previous = logger.getLevel();
        // TRACE would serialize the complete streaming fixture for diagnostics before SMTP transmission.
        logger.setLevel(Level.INFO);
        try {
            assertStopClosesTheBlockedUpstream(protocol, stop);
        } finally {
            logger.setLevel(previous);
        }
    }

    private void assertStopClosesTheBlockedUpstream(final Protocol protocol, final Stop stop) throws Exception {
        final ExecutorService application = Executors.newFixedThreadPool(2);
        final CountDownLatch reuseHealthy = new CountDownLatch(1);
        final CountDownLatch releaseHealthy = new CountDownLatch(1);
        final CompletableFuture<Void> healthyReady = new CompletableFuture<>();
        final CompletableFuture<Void> healthyReused = new CompletableFuture<>();
        final StreamingPayload payload = new StreamingPayload();
        final List<UpstreamOwner> captures = new CopyOnWriteArrayList<>();
        final AuthenticatedSocksModule actualModule = ModuleLoader.loadAuthenticatedSocksModule();
        final NativeWriteTimeoutExecutor nativeTimeouts = stop == Stop.WRITE_TIMEOUT ? new NativeWriteTimeoutExecutor() : null;
        MailSend<MailSubmissionReceipt> send = null;
        UpstreamOwner owner = null;
        CompletableFuture<Void> healthyScope = null;
        try (NativeWriteTimeoutExecutor timerOwnership = nativeTimeouts;
             MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS);
             ProxySmtpFixture fixture = new ProxySmtpFixture(protocol)) {
            final AuthenticatedSocksModule observedModule = spy(actualModule);
            doAnswer(invocation -> {
                final AuthenticatedSocksSocketProvider provider = (AuthenticatedSocksSocketProvider) invocation.callRealMethod();
                return (AuthenticatedSocksSocketProvider) raw -> {
                    assertThat(raw.isConnected()).as("the raw socket is captured before setup").isFalse();
                    final Socket direct = provider.wrap(raw);
                    assertThat(raw.isConnected()).as("wrapping is offline").isFalse();
                    captures.add(new UpstreamOwner(raw, direct));
                    return direct;
                };
            }).when(observedModule).createSocketProvider(any());
            modules.when(ModuleLoader::loadAuthenticatedSocksModule).thenReturn(observedModule);
            final Mailer mailer = mailer(fixture, stop, nativeTimeouts);
            try {
                if (nativeTimeouts != null) {
                    final String prefix = protocol == Protocol.SMTPS ? "mail.smtps" : "mail.smtp";
                    assertThat(mailer.getSession().getProperties().get(prefix + ".executor.writetimeout")).isSameAs(nativeTimeouts);
                    assertThat(mailer.getOperationalConfig().getMailSendTimeout()).as("native expiry is the only timed stop in this row").isNull();
                    assertThat(mailer.getOperationalConfig().getReadTimeout()).isEqualTo(Duration.ZERO);
                }
                mailer.rehearse(email("warm-up"));
                send = mailer.async().sendMail(largeEmail(payload));
                assertThat(fixture.beforeBody.await(5, SECONDS)).as("the remote SMTP peer reached DATA").isTrue();
                assertThat(captures).as("only the stalled physical connection exists before adding the healthy peer").hasSize(1);
                owner = captures.get(0);
                assertThat(owner.direct.isConnected()).isTrue();
                owner.raw.setSendBufferSize(1024);
                assertThat(owner.raw.isClosed()).isFalse();
                if (nativeTimeouts != null) { nativeTimeouts.observe(owner, fixture, send.getCompletion()); }
                fixture.allowBody.countDown();
                final String blockedStack = owner.awaitNativeWrite(payload);
                assertThat(fixture.payloadBytesRead).as("the fixture has not drained SMTP payload").hasValue(0);
                assertThat(payload.latestStreamRead.get().get()).as("the active payload stream still has unread content")
                        .isBetween(1L, 128L * 1024 * 1024 - 1);

                healthyScope = CompletableFuture.runAsync(() -> {
                    try {
                        mailer.withOpenConnection(sender -> {
                            assertThat(sender.sendMail(email("healthy-before-stop")).getStatus()).isEqualTo(ACCEPTED);
                            healthyReady.complete(null);
                            assertThat(reuseHealthy.await(20, SECONDS)).isTrue();
                            assertThat(sender.sendMail(email("healthy-after-stop")).getStatus()).isEqualTo(ACCEPTED);
                            healthyReused.complete(null);
                            assertThat(releaseHealthy.await(15, SECONDS)).isTrue();
                        });
                    } catch (Exception | Error failure) {
                        healthyReady.completeExceptionally(failure);
                        healthyReused.completeExceptionally(failure);
                        throw new CompletionException(failure);
                    }
                }, application);
                healthyReady.get(5, SECONDS);
                assertThat(send.getCompletion().isDone()).as("the healthy peer is established before the stop").isFalse();
                System.out.println("SOCKS_DIRECT_ESTABLISHED blocked protocol=" + protocol + ", mode=" + stop + ", generated=" + payload.generated.get()
                        + ", activeStreamRead=" + payload.latestStreamRead.get().get()
                        + ", sendBuffer=" + owner.raw.getSendBufferSize() + ", receiveBuffer=" + fixture.receiveBuffer
                        + ", fixturePayloadRead=" + fixture.payloadBytesRead.get() + ", stack=" + blockedStack);

                if (stop == Stop.CANCELLATION) {
                    final MailSend<MailSubmissionReceipt> active = send;
                    CompletableFuture.runAsync(active::requestCancellation, application).get(3, SECONDS);
                }
                final Throwable failure = send.getCompletion().handle((receipt, problem) -> problem).get(12, SECONDS);
                final boolean rawClosed = await(owner.raw::isClosed, Duration.ofSeconds(2));
                final UpstreamOwner observed = owner;
                final boolean writerUnblocked = await(() -> !observed.inNativeSmtpWrite(), Duration.ofSeconds(2));
                reuseHealthy.countDown();
                healthyReused.get(5, SECONDS);
                assertThat(healthyScope.isDone()).as("the independent healthy scope remains established").isFalse();
                assertThat(fixture.healthyMessages).hasValue(2);
                assertThat(fixture.payloadBytesRead).as("physical disposal is observed before fixture payload reads resume").hasValue(0);
                System.out.println("SOCKS_DIRECT_ESTABLISHED stopped protocol=" + protocol + ", mode=" + stop + ", rawClosedBeforeDrain=" + rawClosed
                        + ", writerUnblockedBeforeDrain=" + writerUnblocked + ", fixturePayloadRead=" + fixture.payloadBytesRead.get()
                        + ", healthyMessages=" + fixture.healthyMessages.get());
                assertThat(rawClosed).as("the exact raw upstream socket closes before the fixture drains").isTrue();
                assertThat(writerUnblocked).as("the actual sender leaves native SMTP write before the fixture drains").isTrue();

                if (nativeTimeouts != null) {
                    nativeTimeouts.assertCausalExpiry(owner, protocol);
                    // Native cancel(true) can win while its already-running timeout closes the stream. In that race Angus retains
                    // SocketException instead of adding "Write timed out"; the real expiry/closure witness owns this assertion.
                    assertThat(failure).isInstanceOf(MailException.class).hasRootCauseInstanceOf(IOException.class);
                    assertThat(failure).isNotInstanceOf(MailSendCancelledException.class).isNotInstanceOf(MailSendTimeoutException.class);
                    boolean nativeTimeoutLabel = false;
                    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                        nativeTimeoutLabel |= cause instanceof IOException && "Write timed out".equals(cause.getMessage());
                    }
                    System.out.println("SOCKS_NATIVE_TIMEOUT_FAILURE protocol=" + protocol + ", nativeTimeoutLabel=" + nativeTimeoutLabel
                            + ", causalNativeExpiryVerified=true");
                } else {
                    assertThat(failure).isInstanceOf(stop == Stop.CANCELLATION ? MailSendCancelledException.class : MailSendTimeoutException.class);
                }
                ((MailException) failure).getSubmissionReceipt().ifPresent(receipt -> assertThat(receipt.getStatus()).isNotEqualTo(ACCEPTED));

                // TCP EOF can follow queued payload. Observe it separately, after physical local closure was established.
                fixture.resumeDraining.countDown();
                fixture.peerEof.get(5, SECONDS);
                System.out.println("SOCKS_DIRECT_ESTABLISHED peer-eof protocol=" + protocol + ", mode=" + stop + ", drained=" + fixture.payloadBytesRead.get());
            } finally {
                // Failure cleanup reaches the exact captured handle only after the pre-drain assertions.
                if (owner != null) { owner.raw.close(); }
                if (send != null && stop != Stop.WRITE_TIMEOUT) { send.requestCancellation(); }
                fixture.allowBody.countDown();
                fixture.resumeDraining.countDown();
                reuseHealthy.countDown();
                releaseHealthy.countDown();
                try {
                    if (healthyScope != null) { healthyScope.get(10, SECONDS); }
                } finally {
                    mailer.closeAsync().get(10, SECONDS);
                }
            }
        } finally {
            application.shutdownNow();
            assertThat(application.awaitTermination(5, SECONDS)).as("test application workers stop").isTrue();
        }
    }

    private static Mailer mailer(final ProxySmtpFixture fixture, final Stop stop, final NativeWriteTimeoutExecutor nativeTimeouts) {
        final MailerRegularBuilder<?> builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
                .withSMTPServer("localhost", 2525)
                .withTransportStrategy(fixture.protocol == Protocol.SMTPS ? TransportStrategy.SMTPS
                        : fixture.protocol == Protocol.STARTTLS ? TransportStrategy.SMTP_TLS : TransportStrategy.SMTP)
                .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("blocked-write.example.test")
                        .withConnectTimeout(Duration.ofSeconds(5)).withReadTimeout(Duration.ZERO)
                        .withWriteTimeout(stop == Stop.WRITE_TIMEOUT ? Duration.ofSeconds(6) : Duration.ZERO).build())
                .withTlsConfig(TlsConfig.builder().trustingSSLHosts("localhost").verifyingServerIdentity(true).build())
                .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withThreadPoolSize(2).build())
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(2)
                        .withExpireAfter(Duration.ofMinutes(1)).build())
                .withProxyConfig(SocksProxyConfig.builder().withHost("127.0.0.1").withPort(fixture.listener.getLocalPort())
                        .withUsername("proxy-user").withPassword("proxy-secret").build());
        if (stop == Stop.DEADLINE) { builder.withMailSendTimeout(Duration.ofSeconds(8)); }
        if (nativeTimeouts != null) {
            final Properties properties = new Properties();
            properties.put((fixture.protocol == Protocol.SMTPS ? "mail.smtps" : "mail.smtp") + ".executor.writetimeout", nativeTimeouts);
            builder.withProperties(properties);
        }
        return builder.buildMailer();
    }

    private static Stream<Arguments> stops() {
        return Stream.of(Protocol.values()).flatMap(protocol -> Stream.of(Stop.CANCELLATION, Stop.DEADLINE).map(stop -> Arguments.of(protocol, stop)));
    }

    private static Email email(final String subject) {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("sender@example.org").to("recipient@example.org").withSubject(subject).withPlainText("Healthy SMTP peer").buildEmail();
    }

    private static Email largeEmail(final StreamingPayload payload) {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().copying(email("blocked-stream"))
                .withAttachment(AttachmentResource.builder().withName("stream.bin").withDataSource(payload)
                        .withContentTransferEncoding(BASE_64).build()).buildEmail();
    }

    private static boolean await(final BooleanSupplier condition, final Duration budget) throws InterruptedException {
        final long deadline = System.nanoTime() + budget.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { Thread.sleep(10); }
        return condition.getAsBoolean();
    }

    /** A caller-owned scheduler observes the actual native callable and returns its result/future without changing cancellation semantics. */
    private static final class NativeWriteTimeoutExecutor extends ScheduledThreadPoolExecutor implements AutoCloseable {
        private final AtomicInteger scheduled = new AtomicInteger();
        private final AtomicInteger fired = new AtomicInteger();
        private final AtomicInteger finished = new AtomicInteger();
        private final AtomicLong completionAt = new AtomicLong();
        private final AtomicBoolean rawClosedAtCompletion = new AtomicBoolean();
        private final CompletableFuture<Void> completionObserved = new CompletableFuture<>();
        private final CompletableFuture<NativeExpiry> expiryFinished = new CompletableFuture<>();
        private volatile UpstreamOwner blocked;
        private volatile ProxySmtpFixture fixture;
        private volatile CompletableFuture<?> completion;

        private NativeWriteTimeoutExecutor() {
            super(1, task -> {
                final Thread worker = new Thread(task, "authenticated SOCKS native timeout witness");
                worker.setDaemon(true);
                return worker;
            });
            setRemoveOnCancelPolicy(true);
        }

        private void observe(final UpstreamOwner owner, final ProxySmtpFixture peer, final CompletableFuture<?> sendCompletion) {
            blocked = owner;
            fixture = peer;
            completion = sendCompletion;
            sendCompletion.whenComplete((receipt, failure) -> {
                completionAt.set(System.nanoTime());
                rawClosedAtCompletion.set(owner.raw.isClosed());
                completionObserved.complete(null);
            });
        }

        @Override
        public <V> ScheduledFuture<V> schedule(final Callable<V> nativeTask, final long delay, final TimeUnit unit) {
            scheduled.incrementAndGet();
            final Thread writer = Thread.currentThread();
            final StackTraceElement[] schedulingStack = writer.getStackTrace();
            final long scheduledAt = System.nanoTime();
            return super.schedule(() -> {
                final UpstreamOwner owner = blocked;
                final ProxySmtpFixture peer = fixture;
                final NativeExpiry expiry = new NativeExpiry();
                expiry.startedAt = System.nanoTime();
                expiry.delayNanos = unit.toNanos(delay);
                expiry.scheduledAt = scheduledAt;
                expiry.nativeTask = nativeTask.getClass().getName();
                expiry.schedulingStack = Arrays.toString(schedulingStack);
                expiry.scheduledByNativeWrite = Arrays.stream(schedulingStack).anyMatch(frame ->
                        frame.getClassName().equals("org.eclipse.angus.mail.util.TimeoutOutputStream") && frame.getMethodName().equals("write"));
                expiry.scheduledByBlockedWriter = owner != null && writer == owner.senderThread;
                expiry.writerBlockedAtFire = owner != null && owner.inNativeSmtpWrite();
                expiry.rawClosedBefore = owner == null || owner.raw.isClosed();
                expiry.completionDoneBefore = completion == null || completion.isDone();
                expiry.payloadReadBefore = peer == null ? -1 : peer.payloadBytesRead.get();
                fired.incrementAndGet();
                try {
                    final V result = nativeTask.call();
                    expiry.nativeResult = String.valueOf(result);
                    return result;
                } catch (Exception | Error failure) {
                    expiry.nativeFailure = failure;
                    throw failure;
                } finally {
                    expiry.rawClosedAfter = owner != null && owner.raw.isClosed();
                    expiry.payloadReadAfter = peer == null ? -1 : peer.payloadBytesRead.get();
                    expiry.finishedAt = System.nanoTime();
                    finished.incrementAndGet();
                    expiryFinished.complete(expiry);
                }
            }, delay, unit);
        }

        private void assertCausalExpiry(final UpstreamOwner owner, final Protocol protocol) throws Exception {
            final NativeExpiry expiry = expiryFinished.get(3, SECONDS);
            completionObserved.get(3, SECONDS);
            assertThat(scheduled.get()).as("actual native writes scheduled expiry work").isPositive();
            assertThat(fired).as("only the stalled native write expired; healthy writes cancelled their timers").hasValue(1);
            assertThat(finished).as("the real native timeout callable finished").hasValue(1);
            assertThat(expiry.nativeTask).startsWith("org.eclipse.angus.mail.util.TimeoutOutputStream");
            assertThat(expiry.scheduledByNativeWrite).as(expiry.schedulingStack).isTrue();
            assertThat(expiry.scheduledByBlockedWriter).isTrue();
            assertThat(expiry.writerBlockedAtFire).as("expiry found the same writer still blocked in native SMTP output").isTrue();
            assertThat(expiry.delayNanos).isEqualTo(SECONDS.toNanos(6));
            assertThat(expiry.startedAt - expiry.scheduledAt).isGreaterThanOrEqualTo(expiry.delayNanos);
            assertThat(expiry.rawClosedBefore).as("the exact upstream was open immediately before the native expiry call").isFalse();
            assertThat(expiry.rawClosedAfter).as("running the native callable closed that exact upstream").isTrue();
            assertThat(expiry.nativeFailure).isNull();
            assertThat(expiry.completionDoneBefore).as("the native expiry ran before public send completion").isFalse();
            assertThat(completionAt.get()).isGreaterThan(expiry.startedAt);
            assertThat(rawClosedAtCompletion).as("the exact upstream was closed when send completion was observed").isTrue();
            assertThat(expiry.payloadReadBefore).isZero();
            assertThat(expiry.payloadReadAfter).isZero();
            assertThat(owner.raw.isClosed()).isTrue();
            System.out.println("SOCKS_NATIVE_TIMEOUT protocol=" + protocol + ", scheduled=" + scheduled.get() + ", fired=" + fired.get()
                    + ", finished=" + finished.get() + ", task=" + expiry.nativeTask + ", nativeResult=" + expiry.nativeResult
                    + ", rawOpenAtFire=" + !expiry.rawClosedBefore + ", rawClosedAfterNative=" + expiry.rawClosedAfter
                    + ", closedAtCompletion=" + rawClosedAtCompletion.get() + ", expiryBeforeCompletion=" + (expiry.startedAt < completionAt.get())
                    + ", nativeFinishBeforeCompletion=" + (expiry.finishedAt < completionAt.get()) + ", fixturePayloadRead=" + expiry.payloadReadAfter);
        }

        @Override public void close() throws InterruptedException {
            shutdownNow();
            assertThat(awaitTermination(5, SECONDS)).as("test-owned native timeout scheduler stops").isTrue();
        }
    }

    private static final class NativeExpiry {
        private long startedAt;
        private long scheduledAt;
        private long delayNanos;
        private long finishedAt;
        private String nativeTask;
        private String nativeResult;
        private String schedulingStack;
        private boolean scheduledByNativeWrite;
        private boolean scheduledByBlockedWriter;
        private boolean writerBlockedAtFire;
        private boolean rawClosedBefore;
        private boolean rawClosedAfter;
        private boolean completionDoneBefore;
        private long payloadReadBefore;
        private long payloadReadAfter;
        private Throwable nativeFailure;
    }

    /** Observe the real capability's supplied handle; no endpoint lookup or replacement socket factory is involved. */
    private static final class UpstreamOwner {
        private final Socket raw;
        private final Socket direct;
        private volatile Thread senderThread;

        private UpstreamOwner(final Socket raw, final Socket direct) {
            this.raw = raw;
            this.direct = direct;
        }

        private String awaitNativeWrite(final StreamingPayload payload) throws InterruptedException {
            final long deadline = System.nanoTime() + SECONDS.toNanos(5);
            int consecutive = 0;
            StackTraceElement[] last = new StackTraceElement[0];
            while (System.nanoTime() < deadline) {
                senderThread = payload.latestReader.get();
                last = senderThread == null ? last : senderThread.getStackTrace();
                consecutive = nativeSmtpWrite(last) ? consecutive + 1 : 0;
                if (consecutive >= 3) { return Arrays.toString(last); }
                Thread.sleep(50);
            }
            throw new AssertionError("The actual sender was not observed in sustained direct native SMTP write: " + Arrays.toString(last));
        }

        private boolean inNativeSmtpWrite() {
            return senderThread != null && nativeSmtpWrite(senderThread.getStackTrace());
        }

        private static boolean nativeSmtpWrite(final StackTraceElement[] stack) {
            return Arrays.stream(stack).anyMatch(frame -> frame.getClassName().startsWith("org.eclipse.angus.mail.smtp."))
                    && Arrays.stream(stack).anyMatch(frame -> frame.isNativeMethod()
                    && (frame.getMethodName().toLowerCase(Locale.ROOT).contains("write")
                    || frame.getMethodName().toLowerCase(Locale.ROOT).contains("poll")));
        }
    }

    private static final class StreamingPayload implements DataSource {
        private final AtomicLong generated = new AtomicLong();
        private final AtomicReference<AtomicLong> latestStreamRead = new AtomicReference<>();
        private final AtomicReference<Thread> latestReader = new AtomicReference<>();
        @Override public InputStream getInputStream() {
            final AtomicLong streamRead = new AtomicLong();
            latestStreamRead.set(streamRead);
            return new InputStream() {
                private int remaining = 128 * 1024 * 1024;
                @Override public int read(final byte[] bytes, final int offset, final int length) {
                    latestReader.set(Thread.currentThread());
                    if (remaining == 0) { return -1; }
                    final int count = Math.min(remaining, length);
                    Arrays.fill(bytes, offset, offset + count, (byte) 'q');
                    remaining -= count;
                    generated.addAndGet(count);
                    streamRead.addAndGet(count);
                    return count;
                }
                @Override public int read() {
                    latestReader.set(Thread.currentThread());
                    if (remaining == 0) { return -1; }
                    remaining--;
                    generated.incrementAndGet();
                    streamRead.incrementAndGet();
                    return 'q';
                }
            };
        }
        @Override public OutputStream getOutputStream() throws IOException { throw new IOException("Read-only streaming fixture"); }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public String getName() { return "stream.bin"; }
    }

    private static final class ProxySmtpFixture implements AutoCloseable {
        private final Protocol protocol;
        private final SSLSocketFactory tlsFactory;
        private final ServerSocket listener = new ServerSocket();
        private final ExecutorService peers = Executors.newCachedThreadPool();
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicInteger connections = new AtomicInteger();
        private final AtomicInteger healthyMessages = new AtomicInteger();
        private final AtomicLong payloadBytesRead = new AtomicLong();
        private final CountDownLatch beforeBody = new CountDownLatch(1);
        private final CountDownLatch allowBody = new CountDownLatch(1);
        private final CountDownLatch resumeDraining = new CountDownLatch(1);
        private final CompletableFuture<Void> peerEof = new CompletableFuture<>();
        private final Thread acceptor;
        private volatile int receiveBuffer;
        private volatile boolean stopping;

        private ProxySmtpFixture(final Protocol protocol) throws Exception {
            this.protocol = protocol;
            tlsFactory = protocol == Protocol.SMTP ? null : tlsContext(testKeyStore()).getSocketFactory();
            listener.setReceiveBufferSize(1024);
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16);
            acceptor = new Thread(this::accept, "blocked-write-proxy-listener");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private void accept() {
            try {
                while (!stopping) {
                    final Socket socket = listener.accept();
                    sockets.add(socket);
                    final boolean blocked = connections.incrementAndGet() == 1;
                    peers.execute(() -> serve(socket, blocked));
                }
            } catch (IOException failure) {
                if (!stopping) { peerEof.completeExceptionally(failure); }
            }
        }

        private void serve(final Socket socket, final boolean blocked) {
            try (Socket peer = socket) {
                peer.setSoTimeout(15000);
                peer.setTcpNoDelay(true);
                if (blocked) {
                    peer.setReceiveBufferSize(1024);
                    receiveBuffer = peer.getReceiveBufferSize();
                }
                DataInputStream input = new DataInputStream(peer.getInputStream());
                OutputStream output = peer.getOutputStream();
                assertThat(input.readUnsignedByte()).isEqualTo(5);
                assertThat(input.readNBytes(input.readUnsignedByte())).contains((byte) 2);
                output.write(new byte[] {5, 2});
                output.flush();
                assertThat(input.readUnsignedByte()).isEqualTo(1);
                assertThat(new String(input.readNBytes(input.readUnsignedByte()), UTF_8)).isEqualTo("proxy-user");
                assertThat(new String(input.readNBytes(input.readUnsignedByte()), UTF_8)).isEqualTo("proxy-secret");
                output.write(new byte[] {1, 0});
                output.flush();
                assertThat(input.readNBytes(3)).containsExactly(5, 1, 0);
                final int addressType = input.readUnsignedByte();
                assertThat(addressType).isIn(1, 3, 4);
                input.readNBytes(addressType == 3 ? input.readUnsignedByte() : addressType == 1 ? 4 : 16);
                assertThat(input.readUnsignedShort()).isEqualTo(2525);
                output.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 9, (byte) 221});
                output.flush();
                if (protocol == Protocol.SMTPS) {
                    final SSLSocket tls = tls(peer);
                    input = new DataInputStream(tls.getInputStream());
                    output = tls.getOutputStream();
                }
                writeLine(output, "220 blocked-write-fixture.example.test ESMTP");
                if (protocol == Protocol.STARTTLS) {
                    assertThat(readLine(input)).startsWith("EHLO ");
                    writeLine(output, "250-fixture");
                    writeLine(output, "250-STARTTLS");
                    writeLine(output, "250 OK");
                    assertThat(readLine(input)).isEqualTo("STARTTLS");
                    writeLine(output, "220 Begin TLS");
                    final SSLSocket tls = tls(peer);
                    input = new DataInputStream(tls.getInputStream());
                    output = tls.getOutputStream();
                }
                String line;
                while ((line = readLine(input)) != null) {
                    final String command = line.toUpperCase(Locale.ROOT);
                    if (command.startsWith("EHLO ") || command.startsWith("HELO ") || command.startsWith("MAIL FROM:")
                            || command.startsWith("RCPT TO:") || command.equals("RSET") || command.equals("NOOP")) {
                        writeLine(output, "250 OK");
                    } else if (command.equals("DATA")) {
                        if (blocked) {
                            beforeBody.countDown();
                            assertThat(allowBody.await(10, SECONDS)).isTrue();
                            writeLine(output, "354 Send message");
                            assertThat(resumeDraining.await(30, SECONDS)).isTrue();
                            final byte[] bytes = new byte[4096];
                            int count;
                            try {
                                while ((count = input.read(bytes)) != -1) { payloadBytesRead.addAndGet(count); }
                            } catch (SSLException abruptTlsClose) {
                                if (protocol == Protocol.SMTP) { throw abruptTlsClose; }
                                // Cancellation can terminate TLS without close_notify; this is observed only after the pre-drain checks.
                            }
                            peerEof.complete(null);
                            return;
                        }
                        writeLine(output, "354 Send message");
                        while ((line = readLine(input)) != null && !line.equals(".")) { }
                        if (line == null) { throw new EOFException("Healthy SMTP body was truncated"); }
                        healthyMessages.incrementAndGet();
                        writeLine(output, "250 queued");
                    } else if (command.equals("QUIT")) {
                        writeLine(output, "221 Bye");
                        return;
                    } else {
                        throw new IOException("Unexpected fixture SMTP command: " + line);
                    }
                }
            } catch (Exception | AssertionError failure) {
                if (!stopping) { peerEof.completeExceptionally(failure); }
            } finally {
                sockets.remove(socket);
            }
        }

        private SSLSocket tls(final Socket peer) throws IOException {
            final SSLSocket tls = (SSLSocket) tlsFactory.createSocket(peer, "127.0.0.1", peer.getPort(), true);
            tls.setUseClientMode(false);
            tls.startHandshake();
            return tls;
        }

        private static String readLine(final InputStream input) throws IOException {
            final StringBuilder line = new StringBuilder();
            int next;
            while ((next = input.read()) != -1) {
                if (next == '\n') { return line.toString(); }
                if (next != '\r') { line.append((char) next); }
                if (line.length() > 8192) { throw new IOException("Fixture SMTP line exceeds its bound"); }
            }
            return line.length() == 0 ? null : line.toString();
        }

        private static void writeLine(final OutputStream output, final String line) throws IOException {
            output.write((line + "\r\n").getBytes(US_ASCII));
            output.flush();
        }

        @Override public void close() throws Exception {
            stopping = true;
            listener.close();
            allowBody.countDown();
            resumeDraining.countDown();
            for (Socket socket : sockets) { socket.close(); }
            peers.shutdownNow();
            acceptor.join(5000);
            assertThat(acceptor.isAlive()).isFalse();
            assertThat(peers.awaitTermination(5, SECONDS)).isTrue();
        }
    }
}
