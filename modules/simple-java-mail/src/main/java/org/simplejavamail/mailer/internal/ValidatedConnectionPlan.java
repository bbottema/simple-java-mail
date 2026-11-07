package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import lombok.AccessLevel;
import lombok.Builder;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.ServerConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.SessionDebugConfig;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.util.Properties;
import java.util.Map;
import java.util.function.Supplier;

import static org.simplejavamail.internal.config.ConfigurationSnapshot.locksOf;

/** Only successful connection validation can create the plan accepted by Mailer construction. */
@Builder(access = AccessLevel.PRIVATE)
final class ValidatedConnectionPlan {
    @Nullable private final Session targetSession;
    private final MailerSessionSettings settings;
    @Nullable private final ServerConfig serverConfig;
    private final SessionConfigurationChanges changes;
    private final Properties baseline;
    private final SessionDebugConfig debugBaseline;

    static ValidatedConnectionPlan validate(final ResolvedConnection resolved, final MailerSessionSettings settings,
            @Nullable final MailerRegularBuilderImpl regularBuilder) {
        ConnectionConfigurationResolver.validateDeclarations(resolved);
        return validateRequirements(resolved, settings, regularBuilder == null ? null : regularBuilder.buildServerConfig(settings));
    }

    static ValidatedConnectionPlan validateResolved(final ResolvedConnection resolved, final MailerSessionSettings settings,
            final CapturedMailerInputs inputs) {
        ConnectionConfigurationResolver.validateDeclarations(resolved);
        return validateRequirements(resolved, settings, ResolvedMailerConfiguration.resolveServer(inputs, settings));
    }

    private static ValidatedConnectionPlan validateRequirements(final ResolvedConnection resolved, final MailerSessionSettings settings,
            @Nullable final ServerConfig serverConfig) {
        final CapturedConnectionInputs inputs = resolved.inputs;
        final LockedSmtpConnectionRequirements requirements = new LockedSmtpConnectionRequirements(
                inputs.getConfig(), resolved.proxy, settings.isTrustAllSSLHost(), inputs.getStrategy());
        requirements.verifyAdditionalProperties(inputs.copyAdditionalProperties());
        requirements.verifyExplicitConnectionChoices(resolved.explicitOverrides);
        if (inputs.getTargetSession() != null) {
            requirements.verifyCallerOwnedSession(inputs.copySessionProperties(), settings.getTransportStrategy());
            verifyConstructionOnlySettings(inputs.copySessionProperties(), settings.copyEffectiveProperties());
        }
        LockedSmtpConnectionRequirements.verifyCustomSocketFactory(locksOf(inputs.getConfig()), settings.copyEffectiveProperties(), inputs.getStrategy());
        Supplier<String> provider = null;
        if (inputs.isSmtpActive()) {
            final TransportStrategy authenticationStrategy = inputs.getTargetSession() == null ? inputs.getStrategy() : null;
            provider = OAuth2AccessTokenResolver.validateConfiguration(settings.copyEffectiveProperties(), new Properties(),
                    authenticationStrategy, inputs.getTokenProvider());
            if (resolved.proxy.requiresProxy() && resolved.proxy.requiresAuthentication()) {
                ModuleLoader.loadAuthenticatedSocksModule();
            }
        }
        return create(inputs.getTargetSession(), inputs.copyAdditionalProperties(), settings, serverConfig, resolved.proxy,
                provider, inputs.copySessionProperties(), inputs.getDebugBaseline());
    }

    /** Internal injection constructors supply completed facts, without inventing a builder declaration history. */
    static ValidatedConnectionPlan validateApplication(final SimpleJavaMailConfig config, @Nullable final ServerConfig server,
            final ProxyConfig proxy, @Nullable final Session targetSession, final Properties baseline, final OperationalConfig operational,
            final MailerSessionSettings settings, final boolean ownsSession) {
        final TransportStrategy strategy = settings.getTransportStrategy();
        final LockedSmtpConnectionRequirements requirements = new LockedSmtpConnectionRequirements(config, proxy,
                operational.isTrustAllSSLHost(), strategy);
        requirements.verifyAdditionalProperties(operational.getProperties());
        if (!ownsSession) { requirements.verifyCallerOwnedSession(baseline, strategy); }
        final Properties effective = settings.finalProperties(baseline, operational.getProperties());
        if (!ownsSession) { verifyConstructionOnlySettings(baseline, effective); }
        LockedSmtpConnectionRequirements.verifyCustomSocketFactory(locksOf(config), effective, strategy);
        Supplier<String> provider = null;
        if (operational.getCustomMailer() == null) {
            if (ownsSession) {
                MailerImpl.validateMandatoryStartTls(strategy, operational.getProperties());
                MailerImpl.validateImplicitTlsOAuth2Protocol(strategy, operational.getProperties());
            }
            provider = OAuth2AccessTokenResolver.validateConfiguration(effective, new Properties(),
                    ownsSession ? strategy : null, operational.getOAuth2AccessTokenProvider());
            if (proxy.requiresProxy() && proxy.requiresAuthentication()) { ModuleLoader.loadAuthenticatedSocksModule(); }
        }
        return create(targetSession, operational.getProperties(), settings, server, proxy, provider, baseline,
                MailerSessionSettings.debugBaseline(targetSession));
    }

    private static ValidatedConnectionPlan create(@Nullable final Session target, final Properties additional,
            final MailerSessionSettings settings, @Nullable final ServerConfig server, final ProxyConfig proxy,
            @Nullable final Supplier<String> provider, final Properties baseline, final SessionDebugConfig debug) {
        return ValidatedConnectionPlan.builder().targetSession(target).settings(settings).serverConfig(server)
                .changes(SessionConfigurationChanges.describe(settings, additional, proxy, provider))
                .baseline(baseline).debugBaseline(debug).build();
    }

    /** Refuse a stale candidate without re-resolving choices or comparing opaque application values. */
    void verifyClaimedBaseline(final Session session) {
        if (targetSession == null) { return; }
        final Properties current = session.getProperties();
        for (Map.Entry<Object, Object> entry : baseline.entrySet()) {
            if (!MailerImpl.SESSION_OWNER.equals(entry.getKey()) && (!current.containsKey(entry.getKey())
                    || !sameValue(entry.getValue(), current.get(entry.getKey())))) { changedBaseline(); }
        }
        for (Object key : current.keySet()) {
            if (!MailerImpl.SESSION_OWNER.equals(key) && !baseline.containsKey(key)) { changedBaseline(); }
        }
        for (String key : baseline.stringPropertyNames()) {
            if (!MailerImpl.SESSION_OWNER.equals(key) && !sameValue(baseline.getProperty(key), current.getProperty(key))) { changedBaseline(); }
        }
        for (String key : current.stringPropertyNames()) {
            if (!MailerImpl.SESSION_OWNER.equals(key) && !sameValue(baseline.getProperty(key), current.getProperty(key))) { changedBaseline(); }
        }
        if (session.getDebug() != Boolean.TRUE.equals(debugBaseline.getEnabled())
                || session.getDebugOut() != debugBaseline.getPrinter()) { changedBaseline(); }
    }

    private static boolean sameValue(@Nullable final Object expected, @Nullable final Object current) {
        return expected == current || expected instanceof String && current instanceof String && expected.equals(current);
    }

    private static void changedBaseline() {
        throw new IllegalArgumentException("The supplied Session changed while this Mailer was being built. "
                + "No configuration was applied; retry the build after the previous change has finished.");
    }

    /** Jakarta Mail creates this queue in Session's constructor, but service-scoped queues are created later. */
    private static void verifyConstructionOnlySettings(final Properties baseline, final Properties effective) {
        if ("session".equalsIgnoreCase(effective.getProperty("mail.event.scope"))
                && baseline.get("mail.event.executor") != effective.get("mail.event.executor")) {
            throw new IllegalArgumentException("Cannot change mail.event.executor for Session-scoped events after the supplied Session was created. "
                    + "Configure mail.event.executor on the Properties passed to Session.getInstance(...), or let Simple Java Mail create the Session. "
                    + "ALLOW_EXPLICIT_OVERRIDES cannot replace an existing Session event queue.");
        }
    }

    MailerSessionSettings settings() { return settings; }
    @Nullable Session targetSession() { return targetSession; }
    @Nullable ServerConfig serverConfig() { return serverConfig; }
    SessionConfigurationChanges changes() { return changes; }
}
