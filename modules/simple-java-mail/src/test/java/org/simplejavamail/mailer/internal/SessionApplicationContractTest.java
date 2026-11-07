package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.Provider;
import jakarta.mail.Message;
import jakarta.mail.Address;
import jakarta.mail.URLName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.api.mailer.config.SendingLimitsConfig;
import org.simplejavamail.api.mailer.config.SessionDebugConfig;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.BatchModule;
import org.simplejavamail.internal.modules.AuthenticatedSocksModule;

import java.time.Duration;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROVIDER_PROPERTY;

class SessionApplicationContractTest {
    @Test
    void establishedGroupConflictPrecedesCallerSessionApplicationAndWorkerAllocation() throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        final SendingLimitsConfig limits = limits("account", 1);
        try (Mailer ignored = mail.mailerBuilder().withSMTPServer("smtp.example.test", 25)
                .withSendingLimitsConfig(limits).buildMailer()) {
            // Establish the factory-local policy independently of the later build.
        }
        final RecordingProperties properties = oauthProperties();
        final Session session = Session.getInstance(properties);
        final AtomicInteger tokenCalls = new AtomicInteger();
        final Properties before = (Properties) properties.clone();
        properties.record = true;
        try (MockedConstruction<MailSendExecutor> executors = mockConstruction(MailSendExecutor.class)) {
            assertThatThrownBy(() -> mail.mailerBuilder(session)
                    .withOAuth2AccessTokenProvider(() -> { tokenCalls.incrementAndGet(); return "token"; })
                    .withProperty("application.tag", "new")
                    .withDebugConfig(SessionDebugConfig.builder().withEnabled(true).build())
                    .withSendingLimitsConfig(limits("account", 2)).buildMailer())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("same message limit");
            assertThat(executors.constructed()).isEmpty();
        }
        assertThat(properties.configurationWrites).isZero();
        assertThat(properties).isEqualTo(before);
        assertThat(session.getDebug()).isFalse();
        assertThat(tokenCalls).hasValue(0);
    }

    @Test
    void latePoolFailureRemovesNewCallbackAndPermitsCorrectedRetry() throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        final RecordingProperties properties = oauthProperties();
        final Session session = Session.getInstance(properties);
        final AtomicInteger tokenCalls = new AtomicInteger();
        final OAuth2AccessTokenProvider first = () -> { tokenCalls.incrementAndGet(); return "first"; };
        final IllegalStateException failure = new IllegalStateException("pool setup failed");
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            final BatchModule batch = mock(BatchModule.class);
            modules.when(ModuleLoader::loadBatchModule).thenReturn(batch);
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(true);
            doThrow(failure).when(batch).registerToCluster(any(), any(), any(), any(), any(), any());
            when(batch.shutdownConnectionPools(session)).thenReturn(CompletableFuture.completedFuture(null));
            assertThatThrownBy(() -> mail.mailerBuilder(session).withOAuth2AccessTokenProvider(first).buildMailer())
                    .isSameAs(failure);
        }
        assertThat(properties.containsKey(OAUTH2_TOKEN_PROVIDER_PROPERTY)).isFalse();
        assertThat(tokenCalls).hasValue(0);
        final OAuth2AccessTokenProvider replacement = () -> "replacement";
        try (Mailer ignored = mail.mailerBuilder(session).withOAuth2AccessTokenProvider(replacement).buildMailer()) {
            assertThat(properties.get(OAUTH2_TOKEN_PROVIDER_PROPERTY)).isSameAs(replacement);
        }
    }

    @Test
    void changedCapturedSessionBaselineIsRejectedBeforeApplyingChoices() {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.timeout", "100");
        final Session session = Session.getInstance(properties);
        final MailerFromSessionBuilderImpl builder = new MailerFromSessionBuilderImpl(ConfigLoader.builder().load()) {
            @Override MailerImpl constructMailer(final ValidatedMailerPlan plan) {
                properties.setProperty("mail.smtp.timeout", "200");
                return super.constructMailer(plan);
            }
        }.usingSession(session).withProperty("application.tag", "must not be applied");
        assertThatThrownBy(() -> { try (Mailer ignored = builder.buildMailer()) { } }).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("changed").hasMessageContaining("Session");
        assertThat(properties.getProperty("mail.smtp.timeout")).isEqualTo("200");
        assertThat(properties.containsKey("application.tag")).isFalse();
        assertThat(properties.containsKey("org.simplejavamail.internal.mailerOwner")).isFalse();
    }

    @Test
    void failedParentDoesNotEstablishItsPrivatelyConstructedChildsRules() throws Exception {
        final var config = ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.failover.mailer", "backup",
                "simplejavamail.mailers.backup.smtp.host", "backup.example.test",
                "simplejavamail.mailers.backup.smtp.ratelimit.group", "new-child",
                "simplejavamail.mailers.backup.smtp.ratelimit.messages.limit", 1,
                "simplejavamail.mailers.backup.smtp.ratelimit.messages.period", "PT1M")).load();
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(config);
        final IllegalStateException failure = new IllegalStateException("parent application failed");
        final RecordingProperties properties = new RecordingProperties();
        properties.setProperty("mail.transport.protocol", "smtp");
        final Session session = Session.getInstance(properties);
        properties.failure = failure;
        assertThatThrownBy(() -> mail.mailerBuilder(session).withProperty("application.throw", "requested").buildMailer())
                .isSameAs(failure);
        properties.failure = null;
        try (Mailer ignored = mail.mailerBuilder().clearFailoverMailer().withSMTPServer("smtp.example.test", 25)
                .withSendingLimitsConfig(limits("new-child", 2)).buildMailer()) {
            // No successful construction established the first child's policy.
        }
    }

    @Test
    void compatibleConcurrentSuccessSurvivesTheWholeRoutesAbort() throws Exception {
        final var config = ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.failover.mailer", "backup",
                "simplejavamail.mailers.backup.smtp.host", "backup.example.test",
                "simplejavamail.mailers.backup.smtp.ratelimit.group", "shared-child",
                "simplejavamail.mailers.backup.smtp.ratelimit.messages.limit", 1,
                "simplejavamail.mailers.backup.smtp.ratelimit.messages.period", "PT1M")).load();
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(config);
        final RecordingProperties properties = new RecordingProperties();
        properties.setProperty("mail.transport.protocol", "smtp");
        final Session session = Session.getInstance(properties);
        final IllegalStateException failure = new IllegalStateException("parent setup failed");
        final CountDownLatch applying = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        properties.failure = failure;
        properties.beforeFailure = () -> { applying.countDown(); await(finish); };
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            final Future<Throwable> failed = worker.submit(() -> catchThrowable(() -> mail.mailerBuilder(session)
                    .withProperty("application.throw", "requested").buildMailer()));
            assertThat(applying.await(5, SECONDS)).isTrue();
            try (Mailer compatible = mail.mailerBuilder().clearFailoverMailer().withSMTPServer("smtp.example.test", 25)
                    .withSendingLimitsConfig(limits("shared-child", 1)).buildMailer()) {
                finish.countDown();
                assertThat(failed.get(5, SECONDS)).isSameAs(failure);
                assertThatThrownBy(() -> mail.mailerBuilder().clearFailoverMailer().withSMTPServer("smtp.example.test", 25)
                        .withSendingLimitsConfig(limits("shared-child", 2)).buildMailer()).hasMessageContaining("same message limit");
            }
        } finally {
            finish.countDown();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    private static void await(final CountDownLatch latch) {
        try { assertThat(latch.await(5, SECONDS)).isTrue(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    @Test
    void lateFailureRestoresBorrowedValuesRemovedPropertiesProxyDebugAndConverterExactly() {
        final Properties defaults = new Properties();
        defaults.setProperty("application.hidden", "inherited");
        final Properties properties = new Properties(defaults);
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.auth.mechanisms", "XOAUTH2");
        properties.setProperty("mail.smtp.socks.host", "original-proxy.example.test");
        properties.setProperty("mail.smtp.socks.port", "1080");
        properties.setProperty("mail.smtp.ssl.socketFactory.class", "original.Factory");
        properties.setProperty("mail.smtp.ssl.trust", "original.example.test");
        final OpaqueValue oldValue = new OpaqueValue();
        properties.put("application.hidden", oldValue);
        final AtomicInteger tokenCalls = new AtomicInteger();
        final OAuth2AccessTokenProvider provider = () -> { tokenCalls.incrementAndGet(); return "never fetched"; };
        properties.put(OAUTH2_TOKEN_PROVIDER_PROPERTY, provider);
        final Session session = Session.getInstance(properties);
        final PrintStream originalOutput = new PrintStream(new ByteArrayOutputStream());
        session.setDebugOut(originalOutput);
        final SessionBasedEmailToMimeMessageConverter originalConverter = new SessionBasedEmailToMimeMessageConverter(
                session, mock(OperationalConfig.class), mock(EmailGovernance.class));
        properties.put(SessionBasedEmailToMimeMessageConverter.MIMEMESSAGE_CONVERTER_KEY, originalConverter);
        final IllegalStateException failure = new IllegalStateException("late pool initialization failed");
        final Properties replacements = new Properties();
        replacements.put("application.hidden", new OpaqueValue());
        replacements.setProperty("application.new", "removed after failure");
        final AnonymousSocks5Server bridge = mock(AnonymousSocks5Server.class);
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            final BatchModule batch = mock(BatchModule.class);
            final AuthenticatedSocksModule socks = mock(AuthenticatedSocksModule.class);
            modules.when(ModuleLoader::loadBatchModule).thenReturn(batch);
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(true);
            modules.when(ModuleLoader::loadAuthenticatedSocksModule).thenReturn(socks);
            when(socks.createAnonymousSocks5Server(any())).thenReturn(bridge);
            doThrow(failure).when(batch).registerToCluster(any(), any(), any(), any(), any(), any());
            when(batch.shutdownConnectionPools(session)).thenReturn(CompletableFuture.completedFuture(null));
            assertThatThrownBy(() -> SimpleJavaMail.withConfig(ConfigLoader.builder().load()).mailerBuilder(session)
                    .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                    .withProperties(replacements).withOAuth2AccessTokenProvider(provider)
                    .withTlsConfig(TlsConfig.builder().trustingAllHosts(false).clearTrustedSSLHosts().build())
                    .withProxyConfig(SocksProxyConfig.builder().withHost("new-proxy.example.test").withPort(2080)
                            .withUsername("user").withPassword("secret").build())
                    .withDebugConfig(SessionDebugConfig.builder().withEnabled(true)
                            .withPrinter(new PrintStream(new ByteArrayOutputStream())).build()).buildMailer())
                    .isSameAs(failure);
        }
        assertThat(properties.get("application.hidden")).isSameAs(oldValue);
        assertThat(properties.getProperty("application.hidden")).isEqualTo("inherited");
        assertThat(properties.containsKey("application.new")).isFalse();
        assertThat(properties.getProperty("mail.smtp.ssl.socketFactory.class")).isEqualTo("original.Factory");
        assertThat(properties.getProperty("mail.smtp.ssl.trust")).isEqualTo("original.example.test");
        assertThat(properties.getProperty("mail.smtp.socks.host")).isEqualTo("original-proxy.example.test");
        assertThat(properties.getProperty("mail.smtp.socks.port")).isEqualTo("1080");
        assertThat(properties.get(OAUTH2_TOKEN_PROVIDER_PROPERTY)).isSameAs(provider);
        assertThat(properties.get(SessionBasedEmailToMimeMessageConverter.MIMEMESSAGE_CONVERTER_KEY)).isSameAs(originalConverter);
        assertThat(session.getDebug()).isFalse();
        assertThat(session.getDebugOut()).isSameAs(originalOutput);
        assertThat(tokenCalls).hasValue(0);
        verify(bridge).stop();
    }

    @Test
    void unchangedOpaqueBaselineNeedsNoApplicationObjectMethods() throws Exception {
        final Properties properties = new Properties();
        final OpaqueValue borrowed = new OpaqueValue();
        properties.put("application.borrowed", borrowed);
        final Session session = Session.getInstance(properties);
        try (Mailer ignored = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).mailerBuilder(session).buildMailer()) {
            assertThat(properties.get("application.borrowed")).isSameAs(borrowed);
        }
    }

    @Test
    void unresolvedPoolCleanupKeepsItsProvisionalGroupAndSessionReserved() {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        final Properties properties = new Properties();
        final Session session = Session.getInstance(properties);
        final IllegalStateException failure = new IllegalStateException("pool initialization failed");
        final IllegalStateException cleanup = new IllegalStateException("pool retirement failed");
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            final BatchModule batch = mock(BatchModule.class);
            modules.when(ModuleLoader::loadBatchModule).thenReturn(batch);
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(true);
            doThrow(failure).when(batch).registerToCluster(any(), any(), any(), any(), any(), any());
            when(batch.shutdownConnectionPools(session)).thenReturn(CompletableFuture.failedFuture(cleanup));
            assertThatThrownBy(() -> mail.mailerBuilder(session).withSendingLimitsConfig(limits("pending", 1)).buildMailer())
                    .isSameAs(failure);
        }
        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(failure.getSuppressed()[0]).hasCause(cleanup);
        assertThat(properties.containsKey("org.simplejavamail.internal.mailerOwner")).isTrue();
        assertThatThrownBy(() -> mail.mailerBuilder().withSMTPServer("smtp.example.test", 25)
                .withSendingLimitsConfig(limits("pending", 2)).buildMailer()).hasMessageContaining("same message limit");
    }

    @Test
    void ownerReleaseFailureIsSuppressedAndDoesNotStopOtherRouteCleanup() throws Exception {
        final var config = ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.failover.mailer", "backup",
                "simplejavamail.mailers.backup.smtp.host", "backup.example.test",
                "simplejavamail.mailers.backup.smtp.ratelimit.group", "child-release",
                "simplejavamail.mailers.backup.smtp.ratelimit.messages.limit", 1,
                "simplejavamail.mailers.backup.smtp.ratelimit.messages.period", "PT1M")).load();
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(config);
        final RecordingProperties properties = new RecordingProperties();
        properties.setProperty("mail.transport.protocol", "smtp");
        final Session session = Session.getInstance(properties);
        final IllegalStateException failure = new IllegalStateException("parent pool initialization failed");
        final IllegalStateException cleanup = new IllegalStateException("owner release failed");
        properties.ownerReleaseFailure = cleanup;
        final AtomicReference<Session> child = new AtomicReference<>();
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            final BatchModule batch = mock(BatchModule.class);
            modules.when(ModuleLoader::loadBatchModule).thenReturn(batch);
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(true);
            doAnswer(invocation -> {
                final Session selected = invocation.getArgument(2);
                if (selected == session) { throw failure; }
                child.set(selected);
                return null;
            }).when(batch).registerToCluster(any(), any(), any(), any(), any(), any());
            when(batch.shutdownConnectionPools(any())).thenReturn(CompletableFuture.completedFuture(null));
            assertThatThrownBy(() -> mail.mailerBuilder(session).buildMailer()).isSameAs(failure);
        }
        assertThat(failure.getSuppressed()).containsExactly(cleanup);
        assertThat(properties.containsKey("org.simplejavamail.internal.mailerOwner")).isTrue();
        assertThat(child.get()).isNotNull();
        assertThat(child.get().getProperties().containsKey("org.simplejavamail.internal.mailerOwner")).isFalse();
        try (Mailer ignored = mail.mailerBuilder().clearFailoverMailer().withSMTPServer("smtp.example.test", 25)
                .withSendingLimitsConfig(limits("child-release", 2)).buildMailer()) {
            // The failed parent's cleanup exception cannot pin its retired child's provisional policy.
        }
    }

    @Test
    void failedPropertyApplicationRestoresOnlyAttemptedMutationsIncludingTheThrowingPut() {
        final PartialApplicationProperties properties = new PartialApplicationProperties(
                Set.of(SessionBasedEmailToMimeMessageConverter.MIMEMESSAGE_CONVERTER_KEY));
        properties.setProperty("application.first", "before");
        final Session session = Session.getInstance(properties);
        final IllegalStateException failure = new IllegalStateException("first put changed state then failed");
        properties.firstFailure = failure;
        properties.armed = true;
        assertThatThrownBy(() -> SimpleJavaMail.withConfig(ConfigLoader.builder().load()).mailerBuilder(session)
                .withProperty("application.first", "after").buildMailer()).isSameAs(failure);
        assertThat(properties.getProperty("application.first")).isEqualTo("before");
        assertThat(properties.untouchedWrites).isZero();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(properties.containsKey("org.simplejavamail.internal.mailerOwner")).isFalse();
    }

    @Test
    void failedDebugOutputApplicationRestoresOutputWithoutTouchingUnattemptedProperties() {
        final PartialApplicationProperties properties = new PartialApplicationProperties(
                Set.of("application.future", SessionBasedEmailToMimeMessageConverter.MIMEMESSAGE_CONVERTER_KEY));
        properties.setProperty("application.future", "before");
        final Session original = Session.getInstance(properties);
        final PrintStream oldOutput = new PrintStream(new ByteArrayOutputStream());
        final PrintStream replacementOutput = new PrintStream(new ByteArrayOutputStream());
        original.setDebugOut(oldOutput);
        final Session session = spy(original);
        final IllegalStateException failure = new IllegalStateException("debug output changed then failed");
        doAnswer(invocation -> { invocation.callRealMethod(); throw failure; }).when(session).setDebugOut(replacementOutput);
        properties.armed = true;
        assertThatThrownBy(() -> SimpleJavaMail.withConfig(ConfigLoader.builder().load()).mailerBuilder(session)
                .withProperty("application.future", "after")
                .withDebugConfig(SessionDebugConfig.builder().withPrinter(replacementOutput).build()).buildMailer()).isSameAs(failure);
        assertThat(session.getDebugOut()).isSameAs(oldOutput);
        assertThat(properties.getProperty("application.future")).isEqualTo("before");
        assertThat(properties.untouchedWrites).isZero();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(properties.containsKey("org.simplejavamail.internal.mailerOwner")).isFalse();
    }

    private static final class PartialApplicationProperties extends Properties {
        private final Set<String> untouched;
        private boolean armed;
        private RuntimeException firstFailure;
        private int untouchedWrites;

        private PartialApplicationProperties(final Set<String> untouched) { this.untouched = untouched; }

        private void record(final Object key) {
            if (armed && untouched.contains(key)) {
                untouchedWrites++;
                throw new IllegalStateException("An unattempted setting was written during cleanup");
            }
        }

        @Override public synchronized Object put(final Object key, final Object value) {
            record(key);
            final Object before = super.put(key, value);
            if (armed && "application.first".equals(key) && "after".equals(value)) { throw firstFailure; }
            return before;
        }

        @Override public synchronized Object remove(final Object key) { record(key); return super.remove(key); }
    }

    enum CapturedFact { DIRECT_STRING, INHERITED_STRING, OPAQUE_IDENTITY, DEBUG_FLAG, DEBUG_OUTPUT }

    @ParameterizedTest
    @EnumSource(CapturedFact.class)
    void everyCapturedFactRejectsStaleApplication(final CapturedFact fact) {
        final Properties defaults = new Properties();
        defaults.setProperty("application.inherited", "before");
        final Properties properties = new Properties(defaults);
        properties.setProperty("application.direct", "before");
        properties.put("application.opaque", new OpaqueValue());
        final Session session = Session.getInstance(properties);
        session.setDebugOut(new PrintStream(new ByteArrayOutputStream()));
        final MailerFromSessionBuilderImpl builder = new MailerFromSessionBuilderImpl(ConfigLoader.builder().load()) {
            @Override MailerImpl constructMailer(final ValidatedMailerPlan plan) {
                switch (fact) {
                    case DIRECT_STRING: properties.setProperty("application.direct", "after"); break;
                    case INHERITED_STRING: defaults.setProperty("application.inherited", "after"); break;
                    case OPAQUE_IDENTITY: properties.put("application.opaque", new OpaqueValue()); break;
                    case DEBUG_FLAG: session.setDebug(true); break;
                    case DEBUG_OUTPUT: session.setDebugOut(new PrintStream(new ByteArrayOutputStream())); break;
                    default: throw new AssertionError(fact);
                }
                return super.constructMailer(plan);
            }
        }.usingSession(session).withProperty("application.requested", "must not be applied");
        assertThatThrownBy(() -> { try (Mailer ignored = builder.buildMailer()) { } })
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Session changed");
        assertThat(properties.containsKey("application.requested")).isFalse();
        assertThat(properties.containsKey("org.simplejavamail.internal.mailerOwner")).isFalse();
    }

    @Test
    void equalStringContentDoesNotMakeTheCapturedBaselineStale() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("application.value", "same content");
        final Session session = Session.getInstance(properties);
        final MailerFromSessionBuilderImpl builder = new MailerFromSessionBuilderImpl(ConfigLoader.builder().load()) {
            @Override MailerImpl constructMailer(final ValidatedMailerPlan plan) {
                properties.setProperty("application.value", new String("same content"));
                return super.constructMailer(plan);
            }
        }.usingSession(session);
        try (Mailer ignored = builder.buildMailer()) {
            assertThat(properties.getProperty("application.value")).isEqualTo("same content");
        }
    }

    @Test
    void mergedValidationCreatesNoPrivateSessionOrTransportAndFetchesNoToken() throws Exception {
        final RecordingProperties properties = oauthProperties();
        final Session session = Session.getInstance(properties);
        session.setProvider(new Provider(Provider.Type.TRANSPORT, "smtp", UnexpectedTransport.class.getName(), "test", "1"));
        final AtomicInteger tokens = new AtomicInteger();
        final IllegalStateException stop = new IllegalStateException("validated plan reached construction");
        final MailerFromSessionBuilderImpl builder = new MailerFromSessionBuilderImpl(ConfigLoader.builder().load()) {
            @Override MailerImpl constructMailer(final ValidatedMailerPlan plan) { throw stop; }
        }.usingSession(session).withOAuth2AccessTokenProvider(() -> { tokens.incrementAndGet(); return "unused"; });
        properties.record = true;
        UnexpectedTransport.creations.set(0);
        try (MockedStatic<Session> sessions = mockStatic(Session.class, invocation -> {
            throw new AssertionError("Validation must not create a temporary Session");
        })) {
            assertThatThrownBy(builder::buildMailer).isSameAs(stop);
        }
        assertThat(properties.configurationWrites).isZero();
        assertThat(tokens).hasValue(0);
        assertThat(UnexpectedTransport.creations).hasValue(0);
    }

    public static final class UnexpectedTransport extends Transport {
        private static final AtomicInteger creations = new AtomicInteger();
        public UnexpectedTransport(final Session session, final URLName name) {
            super(session, name);
            creations.incrementAndGet();
            throw new AssertionError("Validation must not construct a Transport");
        }
        @Override public void sendMessage(final Message message, final Address[] recipients) {
            throw new AssertionError("Validation must not send mail");
        }
    }

    private static final class OpaqueValue {
        @Override public boolean equals(final Object other) { throw new AssertionError("Opaque equality must not run"); }
        @Override public int hashCode() { throw new AssertionError("Opaque hashing must not run"); }
        @Override public String toString() { throw new AssertionError("Opaque rendering must not run"); }
    }

    private static SendingLimitsConfig limits(final String group, final int count) {
        return SendingLimitsConfig.builder().withRateLimitGroup(group).withMessageRateLimit(count, Duration.ofMinutes(1)).build();
    }

    private static RecordingProperties oauthProperties() {
        final RecordingProperties properties = new RecordingProperties();
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.host", "smtp.example.test");
        properties.setProperty("mail.smtp.user", "sender@example.test");
        properties.setProperty("mail.smtp.auth.mechanisms", "XOAUTH2");
        return properties;
    }

    private static final class RecordingProperties extends Properties {
        private boolean record;
        private int configurationWrites;
        private RuntimeException failure;
        private RuntimeException ownerReleaseFailure;
        private Runnable beforeFailure = () -> { };

        @Override public synchronized Object put(final Object key, final Object value) {
            if (record && !"org.simplejavamail.internal.mailerOwner".equals(key)) { configurationWrites++; }
            final Object previous = super.put(key, value);
            if ("application.throw".equals(key) && failure != null) { beforeFailure.run(); throw failure; }
            return previous;
        }

        @Override public synchronized void putAll(final Map<?, ?> entries) {
            entries.forEach(this::put);
        }

        @Override public synchronized Object remove(final Object key) {
            if ("org.simplejavamail.internal.mailerOwner".equals(key) && ownerReleaseFailure != null) { throw ownerReleaseFailure; }
            return super.remove(key);
        }
    }
}
