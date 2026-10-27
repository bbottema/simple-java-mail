package org.simplejavamail.mailer.internal;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.ServerConfig;
import org.simplejavamail.api.mailer.config.SessionDebugConfig;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigKey;
import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.email.internal.EmailStartingBuilderImpl;
import org.simplejavamail.internal.configresolution.ConfigurationChoice;
import org.simplejavamail.internal.configresolution.ConfigurationDeclaration;
import org.simplejavamail.internal.configresolution.ConfigurationOrigin;
import org.simplejavamail.internal.configresolution.ConfigurationResolver;
import org.simplejavamail.internal.configresolution.ConfigurationSetting;

import javax.net.ssl.SSLSocketFactory;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;
import static org.simplejavamail.internal.config.ConfigurationSnapshot.locksOf;
import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

/** Completed node values retain their captured inputs and declarations until whole-graph validation succeeds. */
@Getter(AccessLevel.PACKAGE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
final class ResolvedMailerConfiguration {
    private final CapturedMailerInputs inputs;
    private final ResolvedConnection connection;
    private final MailerSessionSettings sessionSettings;
    private final MailerOperationalSettings operational;
    private final EmailGovernance governance;
    @Nullable private final ResolvedMailerConfiguration namedChild;

    static ResolvedMailerConfiguration resolve(final CapturedMailerInputs inputs) {
        final CapturedConnectionInputs captured = inputs.getConnection();
        final ResolvedConnection connection = ConnectionConfigurationResolver.resolve(captured);
        final MailerSessionSettings settings = MailerSessionSettings.resolve(captured.getSuppliedSession(), captured.getStrategy(),
                captured.copyAdditionalProperties(), connection.overrides, locksOf(captured.getConfig()).getValues(), connection.proxy,
                connection.shadowedFactoryClassProperty, inputs.isApplyDebugEnabled(), inputs.isApplyDebugOutput()).toBuilder()
                .connectionConflictPolicy(captured.getPolicy()).customMailer(captured.getCustomMailer()).tokenProvider(captured.getTokenProvider()).build();
        final MailerOperationalSettings operational = inputs.getOperational().toBuilder()
                .debugConfig(resolveDebugConfig(captured.getConfig(), inputs.getOperational().getDebugConfig(), settings.getDebugConfig(),
                        captured.getSuppliedSession() != null)).build();
        final EmailGovernance governance = new EmailGovernanceImpl(captured.getConfig(), new EmailStartingBuilderImpl(captured.getConfig()),
                inputs.getEmailValidator(), inputs.getEmailDefaults(), inputs.getEmailOverrides(), inputs.getMaximumEmailSize());
        final ResolvedMailerConfiguration child = inputs.getNamedChild() == null ? null : resolve(inputs.getNamedChild());
        return new ResolvedMailerConfiguration(inputs, connection, settings, operational, governance, child);
    }

    /**
     * Keeps supplied SMTP settings available to explicit connection probes, including in logging-only mode.
     * Logging-only sends can still be built without an SMTP host; CustomMailer continues to own its connection settings.
     */
    @Nullable
    static ServerConfig resolveServer(final CapturedMailerInputs inputs, final MailerSessionSettings settings) {
        if (!inputs.isRegularMailer() || settings.getCustomMailer() != null) { return null; }
        final TransportStrategy strategy = requireNonNull(settings.getTransportStrategy(), "transportStrategy");
        final Map<String, Object> values = settings.getConnectionOverrides();
        final String host = stringValue(values.get(strategy.propertyNameHost()));
        if (inputs.getOperational().isTransportModeLoggingOnly() && valueNullOrEmpty(host)) { return null; }
        checkArgumentNotEmpty(host, "SMTP server host missing");
        final Object port = values.get(strategy.propertyNamePort());
        final Object factory = values.get(strategy.propertyNameSSLSocketFactory());
        final String password = OAuth2AccessTokenResolver.isOAuth2Transport(strategy)
                ? stringValue(values.get(TransportStrategy.OAUTH2_TOKEN_PROPERTY)) : inputs.getPassword();
        return new ServerConfigImpl(host, port == null ? strategy.getDefaultServerPort()
                : ConnectionConfigurationResolver.integer(port, strategy.propertyNamePort()),
                stringValue(values.get(strategy.propertyNameUsername())), password,
                stringValue(values.get(strategy.propertyNameSSLSocketFactoryClass())), factory instanceof SSLSocketFactory ? (SSLSocketFactory) factory : null);
    }

    @Nullable private static String stringValue(@Nullable final Object value) { return value instanceof String ? (String) value : null; }

    static SessionDebugConfig resolveDebugConfig(final SimpleJavaMailConfig config, final SessionDebugConfig choices,
            final SessionDebugConfig baseline, final boolean suppliedSession) {
        final SessionDebugConfig.Builder result = SessionDebugConfig.builder().withEnabled(
                resolveDebugChoice(config, ConfigKeys.JAVAXMAIL_DEBUG, choices.getEnabled(), baseline.getEnabled(), suppliedSession));
        if (choices.getPrinter() != null) { return result.withPrinter(choices.getPrinter()).build(); }
        final SessionDebugOutput output = resolveDebugChoice(config, ConfigKeys.JAVAXMAIL_DEBUG_OUTPUT, choices.getOutput(), baseline.getOutput(), suppliedSession);
        return output != null ? result.withOutput(output).build() : result.withPrinter(requireNonNull(baseline.getPrinter(), "baselinePrinter")).build();
    }

    /** A supplied Session contributes its existing debug state; owned Sessions contribute the documented built-in baseline. */
    @Nullable
    private static <T> T resolveDebugChoice(final SimpleJavaMailConfig config, final ConfigKey<T> key, @Nullable final T supplied,
            @Nullable final T baseline, final boolean suppliedSession) {
        final ConfigurationSetting<T> setting = ConfigurationSetting.configured(key);
        final ConfigurationOrigin.Kind kind = suppliedSession ? ConfigurationOrigin.Kind.SESSION : ConfigurationOrigin.Kind.BUILT_IN;
        return ConfigurationResolver.resolve(setting, List.of(
                ConfigurationDeclaration.javaChoice(setting, ConfigurationChoice.ofNullable(supplied), "SessionDebugConfig " + key.key()),
                ConfigurationDeclaration.configured(setting, config, key),
                ConfigurationDeclaration.of(setting, ConfigurationChoice.ofNullable(baseline), ConfigurationOrigin.of(kind, key.key(),
                        suppliedSession ? "supplied Session debug state" : "built-in debug state"))),
                List.of(ConfigurationOrigin.Kind.JAVA, ConfigurationOrigin.Kind.FACTORY, ConfigurationOrigin.Kind.SESSION, ConfigurationOrigin.Kind.BUILT_IN))
                .valueOrNull();
    }
}
