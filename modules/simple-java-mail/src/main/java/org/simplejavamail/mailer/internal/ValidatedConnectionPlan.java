package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.ServerConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;

import java.util.Properties;

import static org.simplejavamail.internal.config.ConfigurationSnapshot.locksOf;

/** Only successful connection validation can create the plan accepted by Mailer construction. */
final class ValidatedConnectionPlan {
    @Nullable private final Session targetSession;
    private final Properties additionalProperties;
    private final MailerSessionSettings settings;
    @Nullable private final ServerConfig serverConfig;
    private final boolean directAuthenticatedSocks;

    private ValidatedConnectionPlan(@Nullable final Session targetSession, final Properties additionalProperties, final MailerSessionSettings settings,
            @Nullable final ServerConfig serverConfig, final boolean directAuthenticatedSocks) {
        this.targetSession = targetSession;
        this.additionalProperties = SessionPropertyObservation.snapshot(additionalProperties);
        this.settings = settings;
        this.serverConfig = serverConfig;
        this.directAuthenticatedSocks = directAuthenticatedSocks;
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
        final boolean direct = directAuthenticatedSocks(inputs.getTargetSession(), settings.copyEffectiveProperties(), resolved.proxy,
                inputs.isSmtpActive());
        final LockedSmtpConnectionRequirements requirements = new LockedSmtpConnectionRequirements(
                inputs.getConfig(), resolved.proxy, settings.isTrustAllSSLHost(), inputs.getStrategy());
        requirements.verifyAdditionalProperties(inputs.copyAdditionalProperties());
        requirements.verifyExplicitConnectionChoices(resolved.explicitOverrides);
        if (inputs.getSuppliedSession() != null) {
            requirements.verifyCallerOwnedSession(inputs.getSuppliedSession());
        }
        LockedSmtpConnectionRequirements.verifyCustomSocketFactory(locksOf(inputs.getConfig()), settings.copyEffectiveProperties(), inputs.getStrategy());
        if (inputs.isSmtpActive()) {
            final TransportStrategy authenticationStrategy = inputs.getSuppliedSession() == null ? inputs.getStrategy()
                    : TransportStrategy.findStrategyForSession(inputs.getSuppliedSession());
            OAuth2AccessTokenResolver.validateConfiguration(Session.getInstance(settings.copyEffectiveProperties()), new Properties(),
                    authenticationStrategy, inputs.getTokenProvider());
            if (resolved.proxy.requiresProxy() && resolved.proxy.requiresAuthentication()) {
                ModuleLoader.loadAuthenticatedSocksModule();
            }
        }
        return new ValidatedConnectionPlan(inputs.getTargetSession(), inputs.copyAdditionalProperties(), settings, serverConfig, direct);
    }

    /** Internal injection constructors supply completed facts, without inventing a builder declaration history. */
    static ValidatedConnectionPlan validateApplication(final SimpleJavaMailConfig config, @Nullable final ServerConfig server,
            final ProxyConfig proxy, final Session baseline, final OperationalConfig operational,
            final MailerSessionSettings settings, final boolean ownsSession) {
        final TransportStrategy strategy = settings.getTransportStrategy();
        final boolean direct = directAuthenticatedSocks(ownsSession ? null : baseline, settings.finalProperties(baseline, operational.getProperties()),
                proxy, operational.getCustomMailer() == null);
        final LockedSmtpConnectionRequirements requirements = new LockedSmtpConnectionRequirements(config, proxy,
                operational.isTrustAllSSLHost(), strategy);
        requirements.verifyAdditionalProperties(operational.getProperties());
        if (!ownsSession) { requirements.verifyCallerOwnedSession(baseline); }
        final Properties effective = settings.finalProperties(baseline, operational.getProperties());
        LockedSmtpConnectionRequirements.verifyCustomSocketFactory(locksOf(config), effective, strategy);
        if (operational.getCustomMailer() == null) {
            if (ownsSession) {
                MailerImpl.validateMandatoryStartTls(strategy, operational.getProperties());
                MailerImpl.validateImplicitTlsOAuth2Protocol(strategy, operational.getProperties());
            }
            OAuth2AccessTokenResolver.validateConfiguration(Session.getInstance(effective), new Properties(),
                    ownsSession ? strategy : TransportStrategy.findStrategyForSession(baseline), operational.getOAuth2AccessTokenProvider());
            if (proxy.requiresProxy() && proxy.requiresAuthentication()) { ModuleLoader.loadAuthenticatedSocksModule(); }
        }
        return new ValidatedConnectionPlan(ownsSession ? null : baseline, operational.getProperties(), settings, server, direct);
    }

    private static boolean directAuthenticatedSocks(@Nullable final Session supplied, final Properties effective, final ProxyConfig proxy,
            final boolean smtpActive) {
        if (!smtpActive || !proxy.requiresProxy() || !proxy.requiresAuthentication()) {
            return false;
        }
        if (!MailTransportLifecycleResolver.supportsDirectAuthenticatedSocks(supplied == null ? Session.getInstance(effective) : supplied, effective)) {
            throw new IllegalArgumentException("Authenticated SOCKS requires an Angus mail provider with transport socket support. "
                    + "Use a compatible Angus version and its lifecycle adapter; this provider cannot authenticate the configured SOCKS proxy.");
        }
        return true;
    }

    MailerSessionSettings settings() { return settings; }
    @Nullable Session targetSession() { return targetSession; }
    @Nullable ServerConfig serverConfig() { return serverConfig; }
    boolean usesDirectAuthenticatedSocks() { return directAuthenticatedSocks; }
    Properties additionalProperties() { return SessionPropertyObservation.snapshot(additionalProperties); }
}
