package org.simplejavamail.internal.util;

import jakarta.mail.Session;
import org.simplejavamail.api.mailer.SmtpConnectionPhase;
import org.simplejavamail.api.mailer.SmtpConnectionReport;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;

/** Shared report initialization and safe failure descriptions; never copies a provider exception message. */
public final class SmtpProbeReports {
    private SmtpProbeReports() {
    }

    public static SmtpConnectionReport.SmtpConnectionReportBuilder begin(final Session session, final boolean authenticate) {
        final String protocol = session.getProperty("mail.transport.protocol") == null ? "smtp" : session.getProperty("mail.transport.protocol");
        final String configuredHost = session.getProperty("mail." + protocol + ".host");
        final String fallbackHost = session.getProperty("mail.host");
        final Object configuredSsl = session.getProperties().get("mail." + protocol + ".ssl.enable");
        final Object sslEnabled = configuredSsl == null ? session.getProperty("mail." + protocol + ".ssl.enable") : configuredSsl;
        final boolean implicitTls = protocol.equals("smtps") || Boolean.TRUE.equals(sslEnabled) || "true".equalsIgnoreCase(sslEnabled instanceof String ? (String) sslEnabled : null);
        final int defaultPort = protocol.equals("smtp") || protocol.equals("smtps") ? (implicitTls ? 465 : 25) : -1;
        int port = defaultPort;
        try {
            final Object configuredPort = session.getProperties().get("mail." + protocol + ".port");
            port = configuredPort instanceof Integer ? (Integer) configuredPort : Integer.parseInt(session.getProperty("mail." + protocol + ".port"));
        } catch (NumberFormatException absentOrInvalidPort) {
            // Angus falls back to its protocol default for an absent or nonnumeric port.
        }
        return SmtpConnectionReport.builder().host(configuredHost != null ? configuredHost : fallbackHost != null ? fallbackHost : "localhost")
                .port(port == -1 ? defaultPort : port).protocol(protocol)
                .startedAt(Instant.now()).authenticationRequested(authenticate).warnings(List.of());
    }

    public static String describeFailure(final SmtpConnectionPhase phase, final Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return "The connection timed out during " + phase + ". Check the endpoint and proxy, and the configured connection/read timeouts.";
            }
        }
        switch (phase) {
            case AUTHENTICATION: return "Authentication did not complete. Check the credentials or token provider and the server's advertised AUTH mechanisms.";
            case STARTTLS: return "STARTTLS did not complete. Check whether the server offers TLS, its certificate and hostname, and your trust settings.";
            case GREETING: return "The server did not provide an accepted SMTP greeting. Check the endpoint, port and SMTP versus implicit-TLS configuration.";
            case EHLO: return "SMTP capability discovery failed. Check the server's EHLO/HELO support and the connection's TLS settings.";
            case CLOSE: return "The dedicated probe connection could not close normally. The earlier connection facts are retained.";
            case CONNECT: return "Could not open the SMTP connection. Check the host, port, proxy and, for implicit TLS, the certificate and trust settings.";
            default: return "Could not prepare the SMTP probe. Check the selected provider and the Session's connection settings.";
        }
    }
}
