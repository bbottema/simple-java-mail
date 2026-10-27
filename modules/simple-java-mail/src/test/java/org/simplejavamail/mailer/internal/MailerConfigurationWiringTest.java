package org.simplejavamail.mailer.internal;

import com.sanctionco.jmail.EmailValidator;
import com.sanctionco.jmail.JMail;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.AsyncQueueConfig;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import org.simplejavamail.api.mailer.config.SendingLimitsConfig;
import org.simplejavamail.api.mailer.config.SendingRateLimit;
import org.simplejavamail.api.mailer.config.SessionDebugConfig;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.api.mailer.config.ValidationConfig;
import org.simplejavamail.config.ConfigLoader;
import testutil.configcontracts.ConfigurationContractCoverage;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.*;
import static testutil.configcontracts.ConfigurationContractCoverage.invoke;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

/** Each frozen overload is invoked independently and its selected member is inspected through the real capture route. */
class MailerConfigurationWiringTest {
    private static final UUID CONFIGURED_CLUSTER = UUID.fromString("e48b62c8-771f-4f17-b33e-df78216c1b36");
    private static final UUID JAVA_CLUSTER = UUID.fromString("aa960e7f-3122-4fe4-8df8-d742fdd6a86d");
    private static final ExecutorService EXECUTOR = mock(ExecutorService.class);
    private static final EmailValidator VALIDATOR = mock(EmailValidator.class);
    private static final MailSendTelemetry TELEMETRY = new MailSendTelemetry() { };
    private static final PrintStream PRINTER = new PrintStream(OutputStream.nullOutputStream());

    enum Family {
        POOL(ConnectionPoolConfig.Builder.class, "pool", "withConnectionPoolConfig", ConnectionPoolConfig.class),
        CLUSTER(ConnectionPoolClusterConfig.ConnectionPoolClusterConfigBuilder.class, "cluster", null, ConnectionPoolClusterConfig.class),
        ASYNC(AsyncExecutionConfig.Builder.class, "async", "withAsyncExecutionConfig", AsyncExecutionConfig.class),
        DEBUG(SessionDebugConfig.Builder.class, "debug", "withDebugConfig", SessionDebugConfig.class),
        TELEMETRY(MailSendTelemetryConfig.Builder.class, "telemetry", "withTelemetryConfig", MailSendTelemetryConfig.class),
        VALIDATION(ValidationConfig.Builder.class, "validation", "withValidationConfig", ValidationConfig.class),
        LIMITS(SendingLimitsConfig.Builder.class, "limits", "withSendingLimitsConfig", SendingLimitsConfig.class),
        RETRY(MailSendRetryPolicy.Builder.class, "retry", "withMailSendRetryPolicy", MailSendRetryPolicy.class),
        FAILOVER(FailoverPolicy.Builder.class, "failover", null, FailoverPolicy.class),
        CONTENT(ConnectionConfig.Builder.class, "connection-content", "withConnectionConfig", ConnectionConfig.class);

        final Class<?> builderType;
        final String id;
        final String groupSetter;
        final Class<?> valueType;

        Family(final Class<?> builderType, final String id, final String groupSetter, final Class<?> valueType) {
            this.builderType = builderType;
            this.id = id;
            this.groupSetter = groupSetter;
            this.valueType = valueType;
        }
    }

    static Stream<Method> groupedRoutes() {
        final List<Method> methods = new ArrayList<>();
        for (Family family : Family.values()) {
            for (Method method : family.builderType.getDeclaredMethods()) {
                final ConfigurationAdoptionCatalogue.Owner owner = ConfigurationAdoptionCatalogue.API_OWNERS.get(
                        ConfigurationContractCoverage.signature(method));
                if (owner != null && owner.name().startsWith("MAILER") && owner != ConfigurationAdoptionCatalogue.Owner.MAILER_CONNECTION) {
                    methods.add(method);
                }
            }
        }
        return methods.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("groupedRoutes")
    void eachValueBuilderRouteSuppliesTheIntendedCapturedMember(final Method method) throws Exception {
        final Family family = Arrays.stream(Family.values()).filter(candidate -> candidate.builderType == method.getDeclaringClass()).findFirst().orElseThrow();
        final Field field = field(family, method.getName());
        final Object draft = seed(family, field.name);
        final boolean inherit = method.getName().startsWith("useConfigured") || family == Family.CLUSTER && method.getName().startsWith("clear");
        final boolean clear = !inherit && method.getName().startsWith("clear");
        final boolean reset = method.getName().startsWith("reset");
        Object expectedChoice = inherit || clear ? field.clearedChoice : reset ? field.resetChoice : field.suppliedChoice;
        invoke(draft, method, arguments(family, field, method));
        if (family == Family.POOL && method.getName().equals("resetClusterKey")) {
            expectedChoice = field.readChoice(draft);
            assertThat(expectedChoice).isNotNull().isNotEqualTo(CONFIGURED_CLUSTER);
        }
        assertThat(normalize(field.readChoice(draft))).as("Collected member for %s", method).isEqualTo(normalize(expectedChoice));
        assertRemovalState(family, field, draft, inherit, clear);
        final Object choices = family.builderType.getMethod("build").invoke(draft);

        if (family == Family.CLUSTER) {
            assertThat(normalize(field.readChoice(choices))).as("Frozen cluster member for %s", method).isEqualTo(normalize(expectedChoice));
            record("field", "mailer.cluster." + field.name);
            record("adapter", "mailer.cluster.value-to-capture");
            return;
        }

        final MailerRegularBuilderImpl mailer = mailer();
        if (family == Family.FAILOVER) {
            invoke(mailer, MailerGenericBuilder.class.getMethod("withFailoverMailer", Mailer.class, FailoverPolicy.class),
                    mock(MailerImpl.class), choices);
        } else {
            invoke(mailer, MailerGenericBuilder.class.getMethod(family.groupSetter, family.valueType), choices);
        }
        if (family == Family.RETRY && clear && (field.name.equals("maximumAttempts") || field.name.equals("retryDelay"))) {
            assertThatThrownBy(mailer::captureMailerInputs).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("both");
            record("rule", "mailer.retry.partial-removal-rejected");
        } else {
            final ResolvedMailerConfiguration captured = ResolvedMailerConfiguration.resolve(mailer.captureMailerInputs());
            final Object expectedEffective = family == Family.POOL && method.getName().equals("resetClusterKey") ? expectedChoice
                    : inherit ? field.inherited : clear ? field.cleared : reset ? field.resetEffective : field.suppliedEffective;
            assertThat(normalize(field.readEffective.apply(captured))).as("Captured member for %s", method).isEqualTo(normalize(expectedEffective));
            if (family == Family.ASYNC && method.getName().equals("resetQueue")) {
                assertThat(captured.getOperational().getAsyncQueueConfig().getCapacity()).isEqualTo(DEFAULT_ASYNC_QUEUE_CAPACITY);
            }
            if (inherit) {
                for (String property : properties(family, field.name)) { record("property", property); }
            }
        }
        record("field", fieldId(family, field));
        record("adapter", "mailer." + family.id + ".value-to-capture");
    }

    static Stream<Method> directRoutes() {
        return Arrays.stream(MailerGenericBuilder.class.getDeclaredMethods()).filter(method -> {
            final ConfigurationAdoptionCatalogue.Owner owner = ConfigurationAdoptionCatalogue.API_OWNERS.get(ConfigurationContractCoverage.signature(method));
            return owner != null && owner.name().startsWith("MAILER") && owner != ConfigurationAdoptionCatalogue.Owner.MAILER_CONNECTION
                    && owner != ConfigurationAdoptionCatalogue.Owner.MAILER_TEMPLATE_SELECTION;
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("directRoutes")
    void eachDirectMailerRouteReachesItsCapturedOwner(final Method method) throws Exception {
        final MailerRegularBuilderImpl builder = mailer();
        final String name = method.getName();
        final Object[] args;
        final String field;
        final Function<ResolvedMailerConfiguration, Object> read;
        final Object expected;
        switch (name) {
            case "withConnectionPoolConfig":
                args = new Object[] {ConnectionPoolConfig.builder().withMaxSize(13).build()}; field = "pool.maxSize";
                read = selected -> selected.getOperational().getConnectionPoolMaxSize(); expected = 13; break;
            case "useConfiguredConnectionPoolConfig":
                builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().withMaxSize(13).build()); args = new Object[0]; field = "pool.maxSize";
                read = selected -> selected.getOperational().getConnectionPoolMaxSize(); expected = 7; break;
            case "withAsyncExecutionConfig":
                args = new Object[] {AsyncExecutionConfig.builder().withThreadPoolSize(13).build()}; field = "async.threadPoolSize";
                read = selected -> selected.getOperational().getThreadPoolSize(); expected = 13; break;
            case "useConfiguredAsyncExecutionConfig":
                builder.withAsyncExecutionConfig(AsyncExecutionConfig.builder().withThreadPoolSize(13).build()); args = new Object[0]; field = "async.threadPoolSize";
                read = selected -> selected.getOperational().getThreadPoolSize(); expected = 3; break;
            case "withDebugConfig":
                args = new Object[] {SessionDebugConfig.builder().withEnabled(false).build()}; field = "debug.enabled";
                read = selected -> selected.getOperational().getDebugConfig().getEnabled(); expected = false; break;
            case "useConfiguredDebugConfig":
                builder.withDebugConfig(SessionDebugConfig.builder().withEnabled(false).build()); args = new Object[0]; field = "debug.enabled";
                read = selected -> selected.getOperational().getDebugConfig().getEnabled(); expected = true; break;
            case "withTelemetryConfig":
                args = new Object[] {MailSendTelemetryConfig.builder().withEnabled(false).build()}; field = "telemetry.enabled";
                read = selected -> selected.getOperational().getTelemetryConfig().getEnabled(); expected = false; break;
            case "useConfiguredTelemetryConfig":
                builder.withTelemetryConfig(MailSendTelemetryConfig.builder().withEnabled(false).build()); args = new Object[0]; field = "telemetry.enabled";
                read = selected -> selected.getOperational().getTelemetryConfig().getEnabled(); expected = true; break;
            case "withValidationConfig":
                args = new Object[] {ValidationConfig.builder().withClientValidationWarningsOnly(false).build()}; field = "validation.clientValidationWarningsOnly";
                read = selected -> selected.getOperational().isClientValidationWarningsOnly(); expected = false; break;
            case "useConfiguredValidationConfig":
                builder.withValidationConfig(ValidationConfig.builder().withClientValidationWarningsOnly(false).build()); args = new Object[0]; field = "validation.clientValidationWarningsOnly";
                read = selected -> selected.getOperational().isClientValidationWarningsOnly(); expected = true; break;
            case "withSendingLimitsConfig":
                args = new Object[] {SendingLimitsConfig.builder().withRateLimitGroup("java").build()}; field = "limits.rateLimitGroup";
                read = selected -> selected.getOperational().getRateLimitGroup(); expected = "java"; break;
            case "useConfiguredSendingLimitsConfig":
                builder.withSendingLimitsConfig(SendingLimitsConfig.builder().withRateLimitGroup("java").build()); args = new Object[0]; field = "limits.rateLimitGroup";
                read = selected -> selected.getOperational().getRateLimitGroup(); expected = "configured"; break;
            case "withMailSendRetryPolicy":
                args = new Object[] {MailSendRetryPolicy.builder().withWaitingCapacity(13).build()}; field = "retry.waitingCapacity";
                read = selected -> selected.getOperational().getMailSendRetryPolicy().getWaitingCapacity(); expected = 13; break;
            case "clearMailSendRetryPolicy":
                builder.withMailSendRetryPolicy(MailSendRetryPolicy.builder().withWaitingCapacity(13).build()); args = new Object[0]; field = "retry.schedule";
                read = selected -> retrySchedule(selected.getOperational().getMailSendRetryPolicy()); expected = list(null, null, 1, null, 0d); break;
            case "withFailoverMailer":
                final MailerImpl borrowed = mock(MailerImpl.class);
                final FailoverPolicy policy = FailoverPolicy.builder().withPrimaryConnectionAttempts(4).build();
                args = new Object[] {borrowed, policy}; field = "failover.route";
                read = selected -> list(selected.getInputs().getBorrowedFailover(), selected.getOperational().getFailoverPolicy()); expected = list(borrowed, policy); break;
            case "clearFailoverMailer":
                builder.withFailoverMailer(mock(MailerImpl.class), FailoverPolicy.builder().build()); args = new Object[0]; field = "failover.route";
                read = selected -> list(selected.getInputs().getBorrowedFailover(), selected.getOperational().getFailoverPolicy()); expected = list(null, null); break;
            case "withMailSendTimeout":
                args = new Object[] {Duration.ofSeconds(9)}; field = "deadline";
                read = selected -> selected.getOperational().getMailSendTimeout(); expected = Duration.ofSeconds(9); break;
            case "resetMailSendTimeout":
                builder.withMailSendTimeout(Duration.ofSeconds(9)); args = new Object[0]; field = "deadline";
                read = selected -> selected.getOperational().getMailSendTimeout(); expected = DEFAULT_MAIL_SEND_TIMEOUT; break;
            case "withTransportModeLoggingOnly":
                args = new Object[] {false}; field = "loggingOnly";
                read = selected -> selected.getOperational().isTransportModeLoggingOnly(); expected = false; break;
            case "resetTransportModeLoggingOnly":
                builder.withTransportModeLoggingOnly(true); args = new Object[0]; field = "loggingOnly";
                read = selected -> selected.getOperational().isTransportModeLoggingOnly(); expected = DEFAULT_TRANSPORT_MODE_LOGGING_ONLY; break;
            default: throw new AssertionError("Unclassified direct Mailer setup route: " + method);
        }
        invoke(builder, method, args);
        final ResolvedMailerConfiguration captured = ResolvedMailerConfiguration.resolve(builder.captureMailerInputs());
        assertThat(normalize(read.apply(captured))).as(method.toString()).isEqualTo(normalize(expected));
        record("field", "mailer." + field);
        record("adapter", "mailer.direct-to-capture");
    }

    private static Field field(final Family family, final String method) {
        switch (family) {
            case POOL: case CLUSTER:
                if (contains(method, "CoreSize")) { return scalar("coreSize", "getCoreSize", 2, DEFAULT_CONNECTIONPOOL_CORE_SIZE, 1,
                        value -> value.getOperational().getConnectionPoolCoreSize()); }
                if (contains(method, "MaxSize")) { return scalar("maxSize", "getMaxSize", 8, DEFAULT_CONNECTIONPOOL_MAX_SIZE, 7,
                        value -> value.getOperational().getConnectionPoolMaxSize()); }
                if (contains(method, "ClaimTimeout")) { return scalar("claimTimeout", "getClaimTimeout", Duration.ofSeconds(3), DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT,
                        Duration.ofMillis(200), value -> value.getOperational().getConnectionPoolClaimTimeout()); }
                if (contains(method, "ExpireAfterCreation")) { return scalar("expireAfterCreation", "getExpireAfterCreation", Duration.ofSeconds(9), null,
                        Duration.ofMillis(600), value -> value.getOperational().getConnectionPoolExpireAfterCreation()); }
                if (contains(method, "ExpireAfter")) { return scalar("expireAfter", "getExpireAfter", Duration.ofSeconds(6), DEFAULT_CONNECTIONPOOL_EXPIREAFTER,
                        Duration.ofMillis(400), value -> value.getOperational().getConnectionPoolExpireAfter()); }
                if (contains(method, "ClusterKey")) { return scalar("clusterKey", "getClusterKey", JAVA_CLUSTER, null, CONFIGURED_CLUSTER,
                        value -> value.getOperational().getClusterKey()); }
                if (contains(method, "LoadBalancingStrategy")) { return scalar("loadBalancingStrategy", "getLoadBalancingStrategy", LoadBalancingStrategy.RANDOM_ACCESS,
                        LoadBalancingStrategy.ROUND_ROBIN, LoadBalancingStrategy.ROUND_ROBIN, value -> value.getOperational().getConnectionPoolLoadBalancingStrategy()); }
                break;
            case ASYNC:
                if (contains(method, "ThreadPoolSize")) { return scalar("threadPoolSize", "getThreadPoolSize", 4, DEFAULT_POOL_SIZE, 3, value -> value.getOperational().getThreadPoolSize()); }
                if (contains(method, "KeepAliveTime")) { return scalar("keepAliveTime", "getKeepAliveTime", Duration.ofMillis(700), DEFAULT_POOL_KEEP_ALIVE_TIME,
                        Duration.ofMillis(10), value -> value.getOperational().getThreadPoolKeepAliveTime()); }
                if (contains(method, "QueueCapacity")) { return scalar("queueCapacity", "getQueueCapacity", 17, DEFAULT_ASYNC_QUEUE_CAPACITY, 11,
                        value -> value.getOperational().getAsyncQueueConfig().getCapacity()); }
                if (contains(method, "ExecutorService")) { return scalar("executorService", "getExecutorService", EXECUTOR, null, null,
                        value -> value.getOperational().getExecutorService()); }
                if (method.equals("resetQueue")) { return new Field("capacityPolicy", null, list(true, DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT),
                        list(true, DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT), null, value -> list(value.getOperational().getAsyncQueueConfig().getOverflowPolicy() == AsyncQueueOverflowPolicy.REJECT,
                        value.getOperational().getAsyncQueueConfig().getWaitTimeout()), null, list(true, DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT)); }
                if (method.equals("rejectWhenFull")) { return new Field("capacityPolicy", null, list(true, null), null,
                        list(false, Duration.ofMillis(300)), value -> queuePolicy(value.getOperational().getAsyncQueueConfig()), list(true, Duration.ofMillis(300)), null); }
                if (method.equals("waitForCapacity")) { return new Field("capacityPolicy", null, list(false, Duration.ofSeconds(2)), null,
                        list(false, Duration.ofMillis(300)), value -> queuePolicy(value.getOperational().getAsyncQueueConfig()), list(false, Duration.ofSeconds(2)), null); }
                if (method.equals("useConfiguredCapacityPolicy")) { return new Field("capacityPolicy", null, null, null,
                        list(false, Duration.ofMillis(300)), value -> queuePolicy(value.getOperational().getAsyncQueueConfig()), null, null)
                        .clearChoice(list(null, null)); }
                break;
            case DEBUG:
                if (contains(method, "Enabled")) { return scalar("enabled", "getEnabled", false, false, true, value -> value.getOperational().getDebugConfig().getEnabled()); }
                if (contains(method, "Output")) { return scalar("output", "getOutput", SessionDebugOutput.STDOUT, DEFAULT_JAVAXMAIL_DEBUG_OUTPUT,
                        SessionDebugOutput.STDERR, value -> value.getOperational().getDebugConfig().getOutput()); }
                if (contains(method, "Printer")) { return scalar("printer", "getPrinter", PRINTER, null, null, value -> value.getOperational().getDebugConfig().getPrinter()); }
                break;
            case TELEMETRY:
                if (contains(method, "Enabled")) { return scalar("enabled", "getEnabled", false, DEFAULT_MAIL_SEND_TELEMETRY_ENABLED, true,
                        value -> value.getOperational().getTelemetryConfig().getEnabled()); }
                if (contains(method, "SmtpEndpointAttributes")) { return scalar("smtpEndpointAttributes", "getSmtpEndpointAttributes", false,
                        DEFAULT_MAIL_SEND_TELEMETRY_SMTP_ENDPOINT_ATTRIBUTES, true, value -> value.getOperational().getTelemetryConfig().getSmtpEndpointAttributes()); }
                if (contains(method, "Integration")) { return scalar("integration", "getIntegration", TELEMETRY, null, null,
                        value -> value.getOperational().getTelemetryConfig().getIntegration()); }
                break;
            case VALIDATION:
                if (contains(method, "ClientValidationWarningsOnly")) { return scalar("clientValidationWarningsOnly", "getClientValidationWarningsOnly", false,
                        DEFAULT_DISABLE_ALL_CLIENTVALIDATION, true, value -> value.getOperational().isClientValidationWarningsOnly()); }
                if (contains(method, "EmailValidator")) { return scalar("emailValidator", "getEmailValidator", VALIDATOR, JMail.strictValidator(), JMail.strictValidator(),
                        value -> value.getGovernance().getEmailValidator()); }
                if (contains(method, "MaximumEmailSize")) { return scalar("maximumEmailSize", "getMaximumEmailSize", 3072, null, null,
                        value -> value.getGovernance().getMaximumEmailSize()); }
                break;
            case LIMITS:
                if (contains(method, "MessageRateLimit")) { return scalar("messageRateLimit", "getMessageRateLimit", new SendingRateLimit(4, Duration.ofSeconds(5)), null,
                        new SendingRateLimit(3, Duration.ofSeconds(1)), value -> value.getOperational().getMessageRateLimit()); }
                if (contains(method, "RecipientRateLimit")) { return scalar("recipientRateLimit", "getRecipientRateLimit", new SendingRateLimit(6, Duration.ofSeconds(7)), null,
                        new SendingRateLimit(5, Duration.ofSeconds(2)), value -> value.getOperational().getRecipientRateLimit()); }
                if (contains(method, "RateLimitGroup")) { return scalar("rateLimitGroup", "getRateLimitGroup", "java", null, "configured", value -> value.getOperational().getRateLimitGroup()); }
                if (contains(method, "RateLimitBurstsAllowed")) { return scalar("rateLimitBurstsAllowed", "getRateLimitBurstsAllowed", false, true, false,
                        value -> value.getOperational().isRateLimitBurstsAllowed()); }
                break;
            case RETRY:
                if (contains(method, "MaximumAttempts")) { return scalar("maximumAttempts", "getMaximumAttempts", 4, null, 3,
                        value -> value.getOperational().getMailSendRetryPolicy().getMaximumAttempts()); }
                if (contains(method, "RetryDelay")) { return scalar("retryDelay", "getRetryDelay", Duration.ofMillis(300), null, Duration.ofMillis(100),
                        value -> value.getOperational().getMailSendRetryPolicy().getRetryDelay()); }
                if (contains(method, "RetryBackoff")) { return new Field("retryBackoff", null, list(2, Duration.ofSeconds(2)), list(1, null), list(2, Duration.ofSeconds(5)),
                        value -> retryBackoff(value.getOperational().getMailSendRetryPolicy()), list(2, Duration.ofSeconds(2)), list(1, null)); }
                if (contains(method, "RetryJitter")) { return scalar("retryJitter", "getRetryJitter", 0.2d, DEFAULT_MAIL_SEND_RETRY_JITTER, 0.1d,
                        value -> value.getOperational().getMailSendRetryPolicy().getRetryJitter()); }
                if (contains(method, "WaitingCapacity")) { return scalar("waitingCapacity", "getWaitingCapacity", 9, DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY, 8,
                        value -> value.getOperational().getMailSendRetryPolicy().getWaitingCapacity()); }
                if (contains(method, "MaximumMessageSize")) { return scalar("maximumMessageSize", "getMaximumMessageSize", 64L * 1024, DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE, 4L * 1024 * 1024,
                        value -> value.getOperational().getMailSendRetryPolicy().getMaximumMessageSize()); }
                if (contains(method, "MemoryLimit")) { return scalar("memoryLimit", "getMemoryLimit", 256L * 1024 * 1024, DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT, 64L * 1024 * 1024,
                        value -> value.getOperational().getMailSendRetryPolicy().getMemoryLimit()); }
                if (method.equals("clearRetrySchedule")) { return new Field("schedule", null, null, null, null,
                        value -> retrySchedule(value.getOperational().getMailSendRetryPolicy()), null, null).clearChoice(list(null, null, 1, null, 0d)).clearEffective(list(null, null, 1, null, 0d)); }
                break;
            case FAILOVER:
                if (contains(method, "PrimaryConnectionAttempts")) { return scalar("primaryConnectionAttempts", "getPrimaryConnectionAttempts", 4, DEFAULT_FAILOVER_PRIMARY_CONNECTION_ATTEMPTS, null,
                        value -> value.getOperational().getFailoverPolicy().getPrimaryConnectionAttempts()); }
                if (contains(method, "PrimaryConnectionRetryDelay")) { return scalar("primaryConnectionRetryDelay", "getPrimaryConnectionRetryDelay", Duration.ofMillis(300), DEFAULT_FAILOVER_PRIMARY_CONNECTION_RETRY_DELAY, null,
                        value -> value.getOperational().getFailoverPolicy().getPrimaryConnectionRetryDelay()); }
                if (contains(method, "PrimaryRecheckInterval")) { return scalar("primaryRecheckInterval", "getPrimaryRecheckInterval", Duration.ofSeconds(4), DEFAULT_FAILOVER_PRIMARY_RECHECK_INTERVAL, null,
                        value -> value.getOperational().getFailoverPolicy().getPrimaryRecheckInterval()); }
                if (contains(method, "PrimaryRecheckBackoff")) { return new Field("primaryRecheckBackoff", null, list(2, Duration.ofSeconds(10)), list(1, null), null,
                        value -> failoverBackoff(value.getOperational().getFailoverPolicy()), list(2, Duration.ofSeconds(10)), list(1, Duration.ofSeconds(2))); }
                if (contains(method, "FailoverOnAuthenticationFailure")) { return scalar("authenticationFailure", "isFailoverOnAuthenticationFailure", false, DEFAULT_FAILOVER_ON_AUTHENTICATION_FAILURE, null,
                        value -> value.getOperational().getFailoverPolicy().isFailoverOnAuthenticationFailure()); }
                if (contains(method, "FailoverOnCertificateVerificationFailure")) { return scalar("certificateFailure", "isFailoverOnCertificateVerificationFailure", false, DEFAULT_FAILOVER_ON_CERTIFICATE_VERIFICATION_FAILURE, null,
                        value -> value.getOperational().getFailoverPolicy().isFailoverOnCertificateVerificationFailure()); }
                break;
            case CONTENT:
                return scalar("legacySmtpContentSupport", "getLegacySmtpContentSupport", false, DEFAULT_LEGACY_SMTP_CONTENT_SUPPORT, true,
                        value -> value.getOperational().isLegacySmtpContentSupportEnabled());
            default: throw new AssertionError(family);
        }
        throw new AssertionError("No explicit member contract for " + family + " " + method);
    }

    private static Object seed(final Family family, final String field) {
        switch (family) {
            case POOL: return ConnectionPoolConfig.builder().withCoreSize(3).withMaxSize(9).withClaimTimeout(Duration.ofSeconds(7))
                    .withExpireAfter(Duration.ofSeconds(8)).withExpireAfterCreation(Duration.ofSeconds(9)).withClusterKey(CONFIGURED_CLUSTER)
                    .withLoadBalancingStrategy(LoadBalancingStrategy.ROUND_ROBIN);
            case CLUSTER: return ConnectionPoolClusterConfig.builder().coreSize(3).maxSize(9).claimTimeout(Duration.ofSeconds(7))
                    .expireAfter(Duration.ofSeconds(8)).expireAfterCreation(Duration.ofSeconds(9)).loadBalancingStrategy(LoadBalancingStrategy.ROUND_ROBIN);
            case ASYNC:
                final AsyncExecutionConfig.Builder async = AsyncExecutionConfig.builder().withThreadPoolSize(6).withKeepAliveTime(Duration.ofMillis(500))
                        .withQueueCapacity(13).waitForCapacity(Duration.ofMillis(400));
                return field.equals("executorService") ? async.withExecutorService(EXECUTOR) : async;
            case DEBUG:
                final SessionDebugConfig.Builder debug = SessionDebugConfig.builder().withEnabled(true).withOutput(SessionDebugOutput.STDERR);
                return field.equals("printer") ? debug.withPrinter(PRINTER) : debug;
            case TELEMETRY: return MailSendTelemetryConfig.builder().withEnabled(true).withSmtpEndpointAttributes(true).withIntegration(TELEMETRY);
            case VALIDATION: return ValidationConfig.builder().withClientValidationWarningsOnly(true).withEmailValidator(VALIDATOR).withMaximumEmailSize(2048);
            case LIMITS: return SendingLimitsConfig.builder().withMessageRateLimit(7, Duration.ofSeconds(3)).withRecipientRateLimit(9, Duration.ofSeconds(4))
                    .withRateLimitGroup("seed").withRateLimitBurstsAllowed(true);
            case RETRY: return MailSendRetryPolicy.builder().withMaximumAttempts(7).withRetryDelay(Duration.ofMillis(200)).withRetryBackoff(3, Duration.ofSeconds(8))
                    .withRetryJitter(0.5).withWaitingCapacity(23).withMaximumMessageSize(2L * 1024 * 1024).withMemoryLimit(128L * 1024 * 1024);
            case FAILOVER:
                final FailoverPolicy.Builder failover = FailoverPolicy.builder().withPrimaryConnectionAttempts(7).withPrimaryConnectionRetryDelay(Duration.ofMillis(100))
                        .withPrimaryRecheckInterval(Duration.ofSeconds(2)).withFailoverOnAuthenticationFailure(true).withFailoverOnCertificateVerificationFailure(true);
                return field.equals("primaryRecheckBackoff") ? failover.withPrimaryRecheckBackoff(3, Duration.ofSeconds(8)) : failover;
            case CONTENT: return ConnectionConfig.builder().withLegacySmtpContentSupport(true);
            default: throw new AssertionError(family);
        }
    }

    private static Object[] arguments(final Family family, final Field field, final Method method) {
        if (method.getParameterCount() == 0) { return new Object[0]; }
        if (family == Family.ASYNC && field.name.equals("capacityPolicy")) { return new Object[] {Duration.ofSeconds(2)}; }
        if (method.getParameterCount() == 2) {
            if (field.suppliedChoice instanceof SendingRateLimit) {
                final SendingRateLimit rate = (SendingRateLimit) field.suppliedChoice;
                return new Object[] {rate.getCount(), rate.getPeriod()};
            }
            return ((List<?>) field.suppliedChoice).toArray();
        }
        return new Object[] {field.suppliedChoice};
    }

    private static String fieldId(final Family family, final Field field) {
        return family == Family.CONTENT ? "mailer.legacySmtpContentSupport" : "mailer." + family.id + "." + field.name;
    }

    private static void assertRemovalState(final Family family, final Field field, final Object draft,
            final boolean inherit, final boolean clear) throws Exception {
        if (family == Family.ASYNC && field.name.equals("executorService") && (inherit || clear)) {
            final AsyncExecutionConfig value = ((AsyncExecutionConfig.Builder) draft).build();
            assertThat(value.isExecutorServiceCleared()).isEqualTo(clear);
        }
        if (family == Family.LIMITS && (inherit || clear) && !field.name.equals("rateLimitBurstsAllowed")) {
            final SendingLimitsConfig value = ((SendingLimitsConfig.Builder) draft).build();
            final boolean removed = field.name.equals("messageRateLimit") ? value.isMessageRateLimitCleared()
                    : field.name.equals("recipientRateLimit") ? value.isRecipientRateLimitCleared() : value.isRateLimitGroupCleared();
            assertThat(removed).isEqualTo(clear);
        }
        if (family == Family.VALIDATION && (inherit || clear) && !field.name.equals("clientValidationWarningsOnly")) {
            final ValidationConfig value = ((ValidationConfig.Builder) draft).build();
            assertThat(field.name.equals("emailValidator") ? value.isEmailValidatorCleared() : value.isMaximumEmailSizeCleared()).isEqualTo(clear);
        }
    }

    private static List<String> properties(final Family family, final String field) {
        final String property;
        switch (family) {
            case POOL:
                switch (field) {
                    case "coreSize": property = "defaults.connectionpool.coresize"; break;
                    case "maxSize": property = "defaults.connectionpool.maxsize"; break;
                    case "claimTimeout": property = "defaults.connectionpool.claimtimeout.millis"; break;
                    case "expireAfter": property = "defaults.connectionpool.expireafter.millis"; break;
                    case "expireAfterCreation": property = "defaults.connectionpool.expireaftercreation.millis"; break;
                    case "clusterKey": property = "defaults.connectionpool.clusterkey.uuid"; break;
                    case "loadBalancingStrategy": return List.of(); // This fixture uses the built-in fallback, not an invented source.
                    default: throw new AssertionError(field);
                }
                break;
            case ASYNC:
                switch (field) {
                    case "threadPoolSize": property = "defaults.poolsize"; break;
                    case "keepAliveTime": property = "defaults.poolsize.keepalivetime"; break;
                    case "queueCapacity": property = "defaults.async.queue.capacity"; break;
                    case "capacityPolicy": return List.of("simplejavamail.defaults.async.queue.overflowpolicy", "simplejavamail.defaults.async.queue.waittimeoutmillis");
                    case "executorService": return List.of();
                    default: throw new AssertionError(field);
                }
                break;
            case DEBUG:
                if (field.equals("printer")) { return List.of(); }
                property = field.equals("enabled") ? "javaxmail.debug" : "javaxmail.debug.out";
                break;
            case TELEMETRY:
                if (field.equals("integration")) { return List.of(); }
                property = field.equals("enabled") ? "telemetry.enabled" : "telemetry.smtp.endpointattributes";
                break;
            case VALIDATION:
                if (!field.equals("clientValidationWarningsOnly")) { return List.of(); }
                property = "disable.all.clientvalidation";
                break;
            case LIMITS:
                if (field.equals("messageRateLimit")) { return List.of("simplejavamail.smtp.ratelimit.messages.limit", "simplejavamail.smtp.ratelimit.messages.period"); }
                if (field.equals("recipientRateLimit")) { return List.of("simplejavamail.smtp.ratelimit.recipients.limit", "simplejavamail.smtp.ratelimit.recipients.period"); }
                property = field.equals("rateLimitGroup") ? "smtp.ratelimit.group" : "smtp.ratelimit.allowbursts";
                break;
            case CONTENT: property = "smtp.legacycontentsupport"; break;
            default: return List.of();
        }
        return List.of("simplejavamail." + property);
    }

    private static Field scalar(final String name, final String getter, final Object supplied, final Object reset,
            final Object inherited, final Function<ResolvedMailerConfiguration, Object> read) {
        return new Field(name, getter, supplied, reset, inherited, read, supplied, reset);
    }

    private static final class Field {
        final String name;
        final String getter;
        final Object suppliedChoice;
        final Object resetChoice;
        final Object inherited;
        final Function<ResolvedMailerConfiguration, Object> readEffective;
        final Object suppliedEffective;
        final Object resetEffective;
        Object clearedChoice;
        Object cleared;

        Field(final String name, final String getter, final Object suppliedChoice, final Object resetChoice, final Object inherited,
                final Function<ResolvedMailerConfiguration, Object> readEffective, final Object suppliedEffective, final Object resetEffective) {
            this.name = name; this.getter = getter; this.suppliedChoice = suppliedChoice; this.resetChoice = resetChoice;
            this.inherited = inherited; this.readEffective = readEffective; this.suppliedEffective = suppliedEffective; this.resetEffective = resetEffective;
        }

        Field clearChoice(final Object value) { clearedChoice = value; return this; }
        Field clearEffective(final Object value) { cleared = value; return this; }

        Object readChoice(final Object value) throws Exception {
            if (getter != null) { return value.getClass().getMethod(getter).invoke(value); }
            if (name.equals("capacityPolicy")) { return list(read(value, "getRejectWhenFull"), read(value, "getCapacityWait")); }
            if (name.equals("retryBackoff")) { return list(read(value, "getRetryBackoffMultiplier"), read(value, "getMaximumRetryDelay")); }
            if (name.equals("primaryRecheckBackoff")) { return list(read(value, "getPrimaryRecheckBackoffMultiplier"), read(value, "getPrimaryMaximumRecheckInterval")); }
            if (name.equals("schedule")) { return list(read(value, "getMaximumAttempts"), read(value, "getRetryDelay"), read(value, "getRetryBackoffMultiplier"),
                    read(value, "getMaximumRetryDelay"), read(value, "getRetryJitter")); }
            throw new AssertionError(name);
        }
    }

    private static Object read(final Object object, final String getter) throws Exception { return object.getClass().getMethod(getter).invoke(object); }
    private static boolean contains(final String method, final String member) { return method.toLowerCase(java.util.Locale.ROOT).contains(member.toLowerCase(java.util.Locale.ROOT)); }
    private static Object normalize(final Object value) { return value instanceof EmailValidator && value != VALIDATOR ? value.getClass() : value; }
    private static List<Object> list(final Object... values) { return Arrays.asList(values); }
    private static List<Object> queuePolicy(final AsyncQueueConfig value) { return list(value.getOverflowPolicy() == AsyncQueueOverflowPolicy.REJECT, value.getWaitTimeout()); }
    private static List<Object> retryBackoff(final MailSendRetryPolicy value) { return list(value.getRetryBackoffMultiplier(), value.getMaximumRetryDelay()); }
    private static List<Object> failoverBackoff(final FailoverPolicy value) { return list(value.getPrimaryRecheckBackoffMultiplier(), value.getPrimaryMaximumRecheckInterval()); }
    private static List<Object> retrySchedule(final MailSendRetryPolicy value) { return list(value.getMaximumAttempts(), value.getRetryDelay(), value.getRetryBackoffMultiplier(), value.getMaximumRetryDelay(), value.getRetryJitter()); }

    private static MailerRegularBuilderImpl mailer() {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("simplejavamail.defaults.connectionpool.coresize", 1); values.put("simplejavamail.defaults.connectionpool.maxsize", 7);
        values.put("simplejavamail.defaults.connectionpool.claimtimeout.millis", 200); values.put("simplejavamail.defaults.connectionpool.expireafter.millis", 400);
        values.put("simplejavamail.defaults.connectionpool.expireaftercreation.millis", 600); values.put("simplejavamail.defaults.connectionpool.clusterkey.uuid", CONFIGURED_CLUSTER.toString());
        values.put("simplejavamail.defaults.poolsize", 3); values.put("simplejavamail.defaults.poolsize.keepalivetime", 10);
        values.put("simplejavamail.defaults.async.queue.capacity", 11); values.put("simplejavamail.defaults.async.queue.overflowpolicy", "WAIT_FOR_CAPACITY");
        values.put("simplejavamail.defaults.async.queue.waittimeoutmillis", 300); values.put("simplejavamail.defaults.mailsend.timeout", "PT4S");
        values.put("simplejavamail.javaxmail.debug", true); values.put("simplejavamail.javaxmail.debug.out", "STDERR");
        values.put("simplejavamail.telemetry.enabled", true); values.put("simplejavamail.telemetry.smtp.endpointattributes", true);
        values.put("simplejavamail.disable.all.clientvalidation", true); values.put("simplejavamail.smtp.legacycontentsupport", true);
        values.put("simplejavamail.smtp.ratelimit.messages.limit", 3); values.put("simplejavamail.smtp.ratelimit.messages.period", "PT1S");
        values.put("simplejavamail.smtp.ratelimit.recipients.limit", 5); values.put("simplejavamail.smtp.ratelimit.recipients.period", "PT2S");
        values.put("simplejavamail.smtp.ratelimit.group", "configured"); values.put("simplejavamail.smtp.ratelimit.allowbursts", false);
        values.put("simplejavamail.smtp.retry.attempts", 3); values.put("simplejavamail.smtp.retry.delay", "PT0.1S");
        values.put("simplejavamail.smtp.retry.backoffmultiplier", 2); values.put("simplejavamail.smtp.retry.maximumdelay", "PT5S");
        values.put("simplejavamail.smtp.retry.jitter", 0.1); values.put("simplejavamail.defaults.mailsend.retry.waitingcapacity", 8);
        values.put("simplejavamail.defaults.mailsend.retry.maximummessagesize", 4L * 1024 * 1024);
        values.put("simplejavamail.defaults.mailsend.retry.memorylimit", 64L * 1024 * 1024);
        return new MailerRegularBuilderImpl(ConfigLoader.builder().withMap("wiring fixture", values).load()).withSMTPServerHost("relay.example.test");
    }
}
