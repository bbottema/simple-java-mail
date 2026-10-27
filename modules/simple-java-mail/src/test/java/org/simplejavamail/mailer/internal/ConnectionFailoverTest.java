package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.SendingLimitsConfig;
import org.simplejavamail.api.mailer.MailSendHandlers;
import jakarta.mail.Message.RecipientType;

import jakarta.mail.Address;
import jakarta.activation.DataSource;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailSender;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.mailer.internal.ratelimit.FactorySendingLimits;

import java.net.ConnectException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.time.Duration;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static testutil.MailerTestConfig.updateMailer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;



@Timeout(20)
class ConnectionFailoverTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @ParameterizedTest
    @CsvSource({"false,false,false", "false,true,false", "true,false,false", "true,true,false",
            "false,false,true", "false,true,true", "true,false,true", "true,true,true"})
    void connectionFailuresKeepThePublicExceptionContract(final boolean usePooling, final boolean asynchronous, final boolean configuredFailover)
            throws Exception {
        final State primary = new State();
        primary.connectFailure = configuredFailover ? new AuthenticationFailedException("rejected credentials")
                : new MessagingException("refused", new ConnectException());
        final State secondary = new State();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final ExecutorService workers = connectionTestExecutor(usePooling);
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(usePooling);
            try (Mailer alternative = builder(secondary).buildMailer()) {
                final MailerFromSessionBuilderImpl configured = updateMailer(builder(primary), fixture -> fixture.withAsyncExecutionConfig(fixture.getAsyncExecutionConfig().toBuilder().withExecutorService(workers).build())).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build());
                if (configuredFailover) {
                    configured.withFailoverMailer(alternative, FailoverPolicy.builder().build());
                }
                try (Mailer initiating = configured.buildMailer()) {
                    final Throwable failure = asynchronous
                            ? catchThrowable(() -> initiating.async().sendMail(email()).getCompletion().join()).getCause()
                            : catchThrowable(() -> initiating.sync().sendMail(email()));
                    assertThat(failure).isInstanceOf(MailException.class);
                    // Pool acquisition retains its upstream setup wrapper; neither path replaces the provider's original failure.
                    final Throwable providerFailure = usePooling ? failure.getCause().getCause() : failure.getCause();
                    assertThat(providerFailure).isSameAs(primary.connectFailure);
                    assertThat(outcomes).hasSize(1);
                    assertThat(outcomes.get(0).getFailure()).containsSame(failure);
                    assertThat(outcomes.get(0).getSubmissionReceipt()).isEmpty();
                    assertThat(primary.messages).isEmpty();
                    assertThat(secondary.connections).hasValue(0);
                }
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void overlappingDirectConnectionsRestorePrimaryPreference(final boolean successFinishesFirst, final boolean observed) throws Exception {
        final State primary = new State();
        final State secondary = new State();
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch bothStarted = new CountDownLatch(2);
        final CountDownLatch finishFailure = new CountDownLatch(1);
        final CountDownLatch finishSuccess = new CountDownLatch(1);
        primary.onConnect = () -> {
            final int attempt = primary.connections.get();
            if (attempt <= 2) {
                firstStarted.countDown();
                bothStarted.countDown();
                await(attempt == 1 ? finishFailure : finishSuccess);
            }
        };
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final ExecutorService workers = connectionTestExecutor(false);
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            try (Mailer alternative = builder(secondary).buildMailer()) {
                final MailerFromSessionBuilderImpl configured = updateMailer(builder(primary), fixture -> fixture.withAsyncExecutionConfig(fixture.getAsyncExecutionConfig().toBuilder().withExecutorService(workers).build()))
                        .withFailoverMailer(alternative, FailoverPolicy.builder().build());
                if (observed) {
                    configured.withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build());
                }
                try (Mailer initiating = configured.buildMailer()) {
                    try {
                        final var failedConnectionSend = initiating.async().sendMail(email());
                        await(firstStarted);
                        final var successfulConnectionSend = initiating.async().sendMail(email());
                        await(bothStarted);
                        if (successFinishesFirst) {
                            finishSuccess.countDown();
                            successfulConnectionSend.getCompletion().get(5, TimeUnit.SECONDS);
                        }
                        primary.connectFailure = new MessagingException("refused", new ConnectException());
                        finishFailure.countDown();
                        failedConnectionSend.getCompletion().get(5, TimeUnit.SECONDS);
                        primary.connectFailure = null;
                        finishSuccess.countDown();
                        successfulConnectionSend.getCompletion().get(5, TimeUnit.SECONDS);

                        assertThat(primary.messages).hasSize(1);
                        assertThat(secondary.messages).hasSize(1);
                        assertThat(((MailerImpl) initiating).connectionObserver().isBackgroundConnectionAllowed()).isTrue();
                        initiating.sync().sendMail(email());
                        assertThat(primary.messages).hasSize(2);
                        assertThat(secondary.messages).hasSize(1);
                        assertThat(primary.connections).hasValue(3);
                        assertThat(outcomes).hasSize(observed ? 3 : 0);
                    } finally {
                        finishFailure.countDown();
                        finishSuccess.countDown();
                    }
                }
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "false,true,false", "true,false,false", "true,true,false",
            "false,false,true", "false,true,true", "true,false,true", "true,true,true"})
    void retainedScopesBorrowPoolLeasesOnlyWhenFailoverIsConfigured(final boolean usePooling, final boolean configuredFailover,
            final boolean simpleBatch) throws Exception {
        final State primary = new State();
        primary.connectFailure = new MessagingException("refused", new ConnectException());
        final State selected = new State();
        final AtomicInteger closes = new AtomicInteger();
        selected.onClose = closes::incrementAndGet;
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(usePooling);
            try (Mailer destination = builder(selected).buildMailer();
                 Mailer initiating = builder(primary).withFailoverMailer(destination, FailoverPolicy.builder().build()).buildMailer()) {
                final Mailer sender = configuredFailover ? initiating : destination;
                if (simpleBatch) {
                    sender.sync().sendMailsInSimpleBatch(List.of(email(), email()));
                } else {
                    sender.withOpenConnection(connection -> {
                        connection.sendMail(email());
                        connection.sendMail(email());
                        assertThat(closes).hasValue(0);
                    });
                }
                final boolean retainedLease = usePooling && configuredFailover;
                assertThat(selected.messages).hasSize(2);
                assertThat(selected.connections).hasValue(1);
                assertThat(closes).hasValue(retainedLease ? 0 : 1);
                destination.sync().sendMail(email());
                assertThat(selected.connections).hasValue(retainedLease ? 1 : 2);
                assertThat(selected.messages).hasSize(3);
            }
        }
    }

    /** Mockito's static module selection is thread-local; make it match the classpath simulated during builder construction. */
    private static ExecutorService connectionTestExecutor(final boolean usePooling) {
        return Executors.newFixedThreadPool(2, worker -> new Thread(() -> {
            try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
                modules.when(ModuleLoader::batchModuleAvailable).thenReturn(usePooling);
                worker.run();
            }
        }, "connection-contract-test"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void buildingWithoutFailoverLeavesBackgroundRecoveryEnabled(final boolean callerOwnedSession) throws Exception {
        try (Mailer plain = callerOwnedSession ? builder(new State()).buildMailer()
                : mail.mailerBuilder().withSMTPServer("localhost", 2525).withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).build()).buildMailer()) {
            final DestinationConnectionObserver observer = ((MailerImpl) plain).connectionObserver();
            observer.connectionStarting().failed(new ConnectException("temporary outage"));
            assertThat(plain.getOperationalConfig().getFailoverPolicy()).isNull();
            assertThat(observer.isBackgroundConnectionAllowed()).isTrue();
        }
    }

    @Test
    void clearingFailoverBeforeBuildingDoesNotEnableRecoveryGatesOnEitherMailer() throws Exception {
        try (Mailer alternative = builder(new State()).buildMailer();
             Mailer plain = builder(new State()).withFailoverMailer(alternative, FailoverPolicy.builder().build())
                     .clearFailoverMailer().buildMailer()) {
            for (Mailer participant : List.of(plain, alternative)) {
                final DestinationConnectionObserver observer = ((MailerImpl) participant).connectionObserver();
                observer.connectionStarting().failed(new ConnectException("temporary outage"));
                assertThat(observer.isBackgroundConnectionAllowed()).isTrue();
            }
        }
    }

    @Test
    void configuringNestedFailoverEnablesRecoveryGatesOnlyOnItsParticipants() throws Exception {
        try (Mailer unrelated = builder(new State()).buildMailer();
             Mailer last = builder(new State()).buildMailer();
             Mailer middle = builder(new State()).withFailoverMailer(last, FailoverPolicy.builder().build()).buildMailer();
             Mailer first = builder(new State()).withFailoverMailer(middle, FailoverPolicy.builder().build()).buildMailer()) {
            for (Mailer participant : List.of(first, middle, last, unrelated)) {
                final DestinationConnectionObserver observer = ((MailerImpl) participant).connectionObserver();
                observer.connectionStarting().failed(new ConnectException("temporary outage"));
                assertThat(observer.isBackgroundConnectionAllowed()).isEqualTo(participant == unrelated);
            }
        }
    }

    @Test
    void ordinaryPoolReplenishesAfterFailedSetupWithoutAnotherSend() throws Exception {
        final State server = new State();
        server.connectFailure = new MessagingException("refused", new ConnectException());
        final CountDownLatch initialConnectionStarted = new CountDownLatch(1);
        final CountDownLatch failInitialConnection = new CountDownLatch(1);
        final CountDownLatch replacementStarted = new CountDownLatch(1);
        server.onConnect = () -> {
            if (server.connections.get() == 1) {
                initialConnectionStarted.countDown();
                await(failInitialConnection);
            } else {
                replacementStarted.countDown();
            }
        };
        server.onClose = () -> server.connectFailure = null;

        try (Mailer plain = updateMailer(builder(server), fixture -> fixture.withConnectionPoolConfig(fixture.getConnectionPoolConfig().toBuilder().withCoreSize(1).build())).buildMailer()) {
            // Release the failure only after the actual builder has finished configuring the recovery gate.
            try {
                await(initialConnectionStarted);
            } finally {
                failInitialConnection.countDown();
            }
            await(replacementStarted);
            assertThat(server.messages).isEmpty();

            // Only now send through the pool: foreground work must not be what triggered its replacement.
            plain.sync().sendMail(email());
            assertThat(server.connections).hasValue(2);
            assertThat(server.messages).hasSize(1);
        } finally {
            failInitialConnection.countDown();
        }
    }

    @Test
    void configuredFailoverDefersBackgroundRecoveryUntilADueOperationRecoversThePrimary() throws Exception {
        final AtomicLong clock = new AtomicLong();
        final FactoryMailerRuntime runtime = new FactoryMailerRuntime(mail.getConfig(), new FactorySendingLimits(), clock::get);
        final State primary = new State();
        primary.connectFailure = new MessagingException("refused", new ConnectException());
        final State secondary = new State();
        final CountDownLatch initialConnectionStarted = new CountDownLatch(1);
        final CountDownLatch failInitialConnection = new CountDownLatch(1);
        final CountDownLatch failedConnectionDisposed = new CountDownLatch(1);
        final CountDownLatch backgroundReplenished = new CountDownLatch(1);
        primary.onConnect = () -> {
            if (primary.connections.get() == 1) {
                initialConnectionStarted.countDown();
                await(failInitialConnection);
            } else if (primary.connections.get() == 3) {
                backgroundReplenished.countDown();
            }
        };
        primary.onClose = () -> {
            primary.connectFailure = null;
            failedConnectionDisposed.countDown();
        };
        final FailoverPolicy policy = FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofSeconds(30)).build();
        try (Mailer alternative = builder(secondary).buildMailer();
             Mailer initiating = updateMailer(builder(primary, runtime), fixture -> fixture.withConnectionPoolConfig(fixture.getConnectionPoolConfig().toBuilder().withCoreSize(2).withMaxSize(2).withExpireAfter(Duration.ofMillis(0)).build()))
                     // Count recovery allocations, not replacements caused by wall-clock idle expiry.

                     .withFailoverMailer(alternative, policy).buildMailer()) {
            try {
                await(initialConnectionStarted);
            } finally {
                failInitialConnection.countDown();
            }
            await(failedConnectionDisposed);
            final DestinationConnectionObserver observer = ((MailerImpl) initiating).connectionObserver();
            assertThat(observer.isBackgroundConnectionAllowed()).isFalse();
            initiating.sync().sendMail(email());
            assertThat(primary.connections).hasValue(1);
            assertThat(secondary.messages).hasSize(1);

            clock.set(Duration.ofSeconds(30).toNanos());
            initiating.sync().sendMail(email());
            assertThat(primary.messages).hasSize(1);
            assertThat(observer.isBackgroundConnectionAllowed()).isTrue();
            await(backgroundReplenished);
            assertThat(primary.connections).hasValue(3);
        } finally {
            failInitialConnection.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void additionalConnectionAttemptsDoNotSkipRecoveryBackoffSteps(final boolean usePooling) throws Exception {
        final AtomicLong clock = new AtomicLong();
        final FactoryMailerRuntime runtime = new FactoryMailerRuntime(mail.getConfig(), new FactorySendingLimits(), clock::get);
        final State primary = new State();
        primary.connectFailure = new MessagingException("refused", new ConnectException());
        final State secondary = new State();
        final FailoverPolicy policy = FailoverPolicy.builder().withPrimaryConnectionAttempts(3)
                .withPrimaryRecheckInterval(Duration.ofSeconds(30)).withPrimaryRecheckBackoff(2, Duration.ofMinutes(2)).build();
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(usePooling);
            try (Mailer alternative = builder(secondary).buildMailer();
                 Mailer initiating = builder(primary, runtime).withFailoverMailer(alternative, policy).buildMailer()) {
                initiating.sync().sendMail(email());
                assertThat(primary.connections).hasValue(3);
                clock.set(Duration.ofSeconds(29).toNanos());
                initiating.sync().sendMail(email());
                assertThat(primary.connections).hasValue(3);
                clock.set(Duration.ofSeconds(30).toNanos());
                initiating.sync().sendMail(email());
                assertThat(primary.connections).hasValue(6);
                clock.set(Duration.ofSeconds(89).toNanos());
                initiating.sync().sendMail(email());
                assertThat(primary.connections).hasValue(6);
                clock.set(Duration.ofSeconds(90).toNanos());
                initiating.sync().sendMail(email());
                assertThat(primary.connections).hasValue(9);

                clock.set(Duration.ofSeconds(210).toNanos());
                primary.connectFailure = null;
                initiating.sync().sendMail(email());
                assertThat(primary.connections).hasValue(10);
                assertThat(primary.messages).hasSize(1);
                assertThat(secondary.messages).hasSize(5);
            }
        }
    }

    @Test
    void directSendsWithoutPoolingRetainReceiptIdentityAndCloseBothDestinations() throws Exception {
        final State primary = new State();
        primary.connectFailure = new MessagingException("refused", new ConnectException());
        final AtomicInteger primaryClosed = new AtomicInteger();
        primary.onClose = primaryClosed::incrementAndGet;
        final State secondary = new State();
        final AtomicInteger secondaryClosed = new AtomicInteger();
        secondary.onClose = secondaryClosed::incrementAndGet;
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final ExecutorService direct = mock(ExecutorService.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(direct).execute(any(Runnable.class));
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            try (Mailer b = builder(secondary).buildMailer();
                 Mailer a = updateMailer(builder(primary), fixture -> fixture.withAsyncExecutionConfig(fixture.getAsyncExecutionConfig().toBuilder().withExecutorService(direct).build())).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build())
                         .withFailoverMailer(b, FailoverPolicy.builder().build()).buildMailer()) {
                final var synchronous = a.sync().sendMail(email());
                final var asynchronous = a.async().sendMail(email()).getCompletion().get(5, TimeUnit.SECONDS);
                assertThat(outcomes).hasSize(2).allMatch(MailSendOutcome::isSuccessful);
                assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(synchronous);
                assertThat(outcomes.get(1).getSubmissionReceipt()).containsSame(asynchronous);
                assertThat(primary.connections).hasValue(1);
                assertThat(primaryClosed).hasValue(1);
                assertThat(secondary.connections).hasValue(2);
                assertThat(secondaryClosed).hasValue(2);
                assertThat(secondary.messages).hasSize(2);
            }
        }
    }

    @Test
    void cancellationDuringAdditionalAttemptDelayNeverVisitsTheFailoverDestination() throws Exception {
        final State primary = new State();
        primary.connectFailure = new MessagingException("refused", new ConnectException());
        final CountDownLatch disposed = new CountDownLatch(1);
        primary.onClose = disposed::countDown;
        final State secondary = new State();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final FailoverPolicy policy = FailoverPolicy.builder().withPrimaryConnectionAttempts(3)
                .withPrimaryConnectionRetryDelay(Duration.ofDays(1)).build();
        try (Mailer b = builder(secondary).buildMailer();
             Mailer a = builder(primary).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).withFailoverMailer(b, policy).buildMailer()) {
            final var send = a.async().sendMail(email());
            try {
                assertThat(disposed.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                send.requestCancellation();
            }
            assertThatThrownBy(() -> send.getCompletion().get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(org.simplejavamail.api.mailer.MailSendCancelledException.class);
            assertThat(primary.connections).hasValue(1);
            assertThat(secondary.connections).hasValue(0);
            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).getSubmissionReceipt()).isEmpty();
            assertThat(primary.messages).isEmpty();
        }
    }

    @Test
    void anEnvelopeLockCannotEscapeThroughAFailoverCustomMailer() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final SimpleJavaMailConfig locked = ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.locked.defaults.bcc.address", "archive@example.org")).load();
        final CustomMailer custom = new CustomMailer() {
            @Override public void testConnection(final OperationalConfig config, final Session session) { }
            @Override public void sendMessage(final OperationalConfig config, final Session session, final Email email, final MimeMessage message) {
                calls.incrementAndGet();
            }
        };
        try (Mailer destination = builder(new State()).withCustomMailer(custom).buildMailer()) {
            assertThatThrownBy(() -> builder(new State(), new FactoryMailerRuntime(locked), locked)
                    .withFailoverMailer(destination, FailoverPolicy.builder().build()).buildMailer())
                    .hasMessageContaining("simplejavamail.locked.defaults.bcc.address", "CustomMailer", "callback has not been invoked");
            assertThat(calls).hasValue(0);
            destination.sync().sendMail(email());
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void failedConstructionClosesOnlyItsOwnedNamedRouteAndExecutor() throws Exception {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.failover.mailer", "secondary",
                "simplejavamail.mailers.secondary.transport.mode.logging.only", "true")).load();
        final FactoryMailerRuntime runtime = new FactoryMailerRuntime(config);
        try (Mailer established = new MailerRegularBuilderImpl(config, runtime).clearFailoverMailer().withTransportModeLoggingOnly(true)
                .withSendingLimitsConfig(SendingLimitsConfig.builder().withRateLimitGroup("shared").withMessageRateLimit(1, Duration.ofSeconds(1)).build()).buildMailer()) {
            try (MockedConstruction<MailSendExecutor> executors = mockConstruction(MailSendExecutor.class, (executor, context) -> {
                final AtomicBoolean shutdown = new AtomicBoolean();
                when(executor.isShutdown()).thenAnswer(invocation -> shutdown.get());
                when(executor.awaitTermination(anyLong(), any())).thenAnswer(invocation -> shutdown.get());
                doAnswer(invocation -> { shutdown.set(true); return null; }).when(executor).shutdown();
            })) {
                final MailerRegularBuilderImpl failing = new MailerRegularBuilderImpl(config, runtime);
                assertThatThrownBy(() -> failing.withTransportModeLoggingOnly(true).withSendingLimitsConfig(failing.getSendingLimitsConfig().toBuilder()
                        .withRateLimitGroup("shared").withMessageRateLimit(2, Duration.ofSeconds(1)).build()).buildMailer())
                        .hasMessageContaining("sharing a rate-limit group");
                assertThat(executors.constructed()).hasSize(2);
                for (MailSendExecutor owned : executors.constructed()) {
                    verify(owned).shutdown();
                    assertThat(owned.isShutdown()).isTrue();
                }
            }
            assertThat(established.getOperationalConfig().getExecutorService().isShutdown()).isFalse();
            established.sync().sendMail(email());
        }
    }

    @Test
    void closingASelectedParticipantWaitsForItsDelegatedSend() throws Exception {
        final State primary = new State();
        primary.connectFailure = new MessagingException("refused", new ConnectException());
        final State secondary = new State();
        final CountDownLatch submitting = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        secondary.onSend = () -> { submitting.countDown(); await(release); };
        try (Mailer b = builder(secondary).buildMailer();
             Mailer a = builder(primary).withFailoverMailer(b, FailoverPolicy.builder().build()).buildMailer()) {
            final var send = a.async().sendMail(email());
            try {
                assertThat(submitting.await(5, TimeUnit.SECONDS)).isTrue();
                final var shutdown = b.closeAsync();
                assertThat(shutdown.isDone()).isFalse();
                assertThat(send.getCompletion()).isNotDone();
                release.countDown();
                send.getCompletion().get(5, TimeUnit.SECONDS);
                shutdown.get(5, TimeUnit.SECONDS);
                assertThat(secondary.messages).hasSize(1);
            } finally {
                release.countDown();
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void aDueRecheckDoesNotHoldOtherSendsAwayFromTheWorkingDestination() throws Exception {
        final AtomicLong clock = new AtomicLong();
        final FactoryMailerRuntime runtime = new FactoryMailerRuntime(mail.getConfig(), new FactorySendingLimits(), clock::get);
        final State primary = new State();
        primary.connectFailure = new MessagingException("refused", new ConnectException());
        final State secondary = new State();
        final CountDownLatch rechecking = new CountDownLatch(1);
        final CountDownLatch finishRecheck = new CountDownLatch(1);
        try (Mailer b = builder(secondary, runtime).buildMailer();
             Mailer a = updateMailer(builder(primary, runtime), fixture -> fixture.withAsyncExecutionConfig(fixture.getAsyncExecutionConfig().toBuilder().withThreadPoolSize(3).build())).withFailoverMailer(b, FailoverPolicy.builder().build()).buildMailer()) {
            a.sync().sendMail(email());
            primary.connectFailure = null;
            primary.onConnect = () -> { rechecking.countDown(); await(finishRecheck); };
            clock.set(Duration.ofSeconds(31).toNanos());
            final var recheck = a.async().sendMail(email());
            try {
                assertThat(rechecking.await(5, TimeUnit.SECONDS)).isTrue();
                a.async().sendMail(email()).getCompletion().get(5, TimeUnit.SECONDS);
                assertThat(secondary.messages).hasSize(2);
                assertThat(primary.connections).hasValue(2);
                assertThat(recheck.getCompletion()).isNotDone();
                recheck.requestCancellation();
            } finally {
                finishRecheck.countDown();
            }
            assertThatThrownBy(() -> recheck.getCompletion().join()).hasCauseInstanceOf(org.simplejavamail.api.mailer.MailSendCancelledException.class);
            assertThat(primary.messages).isEmpty();
            a.sync().sendMail(email());
            assertThat(primary.messages).hasSize(1);
        } finally {
            finishRecheck.countDown();
        }
    }

    @Test
    void aBatchObserverCannotWaitForItsOwnRetainedFailoverMailerToClose() throws Exception {
        final State primary = new State();
        primary.connectFailure = new MessagingException("refused", new ConnectException());
        final State secondary = new State();
        final AtomicInteger observations = new AtomicInteger();
        try (Mailer b = builder(secondary).buildMailer();
             Mailer a = builder(primary).withFailoverMailer(b, FailoverPolicy.builder().build()).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcome -> {
                 assertThatThrownBy(b::close).isInstanceOf(IllegalStateException.class).hasMessageContaining("own shutdown");
                 observations.incrementAndGet();
             }).build()).buildMailer()) {
            a.sync().sendMailsInSimpleBatch(List.of(email(), email()));
            assertThat(observations).hasValue(2);
            assertThat(secondary.messages).hasSize(2);
        }
    }

    @Test
    void ordinaryPoolCapacityTimeoutIsNotAFailoverIncident() throws Exception {
        final State primary = new State();
        final State secondary = new State();
        final CountDownLatch sending = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        primary.onSend = () -> { sending.countDown(); await(release); };
        try (Mailer b = builder(secondary).buildMailer();
             Mailer a = updateMailer(builder(primary), fixture -> fixture.withConnectionPoolConfig(fixture.getConnectionPoolConfig().toBuilder().withClaimTimeout(Duration.ofMillis(50)).build()).withAsyncExecutionConfig(fixture.getAsyncExecutionConfig().toBuilder().withThreadPoolSize(2).build()))
                     .withFailoverMailer(b, FailoverPolicy.builder().build()).buildMailer()) {
            final var occupied = a.async().sendMail(email());
            try {
                assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> a.async().sendMail(email()).getCompletion().get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(org.simplejavamail.batch.BatchTransportException.class).hasRootCauseInstanceOf(IllegalStateException.class)
                        .rootCause().hasMessageContaining("Timed out waiting for an available SMTP transport");
                assertThat(secondary.connections).hasValue(0);
            } finally {
                release.countDown();
            }
            occupied.getCompletion().get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void incompleteDisposalStopsRoutingAndRetainsTheSuppressedFailure() throws Exception {
        final State primary = new State();
        primary.connectFailure = new MessagingException("refused", new ConnectException());
        primary.closeFailure = new MessagingException("could not close");
        final State secondary = new State();
        try (Mailer b = builder(secondary).buildMailer();
             Mailer a = builder(primary).withFailoverMailer(b, FailoverPolicy.builder().build()).buildMailer()) {
            assertThatThrownBy(() -> a.sync().sendMail(email())).isInstanceOf(MailerException.class)
                    .hasMessageContaining("cleaning it up also failed").satisfies(failure ->
                            assertThat(failure.getCause().getCause().getSuppressed()).containsExactly(primary.closeFailure));
            assertThat(secondary.connections).hasValue(0);
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    @Test
    void refusalUsesSelectedTemplatesAndSubsequentSendsSkipFailedPrimary() throws Exception {
        final State primary = new State();
        primary.connectFailure = new MessagingException("refused", new ConnectException("refused"));
        final State secondary = new State();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (Mailer b = builder(secondary).withEmailOverrides(template("b")).buildMailer();
             Mailer a = builder(primary).withEmailOverrides(template("a")).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build())
                     .withFailoverMailer(b, FailoverPolicy.builder().build()).buildMailer()) {
            a.sync().sendMail(email());
            a.async().sendMail(email()).getCompletion().join();
            assertThat(primary.connections).hasValue(1);
            assertThat(primary.messages).isEmpty();
            assertThat(secondary.messages).hasSize(2);
            for (Message message : secondary.messages) {
                assertThat(message.getHeader("X-Relay")).containsExactly("b");
                assertThat(message.getFrom()).extracting(Object::toString).containsExactly("b@example.org");
                assertThat(message.getAllRecipients()).extracting(Object::toString)
                        .containsExactly("customer@example.org", "b-maintainer@example.org");
            }
            assertThat(outcomes).hasSize(2).allMatch(MailSendOutcome::isSuccessful);
            assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getConnectionHistory()).hasSize(2);
            assertThat(outcomes.get(1).getDiagnostics().orElseThrow().getConnectionHistory().get(0).isConnectionAttempted()).isFalse();
        }
    }

    @Test
    void noReplayAfterProviderInvocationEvenWhenItReportsFailure() throws Exception {
        final State primary = new State();
        primary.sendFailure = new MessagingException("final reply lost");
        final State secondary = new State();
        try (Mailer b = builder(secondary).buildMailer();
             Mailer a = builder(primary).withFailoverMailer(b, FailoverPolicy.builder().build()).buildMailer()) {
            assertThatThrownBy(() -> a.sync().sendMail(email())).isInstanceOf(MailSubmissionException.class);
            assertThat(primary.messages).hasSize(1);
            assertThat(secondary.connections).hasValue(0);
            assertThat(secondary.messages).isEmpty();
        }
    }

    @Test
    void selectedMaintainerBccSupplementsMandatoryArchiveWithoutPrimaryTemplateContamination() throws Exception {
        final SimpleJavaMailConfig locked = ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.locked.defaults.bcc.address", "archive@example.org")).load();
        final State primary = new State();
        primary.connectFailure = new MessagingException("refused", new ConnectException());
        final State secondary = new State();
        try (Mailer b = builder(secondary).withEmailDefaults(template("b")).buildMailer();
             Mailer a = builder(primary, new FactoryMailerRuntime(locked), locked).withEmailDefaults(template("a"))
                     .withFailoverMailer(b, FailoverPolicy.builder().build()).buildMailer()) {
            a.sync().sendMail(email());
            assertThat(secondary.messages).hasSize(1);
            assertThat(secondary.messages.get(0).getAllRecipients()).extracting(Object::toString)
                    .containsExactlyInAnyOrder("customer@example.org", "b-maintainer@example.org", "archive@example.org");
            assertThat(secondary.messages.get(0).getHeader("X-Relay")).containsExactly("b");
        }
    }

    @Test
    void failedDestinationsDoNotReadAttachmentsOrAddSerializationWork() throws Exception {
        final AtomicInteger reads = new AtomicInteger();
        final DataSource attachment = new DataSource() {
            @Override public InputStream getInputStream() { reads.incrementAndGet(); return new ByteArrayInputStream(new byte[4096]); }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "application/octet-stream"; }
            @Override public String getName() { return "fixture.bin"; }
        };
        final Email original = mail.emailBuilder().copying(email()).withAttachment("fixture.bin", attachment).buildEmail();
        final State primary = new State();
        primary.connectFailure = new MessagingException("refused", new ConnectException());
        final State secondary = new State();
        secondary.serialize = true;
        try (Mailer b = builder(secondary).buildMailer();
             Mailer a = builder(primary).withFailoverMailer(b, FailoverPolicy.builder().build()).buildMailer()) {
            b.sync().sendMail(original);
            final int baseline = reads.getAndSet(0);
            assertThat(baseline).isPositive();
            a.sync().sendMail(original);
            assertThat(reads).hasValue(baseline);
            assertThat(primary.messages).isEmpty();
            assertThat(secondary.messages).hasSize(2);
        }
    }

    @Test
    void authenticationRequiresItsOwnOptIn() throws Exception {
        final State primary = new State();
        primary.connectFailure = new AuthenticationFailedException("rejected");
        final State secondary = new State();
        try (Mailer b = builder(secondary).buildMailer();
             Mailer strict = builder(primary).withFailoverMailer(b, FailoverPolicy.builder().build()).buildMailer();
             Mailer permitted = builder(primary).withFailoverMailer(b,
                     FailoverPolicy.builder().withFailoverOnAuthenticationFailure(true).build()).buildMailer()) {
            assertThatThrownBy(() -> strict.sync().sendMail(email())).isInstanceOf(ConnectionSetupFailure.class);
            assertThat(secondary.messages).isEmpty();
            permitted.sync().sendMail(email());
            assertThat(secondary.messages).hasSize(1);
        }
    }

    @Test
    void namedDefinitionIsIndependentAndLockedRelationshipCannotBeCleared() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("simplejavamail.smtp.host", "primary.example.org");
        properties.setProperty("simplejavamail.locked.failover.mailer", "secondary");
        properties.setProperty("simplejavamail.mailers.secondary.smtp.host", "secondary.example.org");
        properties.setProperty("simplejavamail.mailers.secondary.defaults.from.address", "secondary@example.org");
        final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder().withProperties(properties).load());
        assertThatThrownBy(() -> configured.mailerBuilder().clearFailoverMailer().buildMailer())
                .hasMessageContaining("simplejavamail.locked.failover.mailer");
        try (MailerImpl primary = (MailerImpl) configured.mailerBuilder().withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).build()).buildMailer()) {
            assertThat(primary.failoverMailer().getServerConfig().getHost()).isEqualTo("secondary.example.org");
            assertThat(primary.failoverMailer().getOperationalConfig().getFailoverPolicy()).isNull();
            assertThat(primary.getOperationalConfig().getFailoverPolicy()).isEqualTo(FailoverPolicy.builder().build());
        }
    }

    @Test
    void namedCyclesAndIncompletePoliciesFailBeforeConstruction() {
        final Properties values = new Properties();
        values.setProperty("simplejavamail.failover.primary.connectionattempts", "2");
        assertThatThrownBy(() -> SimpleJavaMail.withConfig(ConfigLoader.builder().withProperties(values).load()).mailerBuilder())
                .hasMessageContaining("simplejavamail.failover.mailer");
        values.setProperty("simplejavamail.failover.mailer", "a");
        values.setProperty("simplejavamail.mailers.a.failover.mailer", "b");
        values.setProperty("simplejavamail.mailers.b.failover.mailer", "a");
        assertThatThrownBy(() -> SimpleJavaMail.withConfig(ConfigLoader.builder().withProperties(values).load()).mailerBuilder().buildMailer())
                .hasMessageContaining("cycle");
    }

    @Test
    void lazyBatchReturnsToRecoveredPrimaryOnlyBetweenEmails() throws Exception {
        final AtomicLong clock = new AtomicLong();
        final FactoryMailerRuntime runtime = new FactoryMailerRuntime(mail.getConfig(), new FactorySendingLimits(), clock::get);
        final State primary = new State();
        primary.connectFailure = new MessagingException("refused", new ConnectException());
        final State secondary = new State();
        secondary.onSend = () -> { primary.connectFailure = null; clock.set(Duration.ofSeconds(31).toNanos()); };
        final AtomicInteger iterators = new AtomicInteger();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (Mailer b = builder(secondary, runtime).withEmailOverrides(template("b")).buildMailer();
             Mailer a = builder(primary, runtime).withEmailOverrides(template("a")).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build())
                     .withFailoverMailer(b, FailoverPolicy.builder().build()).buildMailer()) {
            a.async().sendMailsInSimpleBatch(() -> {
                iterators.incrementAndGet();
                return List.of(email(), email(), email(), email()).iterator();
            }).getCompletion().join();
            assertThat(iterators).hasValue(1);
            assertThat(primary.connections).hasValue(2);
            assertThat(secondary.messages).hasSize(1);
            assertThat(primary.messages).hasSize(3);
            assertThat(outcomes).hasSize(4).allMatch(MailSendOutcome::isSuccessful);
            assertThat(outcomes).allSatisfy(outcome -> {
                assertThat(outcome.getDiagnostics().orElseThrow().getConnectionHistory()).isEmpty();
                assertThat(outcome.getDiagnostics().orElseThrow().getConnectionAcquisition().getElapsed()).isNull();
            });
        }
    }

    @Test
    void openConnectionStaysPinnedAndInvokesCallbackOnceDespiteRecovery() throws Exception {
        final AtomicLong clock = new AtomicLong();
        final FactoryMailerRuntime runtime = new FactoryMailerRuntime(mail.getConfig(), new FactorySendingLimits(), clock::get);
        final State primary = new State();
        primary.connectFailure = new MessagingException("refused", new ConnectException());
        final State secondary = new State();
        secondary.onSend = () -> { primary.connectFailure = null; clock.set(Duration.ofSeconds(31).toNanos()); };
        final AtomicInteger callbacks = new AtomicInteger();
        try (Mailer b = builder(secondary, runtime).buildMailer();
             Mailer a = builder(primary, runtime).withFailoverMailer(b, FailoverPolicy.builder().build()).buildMailer()) {
            a.withOpenConnection(sender -> {
                callbacks.incrementAndGet();
                sender.sendMail(email());
                sender.sendMail(email());
                assertThatThrownBy(b::close).isInstanceOf(IllegalStateException.class).hasMessageContaining("own shutdown");
            });
            assertThat(callbacks).hasValue(1);
            assertThat(primary.connections).hasValue(1);
            assertThat(secondary.messages).hasSize(2);
            a.sync().sendMail(email());
            assertThat(primary.messages).hasSize(1);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void handledOpenConnectionSendFailureDoesNotHideCleanupFailure(final boolean usePooling, final boolean sendAgain) throws Exception {
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(usePooling);
            final State primary = new State();
            primary.connectFailure = new MessagingException("refused", new ConnectException());
            final State secondary = new State();
            secondary.sendFailure = new MessagingException("submission rejected");
            secondary.closeFailure = new MessagingException("shared close failed");
            final AtomicInteger closes = new AtomicInteger();
            secondary.onClose = closes::incrementAndGet;
            final AtomicReference<Throwable> handled = new AtomicReference<>();
            final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
            try (Mailer b = builder(secondary).buildMailer();
                 Mailer a = builder(primary).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build())
                         .withFailoverMailer(b, FailoverPolicy.builder().build()).buildMailer()) {
                final Throwable scopeFailure = catchThrowable(() -> a.withOpenConnection(sender -> {
                    handled.set(catchThrowable(() -> sender.sendMail(email())));
                    assertThat(handled.get()).isInstanceOf(MailSubmissionException.class);
                    if (sendAgain) {
                        secondary.sendFailure = null;
                        sender.sendMail(email());
                    }
                }));
                assertThat(scopeFailure).hasRootCause(secondary.closeFailure);
                assertThat(handled.get()).hasNoSuppressedExceptions();
                assertThat(outcomes).hasSize(sendAgain ? 2 : 1);
                assertThat(outcomes.get(0).getFailure()).containsSame(handled.get());
                if (sendAgain) {
                    assertThat(outcomes.get(1).isSuccessful()).isTrue();
                }
                assertThat(closes).hasValue(1);
                assertThat(primary.messages).isEmpty();
                assertThat(secondary.messages).hasSize(sendAgain ? 2 : 1);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void callbackFailureAfterHandledSendKeepsCleanupSuppressedOnTheEscapingException(final boolean usePooling) throws Exception {
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(usePooling);
            final State primary = new State();
            primary.connectFailure = new MessagingException("refused", new ConnectException());
            final State secondary = new State();
            secondary.sendFailure = new MessagingException("submission rejected");
            secondary.closeFailure = new MessagingException("shared close failed");
            final AtomicReference<Throwable> handled = new AtomicReference<>();
            final IOException callbackFailure = new IOException("application callback failed");
            try (Mailer b = builder(secondary).buildMailer();
                 Mailer a = builder(primary).withFailoverMailer(b, FailoverPolicy.builder().build()).buildMailer()) {
                final Throwable scopeFailure = catchThrowable(() -> a.withOpenConnection(sender -> {
                    handled.set(catchThrowable(() -> sender.sendMail(email())));
                    throw callbackFailure;
                }));
                assertThat(scopeFailure).isSameAs(callbackFailure);
                assertThat(scopeFailure.getSuppressed()).singleElement()
                        .satisfies(cleanup -> assertThat(cleanup).hasRootCause(secondary.closeFailure));
                assertThat(handled.get()).isInstanceOf(MailSubmissionException.class).hasNoSuppressedExceptions();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void escapingOpenConnectionSendFailureKeepsItsIdentityAndSuppressedCleanup(final boolean usePooling) throws Exception {
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(usePooling);
            final State primary = new State();
            primary.connectFailure = new MessagingException("refused", new ConnectException());
            final State secondary = new State();
            secondary.sendFailure = new MessagingException("submission rejected");
            secondary.closeFailure = new MessagingException("shared close failed");
            final AtomicReference<RuntimeException> escaping = new AtomicReference<>();
            final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
            try (Mailer b = builder(secondary).buildMailer();
                 Mailer a = builder(primary).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build())
                         .withFailoverMailer(b, FailoverPolicy.builder().build()).buildMailer()) {
                final Throwable scopeFailure = catchThrowable(() -> a.withOpenConnection(sender -> {
                    try {
                        sender.sendMail(email());
                    } catch (RuntimeException failure) {
                        escaping.set(failure);
                        throw failure;
                    }
                }));
                assertThat(scopeFailure).isSameAs(escaping.get()).isInstanceOf(MailSubmissionException.class);
                assertThat(scopeFailure.getSuppressed()).singleElement()
                        .satisfies(cleanup -> assertThat(cleanup).hasRootCause(secondary.closeFailure));
                assertThat(outcomes).hasSize(1);
                assertThat(outcomes.get(0).getFailure()).containsSame(scopeFailure);
                assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(((MailSubmissionException) scopeFailure).getSubmissionReceipt().orElseThrow());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void openConnectionRejectsForeignAndEscapedSenderCallsBeforeAnySendWork(final boolean useFailover, final boolean usePooling) throws Exception {
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(usePooling);
            final State primary = new State();
            if (useFailover) {
                primary.connectFailure = new MessagingException("refused", new ConnectException());
            }
            final State secondary = new State();
            final State selected = useFailover ? secondary : primary;
            final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
            final AtomicReference<MailSender> escaped = new AtomicReference<>();
            final AtomicInteger closes = new AtomicInteger();
            selected.onClose = () -> {
                closes.incrementAndGet();
                if (!usePooling) {
                    // The sender must already be unusable when same-thread direct cleanup begins.
                    assertThatThrownBy(() -> escaped.get().sendMail(null)).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("callback has ended");
                }
            };
            final ExecutorService worker = Executors.newSingleThreadExecutor();
            try (Mailer b = builder(secondary).buildMailer()) {
                final MailerFromSessionBuilderImpl initiating = builder(primary).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build());
                if (useFailover) {
                    initiating.withFailoverMailer(b, FailoverPolicy.builder().build());
                }
                try (Mailer a = initiating.buildMailer()) {
                    a.withOpenConnection(sender -> {
                        escaped.set(sender);
                        worker.submit(() -> {
                            assertThatThrownBy(() -> sender.sendMail(null)).isInstanceOf(IllegalStateException.class)
                                    .hasMessageContaining("thread running its callback");
                            assertThatThrownBy(() -> sender.sendMail(null)).isInstanceOf(IllegalStateException.class)
                                    .hasMessageContaining("thread running its callback");
                        }).get(5, TimeUnit.SECONDS);
                        assertThat(outcomes).isEmpty();
                        assertThat(selected.messages).isEmpty();
                        final var receipt = sender.sendMail(email());
                        assertThat(outcomes).hasSize(1);
                        assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(receipt);
                    });
                    // A healthy pooled lease is returned for reuse, not physically closed at scope exit.
                    assertThat(closes).hasValue(usePooling ? 0 : 1);
                    assertThatThrownBy(() -> escaped.get().sendMail(null)).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("callback has ended");
                    assertThatThrownBy(() -> escaped.get().sendMail(null)).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("callback has ended");
                    assertThat(outcomes).hasSize(1);
                    assertThat(selected.messages).hasSize(1);
                }
            } finally {
                worker.shutdownNow();
                assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void throwingOpenConnectionCallbackEndsSenderScopeAndPreservesItsException(final boolean useFailover, final boolean usePooling) throws Exception {
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(usePooling);
            final State primary = new State();
            if (useFailover) {
                primary.connectFailure = new MessagingException("refused", new ConnectException());
            }
            final State secondary = new State();
            final AtomicReference<MailSender> escaped = new AtomicReference<>();
            final AtomicInteger closes = new AtomicInteger();
            (useFailover ? secondary : primary).onClose = () -> {
                closes.incrementAndGet();
                if (!usePooling) {
                    assertThatThrownBy(() -> escaped.get().sendMail(null)).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("callback has ended");
                }
            };
            final IOException original = new IOException("application callback failed");
            try (Mailer b = builder(secondary).buildMailer()) {
                final MailerFromSessionBuilderImpl initiating = builder(primary);
                if (useFailover) {
                    initiating.withFailoverMailer(b, FailoverPolicy.builder().build());
                }
                try (Mailer a = initiating.buildMailer()) {
                    assertThatThrownBy(() -> a.withOpenConnection(sender -> {
                        escaped.set(sender);
                        throw original;
                    })).isSameAs(original);
                    assertThatThrownBy(() -> escaped.get().sendMail(null)).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("callback has ended");
                    assertThat(closes).hasValue(usePooling ? 0 : 1);
                    assertThat(primary.messages).isEmpty();
                    assertThat(secondary.messages).isEmpty();
                }
            }
        }
    }

    @Test
    void nestedRelationshipsKeepOneObserverAndClosingInitiatorLeavesBorrowedMailersUsable() throws Exception {
        final State first = new State();
        final State second = new State();
        first.connectFailure = new MessagingException("refused", new ConnectException());
        second.connectFailure = new MessagingException("refused", new ConnectException());
        final State third = new State();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final AtomicInteger delegatedObservers = new AtomicInteger();
        try (Mailer c = builder(third).withSendHandlers(MailSendHandlers.builder().withResultObserver(ignored -> delegatedObservers.incrementAndGet()).build()).buildMailer();
             Mailer b = builder(second).withFailoverMailer(c, FailoverPolicy.builder().build()).buildMailer()) {
            try (Mailer a = builder(first).withFailoverMailer(b, FailoverPolicy.builder().build()).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer()) {
                a.sync().sendMail(email());
            }
            c.sync().sendMail(email());
            assertThat(third.messages).hasSize(2);
            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getConnectionHistory()).hasSize(3);
            assertThat(delegatedObservers).hasValue(1);
        }
    }

    @Test
    void unknownSetupFailuresStopAndCertificatePermissionIsIndependentOfAuthentication() throws Exception {
        final State primary = new State();
        final State secondary = new State();
        primary.connectFailure = new MessagingException("unclassified provider failure");
        try (Mailer b = builder(secondary).buildMailer();
             Mailer a = builder(primary).withFailoverMailer(b, FailoverPolicy.builder().withFailoverOnAuthenticationFailure(true)
                     .withFailoverOnCertificateVerificationFailure(true).build()).buildMailer()) {
            assertThatThrownBy(() -> a.sync().sendMail(email())).isInstanceOf(ConnectionSetupFailure.class);
            assertThat(secondary.connections).hasValue(0);
        }
        primary.connectFailure = new MessagingException("certificate", new CertificateException());
        try (Mailer b = builder(secondary).buildMailer();
             Mailer strict = builder(primary)
                     .withFailoverMailer(b, FailoverPolicy.builder()
                             .withFailoverOnAuthenticationFailure(true)
                             .build())
                     .buildMailer();
             Mailer permitted = builder(primary)
                     .withFailoverMailer(b, FailoverPolicy.builder()
                             .withFailoverOnCertificateVerificationFailure(true)
                             .build())
                     .buildMailer()) {
            assertThatThrownBy(() -> strict.sync().sendMail(email())).isInstanceOf(ConnectionSetupFailure.class);
            assertThat(secondary.messages).isEmpty();
            permitted.sync().sendMail(email());
            assertThat(secondary.messages).hasSize(1);
        }
    }

    private Email template(final String name) {
        return mail.emailBuilder().startingBlank().from(name + "@example.org").withHeader("X-Relay", name)
                .withRecipients(mail.recipientBuilder().withAddress(name + "-maintainer@example.org").withType(RecipientType.BCC).build())
                .buildEmail();
    }

    private Email email() {
        // Routing tests must not wait for host DNS while Jakarta Mail generates a Message-ID.
        return mail.emailBuilder().startingBlank().fixingMessageId("<failover-test@example.test>").from("sender@example.org")
                .withRecipients(mail.recipientBuilder().withAddress("customer@example.org").withType(RecipientType.TO).build())
                .withPlainText("test").buildEmail();
    }

    private MailerFromSessionBuilderImpl builder(final State state) throws Exception {
        return builder(state, new FactoryMailerRuntime(mail.getConfig()));
    }

    private MailerFromSessionBuilderImpl builder(final State state, final FactoryMailerRuntime runtime) throws Exception {
        return builder(state, runtime, mail.getConfig());
    }

    private MailerFromSessionBuilderImpl builder(final State state, final FactoryMailerRuntime runtime, final SimpleJavaMailConfig config) throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.host", "localhost");
        properties.setProperty("mail.smtp.port", "25");
        properties.put("test.state", state);
        final Session session = Session.getInstance(properties);
        session.setProvider(new Provider(Provider.Type.TRANSPORT, "smtp", ControlledTransport.class.getName(), "test", "1"));
        return new MailerFromSessionBuilderImpl(config, runtime).usingSession(session).withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1).build());
    }

    private static final class State {
        final AtomicInteger connections = new AtomicInteger();
        final List<Message> messages = new CopyOnWriteArrayList<>();
        volatile MessagingException connectFailure;
        MessagingException sendFailure;
        MessagingException closeFailure;
        boolean serialize;
        Runnable onConnect = () -> { };
        Runnable onSend = () -> { };
        Runnable onClose = () -> { };
    }

    public static final class ControlledTransport extends Transport {
        private final State state;

        public ControlledTransport(final Session session, final URLName name) {
            super(session, name);
            state = (State) session.getProperties().get("test.state");
        }

        @Override
        protected boolean protocolConnect(final String host, final int port, final String user, final String password) throws MessagingException {
            state.connections.incrementAndGet();
            state.onConnect.run();
            if (state.connectFailure != null) {
                throw state.connectFailure;
            }
            return true;
        }

        @Override
        public synchronized void close() throws MessagingException {
            super.close();
            state.onClose.run();
            if (state.closeFailure != null) {
                throw state.closeFailure;
            }
        }

        @Override
        public void sendMessage(final Message message, final Address[] recipients) throws MessagingException {
            state.messages.add(message);
            if (state.serialize) {
                try {
                    message.writeTo(new ByteArrayOutputStream());
                } catch (IOException failure) {
                    throw new MessagingException("Fixture serialization failed", failure);
                }
            }
            state.onSend.run();
            if (state.sendFailure != null) {
                throw state.sendFailure;
            }
        }
    }
}
