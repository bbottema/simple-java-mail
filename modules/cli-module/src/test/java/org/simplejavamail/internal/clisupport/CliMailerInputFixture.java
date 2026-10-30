package org.simplejavamail.internal.clisupport;

import com.sanctionco.jmail.EmailValidator;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.internal.clisupport.CliFailoverMailerBuilder;
import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import org.simplejavamail.api.mailer.config.SessionDebugConfig;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.mailer.internal.MailerRegularBuilderImpl;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Independent Mailer readbacks after the real CLI parser, conversion and result-handler binding have completed. */
final class CliMailerInputFixture {
    private static final Map<Object, UUID> CAPTURED_CLUSTER_KEYS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final UUID SEED_CLUSTER = UUID.fromString("10000000-0000-0000-0000-000000000001");

    private CliMailerInputFixture() { }

    static Object startingBuilder(final SimpleJavaMail mail, final String optionName) {
        final String option = optionName.substring(optionName.indexOf(':') + 1);
        final MailerRegularBuilderImpl builder = (MailerRegularBuilderImpl) mail.mailerBuilder();
        CAPTURED_CLUSTER_KEYS.put(builder, builder.getClusterKey());
        builder.withSMTPServer("before.example.test", 1999, "before-user", "before-password")
                .withTransportModeLoggingOnly(!option.equals("withTransportModeLoggingOnly"))
                .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                .withConnectionConfig(ConnectionConfig.builder().withSessionTimeout(Duration.ofSeconds(9))
                        .withLocalBindAddress("127.0.0.2", 99).withSmtpClientHostname("before-client").build())
                .withProxyConfig(SocksProxyConfig.builder().withHost("before-proxy").withPort(1099)
                        .withUsername("before-proxy-user").withPassword("before-proxy-password").withBridgePort(1090).build())
                .withTlsConfig(TlsConfig.builder().trustingSSLHosts("before-trust").trustingAllHosts(option.equals("resetTrustingAllHosts"))
                        .verifyingServerIdentity(false).withOpportunisticTLS(false).withCustomSSLFactoryClass("before-class").build())
                .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withThreadPoolSize(7).withKeepAliveTime(Duration.ofSeconds(2))
                        .withQueueCapacity(17).waitForCapacity(Duration.ofSeconds(3)).withExecutorService(mock(ExecutorService.class)).build())
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(2).withMaxSize(8).withClaimTimeout(Duration.ofSeconds(2))
                        .withExpireAfter(Duration.ofSeconds(10)).withExpireAfterCreation(Duration.ofSeconds(12)).withClusterKey(SEED_CLUSTER)
                        .withLoadBalancingStrategy(option.equals("resetConnectionPoolLoadBalancingStrategy")
                                ? LoadBalancingStrategy.RANDOM_ACCESS : LoadBalancingStrategy.ROUND_ROBIN).build())
                .withMailSendTimeout(Duration.ofSeconds(9)).withMaximumEmailSize(9999)
                .withMessageRateLimit(7, Duration.ofSeconds(3)).withRecipientRateLimit(8, Duration.ofSeconds(4))
                .withRateLimitGroup("before-group").withRateLimitBurstsAllowed(false)
                .withEmailDefaults(mail.emailBuilder().startingBlank().withSubject("before-default").buildEmail())
                .withEmailOverrides(mail.emailBuilder().startingBlank().withSubject("before-override").buildEmail())
                .withEmailValidator(mock(EmailValidator.class))
                .withDebugConfig(SessionDebugConfig.builder().withEnabled(false).withOutput(SessionDebugOutput.STDOUT).build())
                .withTelemetryConfig(MailSendTelemetryConfig.builder().withEnabled(option.equals("resetMailSendTelemetryEnabled"))
                        .withSmtpEndpointAttributes(option.equals("resetMailSendTelemetrySmtpEndpointAttributes")).build())
                .withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumAttempts(7).withRetryDelay(Duration.ofSeconds(2))
                        .withRetryBackoff(3, Duration.ofSeconds(90)).withRetryJitter(0.5).withWaitingCapacity(13)
                        .withMaximumMessageSize(16L * 1024 * 1024).withMemoryLimit(1024L * 1024 * 1024).build());
        invoke(builder, "withAsyncQueueOverflowPolicy", option.equals("resetAsyncQueue") ? AsyncQueueOverflowPolicy.WAIT_FOR_CAPACITY : AsyncQueueOverflowPolicy.REJECT);
        invoke(builder, "disablingAllClientValidation", option.equals("resetDisableAllClientValidations"));
        invoke(builder, "withLegacySmtpContentSupport", option.equals("resetLegacySmtpContentSupport"));
        invoke(builder, "withProperty", "application.before", "before");
        if (option.equals("withConnectionConflictPolicy")) { builder.withConnectionConflictPolicy(ConnectionConflictPolicy.REJECT_CONFLICTS); }
        if (option.startsWith("withFailover")) {
            final CliFailoverMailerBuilder failover = (CliFailoverMailerBuilder) builder;
            if (!option.equals("withFailoverMailerName")) { failover.withFailoverMailerName("backup"); }
            failover.withFailoverPrimaryConnectionAttempts(7);
            failover.withFailoverPrimaryRetryDelay(Duration.ofSeconds(2));
            failover.withFailoverPrimaryRecheckInterval(Duration.ofSeconds(9));
            failover.withFailoverPrimaryRecheckBackoff(3, Duration.ofSeconds(90));
            failover.withFailoverOnAuthenticationFailure(false);
            failover.withFailoverOnCertificateVerificationFailure(false);
        }
        return builder;
    }

    static void assertBound(final String optionName, final Object actualBuilder, final List<Object> convertedArgs) {
        final String option = optionName.substring(optionName.indexOf(':') + 1);
        final UUID capturedCluster = CAPTURED_CLUSTER_KEYS.remove(actualBuilder);
        final Object first = convertedArgs.isEmpty() ? null : convertedArgs.get(0);
        final Object captured = read(actualBuilder, "captureMailerInputs");
        final Object operational = read(captured, "getOperational");
        final Object resolved = resolve(captured);
        final Object session = read(resolved, "getSessionSettings");
        switch (option) {
            case "withSMTPServer":
                equal(actualBuilder, "getHost", convertedArgs.get(0)); equal(actualBuilder, "getPort", convertedArgs.get(1));
                equal(actualBuilder, "getUsername", convertedArgs.get(2)); equal(actualBuilder, "getPassword", convertedArgs.get(3)); return;
            case "clearSMTPServerHost": equal(actualBuilder, "getHost", null); return;
            case "clearSMTPServerPort": equal(actualBuilder, "getPort", null); return;
            case "clearSMTPServerUsername": equal(actualBuilder, "getUsername", null); return;
            case "clearSMTPServerPassword": equal(actualBuilder, "getPassword", null); return;
            case "withTransportStrategy": equal(read(captured, "getConnection"), "getStrategy", first); return;
            case "withConnectionConflictPolicy": equal(read(captured, "getConnection"), "getPolicy", first); return;
            case "resetConnectionConflictPolicy": equal(read(captured, "getConnection"), "getPolicy", ConnectionConflictPolicy.REJECT_CONFLICTS); return;
            case "withSessionTimeout": timeouts(session, millis(first)); return;
            case "resetSessionTimeout": timeouts(session, Duration.ofSeconds(60)); return;
            case "withConnectTimeout": equal(session, "getConnectTimeout", millis(first)); return;
            case "withReadTimeout": equal(session, "getReadTimeout", millis(first)); return;
            case "withWriteTimeout": equal(session, "getWriteTimeout", millis(first)); return;
            case "resetConnectTimeout": equal(session, "getConnectTimeout", Duration.ofSeconds(60)); return;
            case "resetReadTimeout": equal(session, "getReadTimeout", Duration.ofSeconds(60)); return;
            case "resetWriteTimeout": equal(session, "getWriteTimeout", Duration.ofSeconds(60)); return;
            case "withLocalBindAddress":
                equal(session, "getLocalBindAddress", first); equal(session, "getLocalBindPort", convertedArgs.get(1)); return;
            case "clearLocalBindAddress": equal(session, "getLocalBindAddress", null); equal(session, "getLocalBindPort", null); return;
            case "withSmtpClientHostname": equal(session, "getSmtpClientHostname", first); return;
            case "clearSmtpClientHostname": equal(session, "getSmtpClientHostname", null); return;
            case "withProxy":
                final Object proxy = read(session, "getProxyConfig");
                equal(proxy, "getRemoteProxyHost", convertedArgs.get(0)); equal(proxy, "getRemoteProxyPort", convertedArgs.get(1));
                equal(proxy, "getUsername", convertedArgs.get(2)); equal(proxy, "getPassword", convertedArgs.get(3)); return;
            case "withProxyBridgePort": equal(read(session, "getProxyConfig"), "getProxyBridgePort", first); return;
            case "clearProxy":
                final Object clearedProxy = read(session, "getProxyConfig"); equal(clearedProxy, "getRemoteProxyHost", null);
                equal(clearedProxy, "getUsername", null); equal(clearedProxy, "getPassword", null); equal(clearedProxy, "getProxyBridgePort", 0); return;
            case "trustingSSLHosts": assertThat(read(session, "getSslHostsToTrust")).isEqualTo(Arrays.asList((String[]) first)); return;
            case "clearTrustedSSLHosts": assertThat(read(session, "getSslHostsToTrust")).isEqualTo(List.of()); return;
            case "trustingAllHosts": equal(session, "isTrustAllSSLHost", first); return;
            case "resetTrustingAllHosts": equal(session, "isTrustAllSSLHost", false); return;
            case "verifyingServerIdentity": equal(session, "getVerifyingServerIdentity", first); return;
            case "resetVerifyingServerIdentity": equal(session, "getVerifyingServerIdentity", true); return;
            case "withOpportunisticTLS": equal(captured, "isOpportunisticTLS", first); return;
            case "resetOpportunisticTLS": equal(captured, "isOpportunisticTLS", true); return;
            case "withCustomSSLFactoryClass": equal(actualBuilder, "getCustomSSLFactory", first); return;
            case "clearCustomSSLFactoryClass": equal(actualBuilder, "getCustomSSLFactory", null); return;
            case "withProperty": assertThat(((Properties) read(actualBuilder, "getProperties")).get(convertedArgs.get(0))).isEqualTo(convertedArgs.get(1)); return;
            case "clearProperties": assertThat((Properties) read(actualBuilder, "getProperties")).isEmpty(); return;
            case "withDebugLogging": equal(read(operational, "getDebugConfig"), "getEnabled", first); return;
            case "withDebugOutput": equal(read(operational, "getDebugConfig"), "getOutput", first); return;
            case "withMailSendTelemetryEnabled": equal(read(operational, "getTelemetryConfig"), "getEnabled", first); return;
            case "resetMailSendTelemetryEnabled": equal(read(operational, "getTelemetryConfig"), "getEnabled", false); return;
            case "withMailSendTelemetrySmtpEndpointAttributes": equal(read(operational, "getTelemetryConfig"), "getSmtpEndpointAttributes", first); return;
            case "resetMailSendTelemetrySmtpEndpointAttributes": equal(read(operational, "getTelemetryConfig"), "getSmtpEndpointAttributes", false); return;
            case "withThreadPoolSize": equal(operational, "getThreadPoolSize", first); return;
            case "resetThreadPoolSize": equal(operational, "getThreadPoolSize", 10); return;
            case "withThreadPoolKeepAliveTime": equal(operational, "getThreadPoolKeepAliveTime", millis(first)); return;
            case "resetThreadPoolKeepAliveTime": equal(operational, "getThreadPoolKeepAliveTime", Duration.ofMillis(1)); return;
            case "resetExecutorService": equal(operational, "getExecutorService", null); return;
            case "withAsyncQueueCapacity": equal(read(operational, "getAsyncQueueConfig"), "getCapacity", first); return;
            case "withAsyncQueueOverflowPolicy": equal(read(operational, "getAsyncQueueConfig"), "getOverflowPolicy", first); return;
            case "withAsyncQueueWaitTimeoutMillis": equal(read(operational, "getAsyncQueueConfig"), "getWaitTimeout", millis(first)); return;
            case "resetAsyncQueue":
                final Object queue = read(operational, "getAsyncQueueConfig"); equal(queue, "getCapacity", -1);
                equal(queue, "getOverflowPolicy", AsyncQueueOverflowPolicy.REJECT); equal(queue, "getWaitTimeout", Duration.ofSeconds(1)); return;
            case "withClusterKey": equal(operational, "getClusterKey", first); return;
            case "resetClusterKey":
                final UUID resetCluster = (UUID) read(operational, "getClusterKey");
                assertThat(resetCluster).isNotNull().isNotEqualTo(SEED_CLUSTER).isNotEqualTo(capturedCluster);
                assertThat(resetCluster.version()).isEqualTo(4); return;
            case "withConnectionPoolCoreSize": equal(operational, "getConnectionPoolCoreSize", first); return;
            case "resetConnectionPoolCoreSize": equal(operational, "getConnectionPoolCoreSize", 0); return;
            case "withConnectionPoolMaxSize": equal(operational, "getConnectionPoolMaxSize", first); return;
            case "resetConnectionPoolMaxSize": equal(operational, "getConnectionPoolMaxSize", 4); return;
            case "withConnectionPoolClaimTimeoutMillis": equal(operational, "getConnectionPoolClaimTimeout", millis(first)); return;
            case "resetConnectionPoolClaimTimeoutMillis": equal(operational, "getConnectionPoolClaimTimeout", Duration.ofMillis(Integer.MAX_VALUE)); return;
            case "withConnectionPoolExpireAfterMillis": equal(operational, "getConnectionPoolExpireAfter", millis(first)); return;
            case "resetConnectionPoolExpireAfterMillis": equal(operational, "getConnectionPoolExpireAfter", Duration.ofMillis(5000)); return;
            case "withConnectionPoolExpireAfterCreationMillis": equal(operational, "getConnectionPoolExpireAfterCreation", millis(first)); return;
            case "clearConnectionPoolExpireAfterCreationMillis": equal(operational, "getConnectionPoolExpireAfterCreation", null); return;
            case "withConnectionPoolLoadBalancingStrategy": equal(operational, "getConnectionPoolLoadBalancingStrategy", first); return;
            case "resetConnectionPoolLoadBalancingStrategy": equal(operational, "getConnectionPoolLoadBalancingStrategy", LoadBalancingStrategy.ROUND_ROBIN); return;
            case "withMailSendTimeout": equal(operational, "getMailSendTimeout", first); return;
            case "resetMailSendTimeout": equal(operational, "getMailSendTimeout", null); return;
            case "withMessageRateLimit": rate(read(operational, "getMessageRateLimit"), convertedArgs); return;
            case "resetMessageRateLimit": equal(operational, "getMessageRateLimit", null); return;
            case "withRecipientRateLimit": rate(read(operational, "getRecipientRateLimit"), convertedArgs); return;
            case "resetRecipientRateLimit": equal(operational, "getRecipientRateLimit", null); return;
            case "withRateLimitGroup": equal(operational, "getRateLimitGroup", first); return;
            case "resetRateLimitGroup": equal(operational, "getRateLimitGroup", null); return;
            case "withRateLimitBurstsAllowed": equal(operational, "isRateLimitBurstsAllowed", first); return;
            case "resetRateLimitBurstsAllowed": equal(operational, "isRateLimitBurstsAllowed", true); return;
            case "withTransportModeLoggingOnly": equal(operational, "isTransportModeLoggingOnly", first); return;
            case "resetTransportModeLoggingOnly": equal(operational, "isTransportModeLoggingOnly", false); return;
            case "withLegacySmtpContentSupport": equal(operational, "isLegacySmtpContentSupportEnabled", first); return;
            case "resetLegacySmtpContentSupport": equal(operational, "isLegacySmtpContentSupportEnabled", false); return;
            case "disablingAllClientValidation": equal(operational, "isClientValidationWarningsOnly", first); return;
            case "resetDisableAllClientValidations": equal(operational, "isClientValidationWarningsOnly", false); return;
            case "withMaximumEmailSize": equal(captured, "getMaximumEmailSize", first); return;
            case "clearMaximumEmailSize": equal(captured, "getMaximumEmailSize", null); return;
            case "clearEmailValidator": equal(captured, "getEmailValidator", null); return;
            case "resetEmailValidator":
                final EmailValidator validator = (EmailValidator) read(captured, "getEmailValidator");
                assertThat(validator.isValid("sender@example.org")).isTrue(); assertThat(validator.isValid("not an address")).isFalse(); return;
            case "clearEmailOverrides": equal(captured, "getEmailOverrides", null); return;
            case "clearEmailDefaults": equal(field(read(resolved, "getGovernance"), "emailDefaults"), "getSubject", "configured subject"); return;
            case "withoutEmailDefaults": equal(field(read(resolved, "getGovernance"), "emailDefaults"), "getSubject", null); return;
            default:
                if (option.contains("MailSendRetry")) { retry(option, read(operational, "getMailSendRetryPolicy"), convertedArgs); return; }
                if (option.startsWith("withFailover")) { failover(option, captured, read(operational, "getFailoverPolicy"), convertedArgs); return; }
                throw new AssertionError("Missing independent Mailer CLI readback for " + optionName);
        }
    }

    private static void retry(final String option, final Object policy, final List<Object> args) {
        final Object first = args.isEmpty() ? null : args.get(0);
        switch (option) {
            case "withMailSendRetryAttempts": equal(policy, "getMaximumAttempts", first); return;
            case "withMailSendRetryDelay": equal(policy, "getRetryDelay", first); return;
            case "withMailSendRetryBackoff": equal(policy, "getRetryBackoffMultiplier", first); equal(policy, "getMaximumRetryDelay", args.get(1)); return;
            case "withMailSendRetryJitter": equal(policy, "getRetryJitter", first); return;
            case "withMailSendRetryWaitingCapacity": equal(policy, "getWaitingCapacity", first); return;
            case "resetMailSendRetryWaitingCapacity": equal(policy, "getWaitingCapacity", 100); return;
            case "withMailSendRetryMaximumMessageSize": equal(policy, "getMaximumMessageSize", first); return;
            case "resetMailSendRetryMaximumMessageSize": equal(policy, "getMaximumMessageSize", 32L * 1024 * 1024); return;
            case "withMailSendRetryMemoryLimit": equal(policy, "getMemoryLimit", first); return;
            case "resetMailSendRetryMemoryLimit": equal(policy, "getMemoryLimit", 128L * 1024 * 1024); return;
            case "clearMailSendRetryPolicy": equal(policy, "getMaximumAttempts", null); equal(policy, "getRetryDelay", null); return;
            default: throw new AssertionError("Missing retry CLI readback " + option);
        }
    }

    private static void failover(final String option, final Object captured, final Object policy, final List<Object> args) {
        final Object first = args.get(0);
        switch (option) {
            case "withFailoverMailerName":
                final Object child = read(captured, "getNamedChild"); assertThat(child).isNotNull();
                assertThat(field(read(child, "getDefinition"), "name")).isEqualTo(first); return;
            case "withFailoverPrimaryConnectionAttempts": equal(policy, "getPrimaryConnectionAttempts", first); return;
            case "withFailoverPrimaryRetryDelay": equal(policy, "getPrimaryConnectionRetryDelay", first); return;
            case "withFailoverPrimaryRecheckInterval": equal(policy, "getPrimaryRecheckInterval", first); return;
            case "withFailoverPrimaryRecheckBackoff": equal(policy, "getPrimaryRecheckBackoffMultiplier", first); equal(policy, "getPrimaryMaximumRecheckInterval", args.get(1)); return;
            case "withFailoverOnAuthenticationFailure": equal(policy, "isFailoverOnAuthenticationFailure", first); return;
            case "withFailoverOnCertificateVerificationFailure": equal(policy, "isFailoverOnCertificateVerificationFailure", first); return;
            default: throw new AssertionError("Missing failover CLI readback " + option);
        }
    }

    private static void timeouts(final Object settings, final Duration expected) {
        equal(settings, "getConnectTimeout", expected); equal(settings, "getReadTimeout", expected); equal(settings, "getWriteTimeout", expected);
    }

    private static void rate(final Object rate, final List<Object> args) { equal(rate, "getCount", args.get(0)); equal(rate, "getPeriod", args.get(1)); }
    private static Duration millis(final Object number) { return Duration.ofMillis(((Number) number).longValue()); }
    private static void equal(final Object object, final String getter, final Object expected) { assertThat(read(object, getter)).as(getter).isEqualTo(expected); }

    private static Object resolve(final Object captured) {
        try {
            final Class<?> owner = Class.forName("org.simplejavamail.mailer.internal.ResolvedMailerConfiguration");
            final Method method = owner.getDeclaredMethod("resolve", captured.getClass());
            method.setAccessible(true); return method.invoke(null, captured);
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Unable to inspect completed Mailer values", failure); }
    }

    private static Object read(final Object object, final String name) { return invoke(object, name); }

    private static Object invoke(final Object object, final String name, final Object... args) {
        try {
            for (Class<?> owner = object.getClass(); owner != null; owner = owner.getSuperclass()) {
                for (Method method : owner.getDeclaredMethods()) {
                    if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                        method.setAccessible(true); return method.invoke(object, args);
                    }
                }
            }
            throw new NoSuchMethodException(name);
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Unable to inspect bound Mailer option " + name, failure); }
    }

    private static Object field(final Object object, final String name) {
        try { final var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Unable to inspect captured definition", failure); }
    }
}
