package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.SmtpCapabilities;
import org.simplejavamail.api.mailer.SmtpConnectionPhase;
import org.simplejavamail.api.mailer.SmtpConnectionReport;
import org.simplejavamail.internal.util.SmtpProbeReports;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures only greeting, EHLO and terminal AUTH facts through supported Angus hooks. One instance belongs to one
 * dedicated probe; it is never registered in a Session, leased from a pool, or used to submit a message.
 */
final class AngusProbeTransport extends SMTPTransport {
    private static final Pattern EXTENSION = Pattern.compile("([A-Za-z0-9][A-Za-z0-9-]*)(?:[ \\t]+(.*))?");
    private final SmtpConnectionReport.SmtpConnectionReportBuilder report;
    private final List<String> warnings = new ArrayList<>();
    private final AngusProbeTlsObserver tlsObserver;
    private final boolean authenticationRequested;
    private SmtpConnectionPhase phase;
    private SmtpConnectionPhase failurePhase;
    private boolean awaitingGreeting = true;
    private boolean authenticated;
    private boolean authenticationInProgress;
    private boolean greeted;
    private boolean startTlsAttempted;

    AngusProbeTransport(final Session session, final String protocol, final boolean authenticate) {
        super(session, new URLName(protocol, null, -1, null, null, null), protocol, protocol.equals("smtps"));
        authenticationRequested = authenticate;
        phase = authenticate ? SmtpConnectionPhase.AUTHENTICATION : SmtpConnectionPhase.CONNECT;
        report = SmtpProbeReports.begin(session, authenticate).supported(true);
        tlsObserver = new AngusProbeTlsObserver(session, protocol);
    }

    void recordConnected() {
        report.connected(true).tlsActive(isSSL()).authenticated(authenticated);
        if (authenticationRequested && !authenticated && failurePhase == null) {
            fail(SmtpConnectionPhase.AUTHENTICATION, "The connection opened, but authentication was not confirmed. "
                    + "Check the advertised AUTH mechanisms and your credential configuration.");
        }
    }

    void recordFailure(final Throwable failure) {
        // No AUTH command is sent when client/server mechanisms do not overlap.
        final SmtpConnectionPhase failedPhase = failure instanceof AuthenticationFailedException ? SmtpConnectionPhase.AUTHENTICATION : phase;
        fail(failedPhase, SmtpProbeReports.describeFailure(failedPhase, failure));
    }

    void closeProbe() {
        phase = SmtpConnectionPhase.CLOSE;
        try {
            close();
        } catch (MessagingException | RuntimeException failure) {
            if (failurePhase == null) {
                recordFailure(failure);
            } else {
                warnings.add("Closing the dedicated connection also failed; the first failure is retained.");
            }
        }
    }

    SmtpConnectionReport report() {
        final List<String> notes = new ArrayList<>(warnings);
        notes.addAll(tlsObserver.getWarnings());
        return report.tlsDetails(tlsObserver.getDetails()).completedAt(Instant.now()).warnings(notes).build();
    }

    private void fail(final SmtpConnectionPhase failedPhase, final String description) {
        if (failurePhase == null) {
            failurePhase = failedPhase;
            report.failurePhase(failedPhase).failureDescription(description);
        }
    }

    @Override
    protected synchronized boolean protocolConnect(final String host, final int port, final String user, final String password)
            throws MessagingException {
        resetConnectionFacts();
        phase = SmtpConnectionPhase.CONNECT;
        final boolean connected = super.protocolConnect(host, port, user, password);
        if (!connected) {
            phase = SmtpConnectionPhase.AUTHENTICATION;
        }
        return connected;
    }

    private void resetConnectionFacts() {
        if (greeted) {
            warnings.add("The provider retried connection setup; the snapshots describe the final connection attempt.");
        }
        greeted = false;
        awaitingGreeting = true;
        authenticated = false;
        authenticationInProgress = false;
        startTlsAttempted = false;
        failurePhase = null;
        tlsObserver.reset();
        report.greeting(null).beforeTls(null).afterTls(null).tlsActive(false).tlsDetails(null)
                .startTlsAttempted(false).startTlsCompleted(false).authenticationMechanism(null).authenticated(false)
                .connected(false).failurePhase(null).failureDescription(null);
    }

    @Override
    protected int readServerResponse() throws MessagingException {
        if (awaitingGreeting) {
            phase = SmtpConnectionPhase.GREETING;
        }
        final int code = super.readServerResponse();
        if (awaitingGreeting) {
            awaitingGreeting = false;
            greeted = true;
            report.greeting(getLastServerResponse().trim()).tlsActive(isSSL());
        }
        if (authenticationInProgress && code == 235) {
            authenticated = true;
            authenticationInProgress = false;
        }
        return code;
    }

    @Override
    protected boolean ehlo(final String domain) throws MessagingException {
        phase = SmtpConnectionPhase.EHLO;
        final boolean succeeded = super.ehlo(domain);
        if (succeeded) {
            final SmtpCapabilities capabilities = parseCapabilities(getLastServerResponse());
            if (isSSL()) {
                report.afterTls(capabilities);
            } else {
                report.beforeTls(capabilities);
            }
        } else if (startTlsAttempted) {
            fail(SmtpConnectionPhase.EHLO, "The server rejected EHLO on the TLS connection. "
                    + "Pre-TLS advertisements are historical only; current capabilities are unknown.");
            // Do not let Angus authenticate using the stale pre-TLS extension map it otherwise retains.
            throw new MessagingException("EHLO was rejected on the TLS connection");
        }
        if (succeeded && !isSSL() && getRequireStartTLS() && !supportsExtension("STARTTLS")) {
            phase = SmtpConnectionPhase.STARTTLS;
        }
        return succeeded;
    }

    @Override
    protected void helo(final String domain) throws MessagingException {
        phase = SmtpConnectionPhase.EHLO;
        super.helo(domain);
        warnings.add("The server accepted HELO instead of EHLO; extension capabilities are unknown.");
        if (getRequireStartTLS() && !isSSL()) {
            phase = SmtpConnectionPhase.STARTTLS;
        }
    }

    @Override
    protected void startTLS() throws MessagingException {
        phase = SmtpConnectionPhase.STARTTLS;
        startTlsAttempted = true;
        report.startTlsAttempted(true);
        super.startTLS();
        report.startTlsCompleted(true).tlsActive(true);
    }

    @Override
    protected void sendCommand(final String command) throws MessagingException {
        observeAuthenticationCommand(command);
        super.sendCommand(command);
    }

    @Override
    protected int simpleCommand(final byte[] command) throws MessagingException {
        // Only inspect the verb/mechanism prefix. Never decode or retain an initial response or challenge answer.
        observeAuthenticationCommand(new String(command, 0, Math.min(command.length, 32), StandardCharsets.US_ASCII));
        return super.simpleCommand(command);
    }

    private void observeAuthenticationCommand(final String command) {
        if (command.startsWith("AUTH ")) {
            phase = SmtpConnectionPhase.AUTHENTICATION;
            authenticationInProgress = true;
            final int parameter = command.indexOf(' ', 5);
            final String mechanism = command.substring(5, parameter < 0 ? Math.min(command.length(), 25) : parameter);
            if (mechanism.matches("[A-Za-z0-9_-]{1,20}")) {
                report.authenticationMechanism(mechanism.toUpperCase(Locale.ROOT));
            }
        }
    }

    @Nullable
    private SmtpCapabilities parseCapabilities(final String response) {
        final Map<String, List<String>> extensions = new LinkedHashMap<>();
        final String[] lines = response.split("\\r?\\n", 259);
        // Angus terminates its saved reply with a newline; that is framing, not an empty extension.
        final int lineCount = lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
        if (lineCount > 257) {
            warnings.add("EHLO contained more than 256 extension lines; capabilities are unavailable rather than a misleading partial snapshot.");
            return null;
        }
        for (int index = 1; index < lineCount; index++) {
            final String line = lines[index];
            if (line.length() > 2048) {
                warnings.add("An EHLO line exceeded the diagnostic limit; capabilities are unavailable rather than truncated names or parameters.");
                return null;
            }
            final Matcher extension = EXTENSION.matcher(line.length() > 4 ? line.substring(4) : "");
            if (!(line.startsWith("250-") || line.startsWith("250 ")) || !extension.matches()) {
                warnings.add("An invalid EHLO extension line was ignored.");
                continue;
            }
            final String name = extension.group(1).toUpperCase(Locale.ROOT);
            final String value = extension.group(2) == null ? "" : extension.group(2).trim();
            extensions.computeIfAbsent(name, unused -> new ArrayList<>()).add(value);
        }
        return new SmtpCapabilities(extensions);
    }
}
