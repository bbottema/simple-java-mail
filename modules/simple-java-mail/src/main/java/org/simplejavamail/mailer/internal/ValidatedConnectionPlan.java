package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.ServerConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.util.Properties;

import static org.simplejavamail.internal.config.ConfigurationSnapshot.locksOf;

/** Only successful connection validation can create the plan accepted by Mailer construction. */
final class ValidatedConnectionPlan {
    @Nullable private final Session targetSession;
    private final Properties additionalProperties;
    private final MailerSessionSettings settings;
    @Nullable private final ServerConfig serverConfig;

    private ValidatedConnectionPlan(@Nullable final Session targetSession, final Properties additionalProperties, final MailerSessionSettings settings,
            @Nullable final ServerConfig serverConfig) {
        this.targetSession = targetSession;
        this.additionalProperties = SessionPropertyObservation.snapshot(additionalProperties);
        this.settings = settings;
        this.serverConfig = serverConfig;
    }

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
        if (inputs.isSmtpActive()) {
            final TransportStrategy authenticationStrategy = inputs.getTargetSession() == null ? inputs.getStrategy() : null;
            OAuth2AccessTokenResolver.validateConfiguration(settings.copyEffectiveProperties(), new Properties(),
                    authenticationStrategy, inputs.getTokenProvider());
            if (resolved.proxy.requiresProxy() && resolved.proxy.requiresAuthentication()) {
                ModuleLoader.loadAuthenticatedSocksModule();
            }
        }
        return new ValidatedConnectionPlan(inputs.getTargetSession(), inputs.copyAdditionalProperties(), settings, serverConfig);
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
        if (operational.getCustomMailer() == null) {
            if (ownsSession) {
                MailerImpl.validateMandatoryStartTls(strategy, operational.getProperties());
                MailerImpl.validateImplicitTlsOAuth2Protocol(strategy, operational.getProperties());
            }
            OAuth2AccessTokenResolver.validateConfiguration(effective, new Properties(),
                    ownsSession ? strategy : null, operational.getOAuth2AccessTokenProvider());
            if (proxy.requiresProxy() && proxy.requiresAuthentication()) { ModuleLoader.loadAuthenticatedSocksModule(); }
        }
        return new ValidatedConnectionPlan(targetSession, operational.getProperties(), settings, server);
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
    Properties additionalProperties() { return SessionPropertyObservation.snapshot(additionalProperties); }
}
