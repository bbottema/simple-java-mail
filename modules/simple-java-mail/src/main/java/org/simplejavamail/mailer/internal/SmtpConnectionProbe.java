package org.simplejavamail.mailer.internal;

import jakarta.mail.Authenticator;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.SmtpConnectionPhase;
import org.simplejavamail.api.mailer.SmtpConnectionReport;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.spi.SmtpConnectionProbeAdapter;
import org.simplejavamail.internal.util.MailProviderDiscovery;
import org.simplejavamail.internal.util.SmtpProbeReports;
import org.simplejavamail.mailer.internal.util.TransportConnectionHelper;

import java.time.Instant;
import java.util.Properties;
import java.util.ServiceConfigurationError;

/** Selects inspection support and isolates credentials/configuration for a dedicated connection probe. */
final class SmtpConnectionProbe {
    private SmtpConnectionProbe() {
    }

    @NotNull
    static SmtpConnectionReport probe(@NotNull final Session source, @NotNull final OperationalConfig config, final boolean authenticate) {
        final Instant startedAt = Instant.now();
        final SmtpConnectionReport.SmtpConnectionReportBuilder initial = SmtpProbeReports.begin(source, authenticate).startedAt(startedAt);
        if (config.getCustomMailer() != null) {
            return unavailable(initial, "CustomMailer owns its connection. Use its diagnostics or testConnection(); this probe cannot inspect that transport.");
        }
        try {
            final String protocol = source.getProperty("mail.transport.protocol") == null ? "smtp" : source.getProperty("mail.transport.protocol");
            final Provider provider = source.getProvider(protocol);
            final SmtpConnectionProbeAdapter adapter = selectAdapter(provider);
            if (adapter == null) {
                return unavailable(initial, "No SMTP connection-probe adapter supports provider '" + provider.getClassName()
                        + "'. Keep using testConnection() or supply an adapter for that provider.");
            }
            initial.supported(true);
            final Session dedicated = createIsolatedSession(source, provider, authenticate);
            final SmtpConnectionReport report = adapter.probe(dedicated, authenticate, transport -> {
                if (authenticate) {
                    TransportConnectionHelper.connectTransport(transport, dedicated);
                } else {
                    transport.connect();
                }
            });
            return report.toBuilder().startedAt(startedAt).completedAt(Instant.now()).build();
        } catch (MessagingException | RuntimeException | ServiceConfigurationError failure) {
            return initial.completedAt(Instant.now()).failurePhase(SmtpConnectionPhase.SETUP)
                    .failureDescription(SmtpProbeReports.describeFailure(SmtpConnectionPhase.SETUP, failure)).build();
        }
    }

    @Nullable
    private static SmtpConnectionProbeAdapter selectAdapter(final Provider provider) {
        SmtpConnectionProbeAdapter selected = null;
        for (final SmtpConnectionProbeAdapter candidate : MailProviderDiscovery.newProviders(SmtpConnectionProbeAdapter.class)) {
            if (candidate.supportsProvider(provider)) {
                if (selected != null) {
                    throw new IllegalStateException("More than one SMTP connection-probe adapter matches the selected provider.");
                }
                selected = candidate;
            }
        }
        return selected;
    }

    private static SmtpConnectionReport unavailable(final SmtpConnectionReport.SmtpConnectionReportBuilder initial, final String reason) {
        return initial.supported(false).completedAt(Instant.now()).failurePhase(SmtpConnectionPhase.SETUP).failureDescription(reason).build();
    }

    private static Session createIsolatedSession(final Session source, final Provider provider, final boolean authenticate) throws MessagingException {
        final Properties properties = (Properties) source.getProperties().clone();
        final String protocol = provider.getProtocol();
        properties.setProperty("mail.transport.protocol", protocol);
        properties.setProperty("mail." + protocol + ".auth", Boolean.toString(authenticate));
        // A probe sends no MIME or envelope addresses. Avoid Angus's irrelevant UTF-8 output notice on legacy servers.
        properties.setProperty("mail.mime.allowutf8", "false");
        properties.setProperty("mail.debug", "false");
        properties.setProperty("mail.debug.auth", "false");
        properties.setProperty("mail.debug.auth.username", "false");
        properties.setProperty("mail.debug.auth.password", "false");
        final Session session = Session.getInstance(properties, authenticate ? new ProbeAuthenticator(source, properties, protocol) : null);
        session.addProvider(provider);
        session.setProvider(provider);
        return session;
    }

    /** Forward authentication lazily, including an existing Session cache, without copying that cache into the no-auth path. */
    private static final class ProbeAuthenticator extends Authenticator {
        private final Session source;
        private final Properties properties;
        private final String protocol;

        private ProbeAuthenticator(final Session source, final Properties properties, final String protocol) {
            this.source = source;
            this.properties = properties;
            this.protocol = protocol;
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            final String host = properties.getProperty("mail." + protocol + ".host", properties.getProperty("mail.host", "localhost"));
            final URLName key = new URLName(protocol, host, getRequestingPort(), null, getDefaultUserName(), null);
            final PasswordAuthentication cached = source.getPasswordAuthentication(key);
            return cached != null ? cached : source.requestPasswordAuthentication(getRequestingSite(), getRequestingPort(),
                    getRequestingProtocol(), getRequestingPrompt(), getDefaultUserName());
        }
    }

}
