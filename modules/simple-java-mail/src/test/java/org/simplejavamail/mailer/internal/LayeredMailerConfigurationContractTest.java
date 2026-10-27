package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockito.MockedStatic;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.mailer.internal.ConfigurationAdoptionCatalogue.Owner;
import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.SendingLimitsConfig;
import org.simplejavamail.api.mailer.config.SessionDebugConfig;
import org.simplejavamail.api.mailer.config.ValidationConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.OpenTelemetryModule;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge;

import java.io.OutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.mailer.internal.ConfigurationAdoptionCatalogue.assertApiCategory;
import static org.simplejavamail.mailer.internal.ConfigurationAdoptionCatalogue.assertValueBuilderCategory;
import static org.simplejavamail.mailer.internal.ConfigurationAdoptionCatalogue.assertPropertyCategory;
import static org.simplejavamail.mailer.internal.ConfigurationAdoptionCatalogue.Owner.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

/** Independent source/local-choice/lock tables exercised through public Mailer builders. */
class LayeredMailerConfigurationContractTest {
    private static final MailSendTelemetry TELEMETRY = new MailSendTelemetry() { };
    private static final PrintStream DEBUG = new PrintStream(OutputStream.nullOutputStream());
    private MockedStatic<ModuleLoader> telemetryModule;

    @BeforeEach
    void supplyTheOptionalTelemetryRuntimeThroughItsCoreContract() {
        final OpenTelemetryModule module = mock(OpenTelemetryModule.class);
        when(module.bind(any(), anyBoolean())).thenReturn(mock(MailSendTelemetryBridge.Runtime.class));
        telemetryModule = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS);
        telemetryModule.when(ModuleLoader::loadOpenTelemetryModule).thenReturn(module);
    }

    @AfterEach
    void retireTheOptionalRuntimeFixture() {
        telemetryModule.close();
    }

    enum Member {
        POOL_CORE("defaults.connectionpool.coresize", 0), POOL_MAX("defaults.connectionpool.maxsize", 4),
        POOL_CLAIM("defaults.connectionpool.claimtimeout.millis", Duration.ofMillis(Integer.MAX_VALUE)),
        POOL_IDLE("defaults.connectionpool.expireafter.millis", Duration.ofSeconds(5)),
        POOL_AGE("defaults.connectionpool.expireaftercreation.millis", null),
        BALANCING("defaults.connectionpool.loadbalancing.strategy", LoadBalancingStrategy.ROUND_ROBIN),
        WORKERS("defaults.poolsize", 10), KEEP_ALIVE("defaults.poolsize.keepalivetime", Duration.ofMillis(1)),
        QUEUE_CAPACITY("defaults.async.queue.capacity", -1), QUEUE_WAIT("defaults.async.queue.waittimeoutmillis", Duration.ofSeconds(1)),
        SEND_DEADLINE("defaults.mailsend.timeout", null), DEBUG_ENABLED("javaxmail.debug", false),
        TELEMETRY_ENABLED("telemetry.enabled", false), TELEMETRY_ENDPOINT("telemetry.smtp.endpointattributes", false),
        VALIDATION_WARNINGS("disable.all.clientvalidation", false), LOGGING_ONLY("transport.mode.logging.only", false),
        LEGACY_CONTENT("smtp.legacycontentsupport", false), BURSTS("smtp.ratelimit.allowbursts", true),
        RETRY_WAITING("defaults.mailsend.retry.waitingcapacity", 100),
        RETRY_MESSAGE_SIZE("defaults.mailsend.retry.maximummessagesize", 32L * 1024 * 1024),
        RETRY_MEMORY("defaults.mailsend.retry.memorylimit", 128L * 1024 * 1024);

        final String property;
        final Object fallback;
        Member(final String property, final Object fallback) { this.property = property; this.fallback = fallback; }

        Object value(final int marker) {
            switch (this) {
                case POOL_CORE: return marker;
                case POOL_MAX: return marker + 4;
                case WORKERS: case QUEUE_CAPACITY: case RETRY_WAITING: return marker + 1;
                case POOL_CLAIM: case POOL_IDLE: case POOL_AGE: case KEEP_ALIVE: case QUEUE_WAIT: case SEND_DEADLINE:
                    return Duration.ofMillis(marker * 100L);
                case BALANCING: return marker == 1 ? LoadBalancingStrategy.RANDOM_ACCESS : LoadBalancingStrategy.ROUND_ROBIN;
                case RETRY_MESSAGE_SIZE: return marker * 1024L;
                case RETRY_MEMORY: return marker * 64L * 1024 * 1024;
                default: return marker == 1;
            }
        }

        void verifyCategoryBinding() {
            final Owner category;
            switch (this) {
                case POOL_CORE: case POOL_MAX: case POOL_CLAIM: case POOL_IDLE: case POOL_AGE: case BALANCING:
                    category = MAILER_POOL;
                    verifyGroup(category, ConnectionPoolConfig.Builder.class, "withConnectionPoolConfig", "useConfiguredConnectionPoolConfig");
                    break;
                case WORKERS: case KEEP_ALIVE: case QUEUE_CAPACITY: case QUEUE_WAIT:
                    category = MAILER_ASYNC;
                    verifyGroup(category, AsyncExecutionConfig.Builder.class, "withAsyncExecutionConfig", "useConfiguredAsyncExecutionConfig");
                    break;
                case SEND_DEADLINE:
                    category = MAILER_DEADLINE;
                    assertApiCategory(category, MailerGenericBuilder.class, "withMailSendTimeout", "resetMailSendTimeout");
                    break;
                case DEBUG_ENABLED:
                    category = MAILER_DEBUG;
                    verifyGroup(category, SessionDebugConfig.Builder.class, "withDebugConfig", "useConfiguredDebugConfig");
                    break;
                case TELEMETRY_ENABLED: case TELEMETRY_ENDPOINT:
                    category = MAILER_TELEMETRY;
                    verifyGroup(category, MailSendTelemetryConfig.Builder.class, "withTelemetryConfig", "useConfiguredTelemetryConfig");
                    break;
                case VALIDATION_WARNINGS:
                    category = MAILER_VALIDATION;
                    verifyGroup(category, ValidationConfig.Builder.class, "withValidationConfig", "useConfiguredValidationConfig");
                    break;
                case LOGGING_ONLY:
                    category = MAILER_LOGGING;
                    assertApiCategory(category, MailerGenericBuilder.class, "withTransportModeLoggingOnly", "resetTransportModeLoggingOnly");
                    break;
                case LEGACY_CONTENT:
                    category = MAILER_CONTENT_COMPATIBILITY;
                    assertApiCategory(category, ConnectionConfig.Builder.class, "withLegacySmtpContentSupport",
                            "resetLegacySmtpContentSupport", "useConfiguredLegacySmtpContentSupport");
                    break;
                case BURSTS:
                    category = MAILER_LIMITS;
                    verifyGroup(category, SendingLimitsConfig.Builder.class, "withSendingLimitsConfig", "useConfiguredSendingLimitsConfig");
                    break;
                case RETRY_WAITING: case RETRY_MESSAGE_SIZE: case RETRY_MEMORY:
                    category = MAILER_RETRY;
                    verifyGroup(category, MailSendRetryPolicy.Builder.class, "withMailSendRetryPolicy", "clearMailSendRetryPolicy");
                    break;
                default: throw new AssertionError(this);
            }
            assertPropertyCategory(category, "simplejavamail." + property);
        }

        private static void verifyGroup(final Owner category, final Class<?> choices, final String... methods) {
            assertValueBuilderCategory(category, choices);
            assertApiCategory(category, MailerGenericBuilder.class, methods);
        }

        Object configured(final Object value) {
            if (value instanceof Duration) { return this == SEND_DEADLINE ? value.toString() : Math.toIntExact(((Duration) value).toMillis()); }
            return value instanceof Enum<?> ? ((Enum<?>) value).name() : value;
        }

        void set(final MailerRegularBuilder<?> builder, final Object value) {
            switch (this) {
                case POOL_CORE: builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize((Integer) value).build()); break;
                case POOL_MAX: builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().withMaxSize((Integer) value).build()); break;
                case POOL_CLAIM: builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().withClaimTimeout((Duration) value).build()); break;
                case POOL_IDLE: builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().withExpireAfter((Duration) value).build()); break;
                case POOL_AGE: builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().withExpireAfterCreation((Duration) value).build()); break;
                case BALANCING: builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().withLoadBalancingStrategy((LoadBalancingStrategy) value).build()); break;
                case WORKERS: builder.withAsyncExecutionConfig(AsyncExecutionConfig.builder().withThreadPoolSize((Integer) value).build()); break;
                case KEEP_ALIVE: builder.withAsyncExecutionConfig(AsyncExecutionConfig.builder().withKeepAliveTime((Duration) value).build()); break;
                case QUEUE_CAPACITY: builder.withAsyncExecutionConfig(AsyncExecutionConfig.builder().withQueueCapacity((Integer) value).build()); break;
                case QUEUE_WAIT: builder.withAsyncExecutionConfig(AsyncExecutionConfig.builder().waitForCapacity((Duration) value).build()); break;
                case SEND_DEADLINE: builder.withMailSendTimeout((Duration) value); break;
                case DEBUG_ENABLED: builder.withDebugConfig(SessionDebugConfig.builder().withEnabled((Boolean) value).build()); break;
                case TELEMETRY_ENABLED: builder.withTelemetryConfig(MailSendTelemetryConfig.builder().withEnabled((Boolean) value).build()); break;
                case TELEMETRY_ENDPOINT: builder.withTelemetryConfig(MailSendTelemetryConfig.builder().withSmtpEndpointAttributes((Boolean) value).build()); break;
                case VALIDATION_WARNINGS: builder.withValidationConfig(ValidationConfig.builder().withClientValidationWarningsOnly((Boolean) value).build()); break;
                case LOGGING_ONLY: builder.withTransportModeLoggingOnly((Boolean) value); break;
                case LEGACY_CONTENT: builder.withConnectionConfig(ConnectionConfig.builder().withLegacySmtpContentSupport((Boolean) value).build()); break;
                case BURSTS: builder.withSendingLimitsConfig(SendingLimitsConfig.builder().withRateLimitBurstsAllowed((Boolean) value).build()); break;
                case RETRY_WAITING: builder.withMailSendRetryPolicy(MailSendRetryPolicy.builder().withWaitingCapacity((Integer) value).build()); break;
                case RETRY_MESSAGE_SIZE: builder.withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumMessageSize((Long) value).build()); break;
                case RETRY_MEMORY: builder.withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMemoryLimit((Long) value).build()); break;
                default: throw new AssertionError(this);
            }
        }

        void reset(final MailerRegularBuilder<?> builder) {
            switch (this) {
                case POOL_CORE: builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().resetCoreSize().build()); break;
                case POOL_MAX: builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().resetMaxSize().build()); break;
                case POOL_CLAIM: builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().resetClaimTimeout().build()); break;
                case POOL_IDLE: builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().resetExpireAfter().build()); break;
                case POOL_AGE: builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().clearExpireAfterCreation().build()); break;
                case BALANCING: builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().resetLoadBalancingStrategy().build()); break;
                case WORKERS: builder.withAsyncExecutionConfig(AsyncExecutionConfig.builder().resetThreadPoolSize().build()); break;
                case KEEP_ALIVE: builder.withAsyncExecutionConfig(AsyncExecutionConfig.builder().resetKeepAliveTime().build()); break;
                case QUEUE_CAPACITY: builder.withAsyncExecutionConfig(AsyncExecutionConfig.builder().resetQueueCapacity().build()); break;
                case QUEUE_WAIT: builder.withAsyncExecutionConfig(AsyncExecutionConfig.builder().resetQueue().build()); break;
                case SEND_DEADLINE: builder.resetMailSendTimeout(); break;
                case DEBUG_ENABLED: builder.withDebugConfig(SessionDebugConfig.builder().resetEnabled().build()); break;
                case TELEMETRY_ENABLED: builder.withTelemetryConfig(MailSendTelemetryConfig.builder().resetEnabled().build()); break;
                case TELEMETRY_ENDPOINT: builder.withTelemetryConfig(MailSendTelemetryConfig.builder().resetSmtpEndpointAttributes().build()); break;
                case VALIDATION_WARNINGS: builder.withValidationConfig(ValidationConfig.builder().resetClientValidationWarningsOnly().build()); break;
                case LOGGING_ONLY: builder.resetTransportModeLoggingOnly(); break;
                case LEGACY_CONTENT: builder.withConnectionConfig(ConnectionConfig.builder().resetLegacySmtpContentSupport().build()); break;
                case BURSTS: builder.withSendingLimitsConfig(SendingLimitsConfig.builder().resetRateLimitBurstsAllowed().build()); break;
                case RETRY_WAITING: builder.withMailSendRetryPolicy(MailSendRetryPolicy.builder().resetWaitingCapacity().build()); break;
                case RETRY_MESSAGE_SIZE: builder.withMailSendRetryPolicy(MailSendRetryPolicy.builder().resetMaximumMessageSize().build()); break;
                case RETRY_MEMORY: builder.withMailSendRetryPolicy(MailSendRetryPolicy.builder().resetMemoryLimit().build()); break;
                default: throw new AssertionError(this);
            }
        }

        void restore(final MailerRegularBuilder<?> builder) {
            switch (this) {
                case POOL_CORE: case POOL_MAX: case POOL_CLAIM: case POOL_IDLE: case POOL_AGE: case BALANCING:
                    builder.useConfiguredConnectionPoolConfig(); break;
                case WORKERS: case KEEP_ALIVE: case QUEUE_CAPACITY: case QUEUE_WAIT: builder.useConfiguredAsyncExecutionConfig(); break;
                case DEBUG_ENABLED: builder.useConfiguredDebugConfig(); break;
                case TELEMETRY_ENABLED: case TELEMETRY_ENDPOINT: builder.useConfiguredTelemetryConfig(); break;
                case VALIDATION_WARNINGS: builder.useConfiguredValidationConfig(); break;
                case LEGACY_CONTENT: builder.useConfiguredConnectionConfig(); break;
                case BURSTS: builder.useConfiguredSendingLimitsConfig(); break;
                case RETRY_WAITING: case RETRY_MESSAGE_SIZE: case RETRY_MEMORY: builder.withMailSendRetryPolicy(MailSendRetryPolicy.builder().build()); break;
                case LOGGING_ONLY: builder.resetTransportModeLoggingOnly(); break;
                case SEND_DEADLINE: throw new AssertionError("Deadline API has reset, but no restore companion");
                default: throw new AssertionError(this);
            }
        }

        Object get(final OperationalConfig config) {
            switch (this) {
                case POOL_CORE: return config.getConnectionPoolCoreSize();
                case POOL_MAX: return config.getConnectionPoolMaxSize();
                case POOL_CLAIM: return config.getConnectionPoolClaimTimeout();
                case POOL_IDLE: return config.getConnectionPoolExpireAfter();
                case POOL_AGE: return config.getConnectionPoolExpireAfterCreation();
                case BALANCING: return config.getConnectionPoolLoadBalancingStrategy();
                case WORKERS: return config.getThreadPoolSize();
                case KEEP_ALIVE: return config.getThreadPoolKeepAliveTime();
                case QUEUE_CAPACITY: return config.getAsyncQueueConfig().getCapacity();
                case QUEUE_WAIT: return config.getAsyncQueueConfig().getWaitTimeout();
                case SEND_DEADLINE: return config.getMailSendTimeout();
                case DEBUG_ENABLED: return config.getDebugConfig().getEnabled();
                case TELEMETRY_ENABLED: return config.getTelemetryConfig().getEnabled();
                case TELEMETRY_ENDPOINT: return config.getTelemetryConfig().getSmtpEndpointAttributes();
                case VALIDATION_WARNINGS: return config.isClientValidationWarningsOnly();
                case LOGGING_ONLY: return config.isTransportModeLoggingOnly();
                case LEGACY_CONTENT: return config.isLegacySmtpContentSupportEnabled();
                case BURSTS: return config.isRateLimitBurstsAllowed();
                case RETRY_WAITING: return config.getMailSendRetryPolicy().getWaitingCapacity();
                case RETRY_MESSAGE_SIZE: return config.getMailSendRetryPolicy().getMaximumMessageSize();
                case RETRY_MEMORY: return config.getMailSendRetryPolicy().getMemoryLimit();
                default: throw new AssertionError(this);
            }
        }
    }

    enum ChoiceScenario {
        BUILT_IN(0, 0), CONFIGURED(1, 0), EQUIVALENT_JAVA(1, 1), JAVA_OVERRIDE(1, 2), RESET(1, 3), RESTORE(1, 4);
        final int source;
        final int local;
        ChoiceScenario(final int source, final int local) { this.source = source; this.local = local; }
    }

    @ParameterizedTest
    @EnumSource(Member.class)
    void everyLayeredScalarHonorsConfiguredJavaResetAndRestorationChoices(final Member member) throws Exception {
        // The same adapter checks its API/property category and the resulting public behavior.
        member.verifyCategoryBinding();
        for (ChoiceScenario scenario : ChoiceScenario.values()) {
            final int source = scenario.source;
            final int local = scenario.local;
            if (local == 4 && (member == Member.SEND_DEADLINE || member == Member.LOGGING_ONLY)) { continue; }
            final Map<String, Object> values = source == 0 ? Map.of() : Map.of("simplejavamail." + member.property, member.configured(member.value(source)));
            final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(values).load());
            final MailerRegularBuilder<?> builder = mail.mailerBuilder().withSMTPServerHost("relay.example.test");
            if (local == 1 || local == 2) { member.set(builder, member.value(local)); }
            if (local == 3) { member.set(builder, member.value(2)); member.reset(builder); }
            if (local == 4) { member.set(builder, member.value(2)); member.restore(builder); }
            bindBorrowedDiagnostics(builder);
            final Object expected = local == 1 || local == 2 ? member.value(local) : local == 3 ? member.fallback
                    : source == 0 ? member.fallback : member.value(source);
            try (Mailer result = builder.buildMailer()) {
                assertThat(member.get(result.getOperationalConfig())).as("%s %s", member, scenario).isEqualTo(expected);
            }
            record("rule", "mailer.layer." + scenario.name());
            if (source != 0 && (local == 0 || local == 4)) {
                record("property", "simplejavamail." + member.property);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = Member.class, names = {"POOL_MAX", "POOL_CLAIM", "LEGACY_CONTENT", "TELEMETRY_ENABLED", "RETRY_MEMORY"})
    void aLockAcceptsEquivalentChoicesAndRejectsOnlyTheFinalConflictingChoice(final Member member) throws Exception {
        final Object locked = member.value(1);
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder()
                .withMap(Map.of("simplejavamail.locked." + member.property, member.configured(locked))).load());
        for (int local = 0; local < 4; local++) {
            final MailerRegularBuilder<?> builder = mail.mailerBuilder().withSMTPServerHost("relay.example.test");
            if (local == 1) { member.set(builder, locked); }
            if (local == 2) { member.set(builder, member.value(2)); }
            if (local == 3) { member.set(builder, member.value(2)); member.set(builder, locked); }
            bindBorrowedDiagnostics(builder);
            RuntimeException failure = null;
            Object actual = null;
            try (Mailer result = builder.buildMailer()) { actual = member.get(result.getOperationalConfig()); }
            catch (RuntimeException rejected) { failure = rejected; }
            final boolean conflict = local == 2 && !Objects.equals(locked, member.value(2));
            if (conflict) { assertThat(failure).as("%s local=%s", member, local).isInstanceOf(IllegalArgumentException.class); }
            else { assertThat(failure).as("%s local=%s", member, local).isNull(); assertThat(actual).isEqualTo(locked); }
            record("rule", "mailer.lock." + new String[] {"INHERITED", "EQUIVALENT", "CONFLICT", "REPLACED"}[local]);
        }
    }

    @Test
    void factorySourcePrecedenceIsSettledBeforeTheConsumerSelectsItsJavaChoice() throws Exception {
        for (Member member : new Member[] {Member.POOL_MAX, Member.SEND_DEADLINE, Member.DEBUG_ENABLED}) {
            final String name = "simplejavamail." + member.property;
            final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder()
                    .withMap("base", Map.of(name, member.configured(member.value(1))))
                    .withMap("deployment", Map.of(name, member.configured(member.value(2)))).load());
            final MailerRegularBuilder<?> builder = mail.mailerBuilder().withSMTPServerHost("relay.example.test");
            bindBorrowedDiagnostics(builder);
            try (Mailer result = builder.buildMailer()) { assertThat(member.get(result.getOperationalConfig())).as(member.name()).isEqualTo(member.value(2)); }
            record("rule", "mailer.source.precedence");
            record("property", name);
        }
    }

    enum PoolHistory {
        EMPTY(), SET(0), REPLACE(0,1), RESET(1,2), RESTORE(0,3), GROUP_REPLACE(0,4),
        REPEATED_RESTORE(0,1,3), REPLACE_AND_RESET(0,4,1,2), RESET_AND_RESTORE(0,1,2,3);
        final int[] operations;
        PoolHistory(final int... operations) { this.operations = operations; }
    }

    @Test
    void poolEditingCategoriesPreserveSuppliedVersusInheritedChoices() {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of("simplejavamail.defaults.connectionpool.maxsize", 7)).load());
        final Set<Integer> lengths = new LinkedHashSet<>();
        final Set<Integer> edits = new LinkedHashSet<>();
        for (PoolHistory history : PoolHistory.values()) {
            lengths.add(history.operations.length);
            final MailerRegularBuilder<?> builder = mail.mailerBuilder();
            Integer expected = null;
            for (int operation : history.operations) {
                edits.add(operation);
                switch (operation) {
                    case 0: builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().withMaxSize(8).build()); expected = 8; break;
                    case 1: builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().withMaxSize(9).build()); expected = 9; break;
                    case 2: builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().resetMaxSize().build()); expected = 4; break;
                    case 3: builder.useConfiguredConnectionPoolConfig(); expected = null; break;
                    case 4: builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(1).build()); expected = null; break;
                    default: throw new AssertionError(operation);
                }
            }
            assertThat(builder.getConnectionPoolConfig().getMaxSize()).as(history.name()).isEqualTo(expected);
            record("rule", "mailer.pool.editing." + history.name());
        }
        assertThat(lengths).containsExactlyInAnyOrder(0, 1, 2, 3, 4);
        assertThat(edits).containsExactlyInAnyOrder(0, 1, 2, 3, 4);
    }

    @Test
    void borrowedExecutorsRemainBorrowedAfterGroupReplacementAndRestoration() throws Exception {
        final ExecutorService borrowed = Executors.newSingleThreadExecutor();
        try {
            final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
            final MailerRegularBuilder<?> builder = mail.mailerBuilder().withSMTPServerHost("relay.example.test")
                    .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withExecutorService(borrowed).build());
            try (Mailer first = builder.buildMailer()) { assertThat(first.getOperationalConfig().getExecutorService()).isSameAs(borrowed); }
            assertThat(borrowed.isShutdown()).isFalse();
            record("rule", "mailer.borrowed.executor");
            builder.useConfiguredAsyncExecutionConfig();
            assertThat(builder.getAsyncExecutionConfig().getExecutorService()).isNull();
            assertThat(borrowed.isShutdown()).isFalse();
        } finally { borrowed.shutdownNow(); }
    }

    @Test
    void completeRateValuesReplaceTheirCapturedPairAndMemberClearsRestoreIndependently() throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.smtp.ratelimit.messages.limit", 3, "simplejavamail.smtp.ratelimit.messages.period", "PT1S",
                "simplejavamail.smtp.ratelimit.recipients.limit", 5, "simplejavamail.smtp.ratelimit.recipients.period", "PT2S")).load());
        final MailerRegularBuilder<?> builder = mail.mailerBuilder().withSMTPServerHost("relay.example.test")
                .withSendingLimitsConfig(SendingLimitsConfig.builder().withMessageRateLimit(7, Duration.ofSeconds(3)).clearRecipientRateLimit().build());
        try (Mailer selected = builder.buildMailer()) {
            assertThat(selected.getOperationalConfig().getMessageRateLimit().getCount()).isEqualTo(7);
            assertThat(selected.getOperationalConfig().getMessageRateLimit().getPeriod()).isEqualTo(Duration.ofSeconds(3));
            assertThat(selected.getOperationalConfig().getRecipientRateLimit()).isNull();
        }
        builder.withSendingLimitsConfig(builder.getSendingLimitsConfig().toBuilder().useConfiguredMessageRateLimit()
                .useConfiguredRecipientRateLimit().build());
        try (Mailer restored = builder.buildMailer()) {
            assertThat(restored.getOperationalConfig().getMessageRateLimit().getCount()).isEqualTo(3);
            assertThat(restored.getOperationalConfig().getMessageRateLimit().getPeriod()).isEqualTo(Duration.ofSeconds(1));
            assertThat(restored.getOperationalConfig().getRecipientRateLimit().getCount()).isEqualTo(5);
            assertThat(restored.getOperationalConfig().getRecipientRateLimit().getPeriod()).isEqualTo(Duration.ofSeconds(2));
        }
        record("rule", "mailer.limits.atomic-replacement");
        record("property", "simplejavamail.smtp.ratelimit.messages.limit");
        record("property", "simplejavamail.smtp.ratelimit.messages.period");
        record("property", "simplejavamail.smtp.ratelimit.recipients.limit");
        record("property", "simplejavamail.smtp.ratelimit.recipients.period");
    }

    @Test
    void retryScheduleRemovalAndGroupRestorationPreserveResourceOverrides() throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.smtp.retry.attempts", 3, "simplejavamail.smtp.retry.delay", "PT0.1S",
                "simplejavamail.smtp.retry.backoffmultiplier", 2, "simplejavamail.smtp.retry.maximumdelay", "PT1S",
                "simplejavamail.smtp.retry.jitter", 0.25, "simplejavamail.defaults.mailsend.retry.waitingcapacity", 8)).load());
        final MailerRegularBuilder<?> builder = mail.mailerBuilder().withSMTPServerHost("relay.example.test")
                .withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumAttempts(4).withWaitingCapacity(12).build());
        try (Mailer selected = builder.buildMailer()) {
            assertThat(selected.getOperationalConfig().getMailSendRetryPolicy().getMaximumAttempts()).isEqualTo(4);
            assertThat(selected.getOperationalConfig().getMailSendRetryPolicy().getRetryDelay()).isEqualTo(Duration.ofMillis(100));
            assertThat(selected.getOperationalConfig().getMailSendRetryPolicy().getWaitingCapacity()).isEqualTo(12);
        }
        builder.withMailSendRetryPolicy(builder.getMailSendRetryPolicy().toBuilder().clearRetrySchedule().build());
        try (Mailer disabled = builder.buildMailer()) {
            final MailSendRetryPolicy policy = disabled.getOperationalConfig().getMailSendRetryPolicy();
            assertThat(policy.getMaximumAttempts()).isNull();
            assertThat(policy.getRetryDelay()).isNull();
            assertThat(policy.getRetryBackoffMultiplier()).isEqualTo(1);
            assertThat(policy.getRetryJitter()).isZero();
            assertThat(policy.getWaitingCapacity()).isEqualTo(12);
        }
        builder.withMailSendRetryPolicy(MailSendRetryPolicy.builder().build());
        try (Mailer restored = builder.buildMailer()) {
            assertThat(restored.getOperationalConfig().getMailSendRetryPolicy().getMaximumAttempts()).isEqualTo(3);
            assertThat(restored.getOperationalConfig().getMailSendRetryPolicy().getRetryDelay()).isEqualTo(Duration.ofMillis(100));
            assertThat(restored.getOperationalConfig().getMailSendRetryPolicy().getRetryBackoffMultiplier()).isEqualTo(2);
            assertThat(restored.getOperationalConfig().getMailSendRetryPolicy().getMaximumRetryDelay()).isEqualTo(Duration.ofSeconds(1));
            assertThat(restored.getOperationalConfig().getMailSendRetryPolicy().getRetryJitter()).isEqualTo(0.25);
            assertThat(restored.getOperationalConfig().getMailSendRetryPolicy().getWaitingCapacity()).isEqualTo(8);
        }
        record("rule", "mailer.retry.clear-and-restore");
        record("property", "simplejavamail.smtp.retry.attempts");
        record("property", "simplejavamail.smtp.retry.delay");
        record("property", "simplejavamail.smtp.retry.backoffmultiplier");
        record("property", "simplejavamail.smtp.retry.maximumdelay");
        record("property", "simplejavamail.smtp.retry.jitter");
        record("property", "simplejavamail.defaults.mailsend.retry.waitingcapacity");
    }

    @Test
    void anExplicitFailoverPolicyRemainsACompleteValueRatherThanAMemberPatch() throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.failover.primary.connectionattempts", 5,
                "simplejavamail.failover.primary.retrydelay", "PT3S",
                "simplejavamail.failover.mailer", "named-backup",
                "simplejavamail.mailers.named-backup.smtp.host", "named-backup.example.test")).load());
        final FailoverPolicy explicit = FailoverPolicy.builder().withPrimaryConnectionAttempts(2).build();
        try (Mailer backup = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).mailerBuilder().withSMTPServerHost("backup.example.test").buildMailer();
             Mailer primary = mail.mailerBuilder().withSMTPServerHost("primary.example.test").withFailoverMailer(backup, explicit).buildMailer()) {
            assertThat(primary.getOperationalConfig().getFailoverPolicy()).isSameAs(explicit);
        }
        record("rule", "mailer.failover.complete-policy");
    }

    @Test
    void namedFailoverSourcesReachTheCapturedRouteAndEveryPolicyMember() {
        final Map<String, Object> values = Map.ofEntries(
                Map.entry("simplejavamail.smtp.host", "primary.example.test"),
                Map.entry("simplejavamail.failover.mailer", "backup"),
                Map.entry("simplejavamail.mailers.backup.smtp.host", "backup.example.test"),
                Map.entry("simplejavamail.failover.primary.connectionattempts", 4),
                Map.entry("simplejavamail.failover.primary.retrydelay", "PT0.04S"),
                Map.entry("simplejavamail.failover.primary.recheckinterval", "PT5S"),
                Map.entry("simplejavamail.failover.primary.backoffmultiplier", 2),
                Map.entry("simplejavamail.failover.primary.maximumrecheckinterval", "PT10S"),
                Map.entry("simplejavamail.failover.onauthenticationfailure", true),
                Map.entry("simplejavamail.failover.oncertificateverificationfailure", true));
        final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(ConfigLoader.builder().withMap("named route", values).load());
        final ResolvedMailerConfiguration selected = ResolvedMailerConfiguration.resolve(builder.captureMailerInputs());
        final FailoverPolicy policy = selected.getOperational().getFailoverPolicy();
        assertThat(selected.getNamedChild().getInputs().getDefinition().name).isEqualTo("backup");
        assertThat(policy.getPrimaryConnectionAttempts()).isEqualTo(4);
        assertThat(policy.getPrimaryConnectionRetryDelay()).isEqualTo(Duration.ofMillis(40));
        assertThat(policy.getPrimaryRecheckInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.getPrimaryRecheckBackoffMultiplier()).isEqualTo(2);
        assertThat(policy.getPrimaryMaximumRecheckInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.isFailoverOnAuthenticationFailure()).isTrue();
        assertThat(policy.isFailoverOnCertificateVerificationFailure()).isTrue();
        for (String property : values.keySet()) {
            if (property.startsWith("simplejavamail.failover.")) { record("property", property); }
        }
    }

    private static void bindBorrowedDiagnostics(final MailerRegularBuilder<?> builder) {
        // Keep optional telemetry activation and console output independent of the source-selection table under examination.
        builder.withTelemetryConfig(builder.getTelemetryConfig().toBuilder().withIntegration(TELEMETRY).build());
        builder.withDebugConfig(builder.getDebugConfig().toBuilder().withPrinter(DEBUG).build());
    }
}
