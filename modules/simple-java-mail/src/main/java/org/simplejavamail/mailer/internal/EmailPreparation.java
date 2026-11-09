package org.simplejavamail.mailer.internal;

import org.simplejavamail.internal.smime.FactorySmimeRuntime;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.api.mailer.MailRehearsal;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.email.internal.InternalEmail;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;

import java.net.InetSocketAddress;
import java.util.Properties;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.simplejavamail.mailer.internal.MailerException.VALIDATION_ERROR;

/** Governed message preparation shared by built Mailers and transient CLI validation. */
final class EmailPreparation {
    private EmailPreparation() { }

    static void validate(final ValidatedMailerPlan plan, final Email email) {
        final ResolvedMailerConfiguration configuration = plan.getConfiguration();
        final MailerOperationalSettings operational = configuration.getOperational();
        rehearse(email, mimeSession(plan), configuration.getGovernance(), operational.isClientValidationWarningsOnly(),
                operational.isLegacySmtpContentSupportEnabled(), true, configuration.getInputs().getSmimeRuntime());
    }

    static MailRehearsal rehearse(final Email suppliedEmail, final Session preparationSession, final EmailGovernance governance,
            final boolean warningsOnly, final boolean legacySmtpContentSupportEnabled, final boolean processSecurityAndValidateSize,
            final FactorySmimeRuntime smimeRuntime) {
        final Email governedEmail = InternalEmail.requireInternalEmail(requireNonNull(suppliedEmail, "email"))
                .prepareForSending(governance, warningsOnly);
        // A preview owns its generated identity; it must not propagate it into the caller's draft.
        InternalEmail.requireInternalEmail(governedEmail).setUserProvidedEmail(null);
        try {
            final MailRehearsal rehearsal = SessionBasedEmailToMimeMessageConverter
                    .forPreparation(preparationSession, governance, legacySmtpContentSupportEnabled)
                    .rehearseMimeMessage(governedEmail, processSecurityAndValidateSize, smimeRuntime);
            if (rehearsal.getEmailId() != null) {
                InternalEmail.requireInternalEmail(governedEmail).updateId(rehearsal.getEmailId());
            }
            return rehearsal;
        } catch (MessagingException failure) {
            throw new MailerException(MailFailureKind.PREPARATION, format(VALIDATION_ERROR, governedEmail.getId()), failure);
        }
    }

    private static Session mimeSession(final ValidatedMailerPlan plan) {
        final ResolvedMailerConfiguration configuration = plan.getConfiguration();
        final CapturedMailerInputs inputs = configuration.getInputs();
        final ValidatedConnectionPlan connection = plan.getConnection();
        final MailerSessionSettings settings = configuration.getSessionSettings();
        final boolean owned = connection.targetSession() == null;
        final Properties baseline = owned
                ? MailerImpl.mailSessionProperties(connection.serverConfig(), requireNonNull(settings.getTransportStrategy(), "transportStrategy"),
                        inputs.isOpportunisticTLS())
                : inputs.getConnection().copySessionProperties();
        final Session preparation = RehearsalSessionContext.quietMimeSession(connection.changes().propertiesForPreparation(baseline));
        if (owned && settings.getCustomMailer() == null) {
            initializeOwnedMimeSession(configuration, preparation);
        }
        return preparation;
    }

    /** Existing provider initialization supplies MIME defaults without creating a Transport or starting network work. */
    private static void initializeOwnedMimeSession(final ResolvedMailerConfiguration configuration, final Session preparation) {
        final CapturedConnectionInputs inputs = configuration.getInputs().getConnection();
        final MailerSessionSettings settings = configuration.getSessionSettings();
        final ProxyConfig proxy = requireNonNull(settings.getProxyConfig(), "proxyConfig");
        final TransportStrategy strategy = settings.getTransportStrategy();
        final LockedSmtpConnectionRequirements requirements = new LockedSmtpConnectionRequirements(inputs.getConfig(), proxy,
                settings.isTrustAllSSLHost(), strategy);
        requirements.verifySession(preparation);
        try {
            if (proxy.requiresProxy() && !proxy.requiresAuthentication()) {
                MailTransportLifecycleResolver.configureOwnedSession(preparation,
                        InetSocketAddress.createUnresolved(proxy.getRemoteProxyHost(), proxy.getRemoteProxyPort()));
            } else {
                MailTransportLifecycleResolver.configureOwnedSession(preparation);
            }
            requirements.verifySession(preparation);
        } catch (MessagingException failure) {
            throw new MailerException("Couldn't prepare the MIME Session for validation. Check the cause and your Jakarta Mail provider dependencies.", failure);
        }
    }
}
