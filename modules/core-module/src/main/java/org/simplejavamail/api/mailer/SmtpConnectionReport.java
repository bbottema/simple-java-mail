package org.simplejavamail.api.mailer;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.internal.util.SmtpDiagnosticText;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Immutable facts from a dedicated SMTP connection, including partial results when setup fails.
 * This is not a send receipt: no message was submitted, and no delivery or future send is guaranteed.
 * Text fields are escaped for single-line logging; no raw exception, AUTH exchange or provider object is retained.
 * The endpoint is the configured endpoint: a custom socket factory or proxy may route it elsewhere.
 * Read the configured transport strategy from {@link Mailer#getTransportStrategy()}; it is not a probe observation.
 *
 * @see Mailer.Sync#probeConnection()
 * @see Mailer.Async#probeConnection()
 */
@Getter
public final class SmtpConnectionReport implements Serializable {
    private static final long serialVersionUID = 1L;
    @NotNull private final String host;
    private final int port;
    @NotNull private final String protocol;
    @NotNull private final Instant startedAt;
    @NotNull private final Instant completedAt;
    private final boolean supported;
    /** Whether setup returned successfully; the dedicated socket is closed before this report is returned. */
    private final boolean connected;
    private final boolean authenticationRequested;
    private final boolean authenticated;
    private final boolean startTlsAttempted;
    private final boolean startTlsCompleted;
    /** Whether TLS was observed during setup, not an independent certificate-trust verdict. */
    private final boolean tlsActive;
    @Getter(AccessLevel.NONE) @Nullable private final String greeting;
    @Getter(AccessLevel.NONE) @Nullable private final SmtpCapabilities beforeTls;
    @Getter(AccessLevel.NONE) @Nullable private final SmtpCapabilities afterTls;
    @Getter(AccessLevel.NONE) @Nullable private final SmtpTlsDetails tlsDetails;
    @Getter(AccessLevel.NONE) @Nullable private final String authenticationMechanism;
    @Getter(AccessLevel.NONE) @Nullable private final SmtpConnectionPhase failurePhase;
    @Getter(AccessLevel.NONE) @Nullable private final String failureDescription;
    @NotNull private final List<String> warnings;

    /** Provider-adapter construction boundary. All supplied collections and display fields are copied. */
    @Builder(toBuilder = true)
    public SmtpConnectionReport(@NotNull final String host, final int port, @NotNull final String protocol,
            @NotNull final Instant startedAt, @NotNull final Instant completedAt, final boolean supported, final boolean connected,
            final boolean authenticationRequested, final boolean authenticated, final boolean startTlsAttempted,
            final boolean startTlsCompleted, final boolean tlsActive, @Nullable final String greeting,
            @Nullable final SmtpCapabilities beforeTls, @Nullable final SmtpCapabilities afterTls,
            @Nullable final SmtpTlsDetails tlsDetails, @Nullable final String authenticationMechanism,
            @Nullable final SmtpConnectionPhase failurePhase, @Nullable final String failureDescription,
            @NotNull final List<String> warnings) {
        this.host = SmtpDiagnosticText.display(host);
        this.port = port;
        this.protocol = SmtpDiagnosticText.display(protocol);
        this.startedAt = requireNonNull(startedAt, "startedAt");
        this.completedAt = requireNonNull(completedAt, "completedAt");
        this.supported = supported;
        this.connected = connected;
        this.authenticationRequested = authenticationRequested;
        this.authenticated = authenticated;
        this.startTlsAttempted = startTlsAttempted;
        this.startTlsCompleted = startTlsCompleted;
        this.tlsActive = tlsActive;
        this.greeting = greeting == null ? null : SmtpDiagnosticText.display(greeting);
        this.beforeTls = beforeTls;
        this.afterTls = afterTls;
        this.tlsDetails = tlsDetails;
        this.authenticationMechanism = authenticationMechanism == null ? null : SmtpDiagnosticText.display(authenticationMechanism);
        this.failurePhase = failurePhase;
        this.failureDescription = failureDescription == null ? null : SmtpDiagnosticText.display(failureDescription);
        this.warnings = warnings.stream().map(SmtpDiagnosticText::display).collect(Collectors.toUnmodifiableList());
    }

    /** Whether connection, requested authentication and cleanup completed without a recorded failure. */
    public boolean isSuccessful() {
        return supported && connected && failurePhase == null && (!authenticationRequested || authenticated);
    }

    /** The initial SMTP greeting, if it was read; never an AUTH response. */
    @NotNull public Optional<String> getGreeting() { return Optional.ofNullable(greeting); }
    /** Empty for implicit TLS, rejected EHLO, disabled EHLO or failure before discovery. */
    @NotNull public Optional<SmtpCapabilities> getBeforeTls() { return Optional.ofNullable(beforeTls); }
    /** Only a successful EHLO on the encrypted connection; never a reused pre-TLS map. */
    @NotNull public Optional<SmtpCapabilities> getAfterTls() { return Optional.ofNullable(afterTls); }
    /** Metadata, not a trust verdict; empty when the provider/configuration could not safely expose it. */
    @NotNull public Optional<SmtpTlsDetails> getTlsDetails() { return Optional.ofNullable(tlsDetails); }
    /** Mechanism observed in an AUTH command, not simply the first configured or advertised mechanism. */
    @NotNull public Optional<String> getAuthenticationMechanism() { return Optional.ofNullable(authenticationMechanism); }
    /** The first failed step; later cleanup failures appear in warnings without erasing earlier facts. */
    @NotNull public Optional<SmtpConnectionPhase> getFailurePhase() { return Optional.ofNullable(failurePhase); }
    /** A safe, actionable description rather than a provider exception message or authentication reply. */
    @NotNull public Optional<String> getFailureDescription() { return Optional.ofNullable(failureDescription); }

    /**
     * The successful EHLO snapshot applicable at the end of setup. Empty after any failed STARTTLS attempt,
     * failed post-TLS discovery, or connection setup failure; inspect the historical snapshots separately.
     */
    @NotNull
    public Optional<SmtpCapabilities> getEffectiveCapabilities() {
        if (!connected || (startTlsAttempted && !startTlsCompleted)) {
            return Optional.empty();
        }
        return tlsActive ? getAfterTls() : getBeforeTls();
    }

    @Override
    public String toString() {
        final StringBuilder report = new StringBuilder("SMTP connection probe: ")
                .append(isSuccessful() ? "SUCCESS" : supported ? "FAILED" : "UNSUPPORTED")
                .append("\nEndpoint: ").append(host).append(':').append(port).append(" (").append(protocol).append(")")
                .append("\nGreeting: ").append(getGreeting().orElse("not observed"))
                .append("\nBefore TLS: ").append(getBeforeTls().map(Object::toString).orElse("not available"))
                .append("\nSTARTTLS: ").append(startTlsCompleted ? "completed" : startTlsAttempted ? "failed" : "not attempted")
                .append("\nAfter TLS: ").append(getAfterTls().map(Object::toString).orElse("not available"))
                .append("\nTLS active during setup: ").append(tlsActive)
                .append("\nTLS details: ").append(getTlsDetails().map(Object::toString).orElse("not available"))
                .append("\nAuthentication: ").append(!authenticationRequested ? "not requested" : authenticated ? "succeeded" : "not completed");
        getAuthenticationMechanism().ifPresent(mechanism -> report.append(" (").append(mechanism).append(')'));
        getFailurePhase().ifPresent(phase -> report.append("\nFailure at ").append(phase).append(": ").append(getFailureDescription().orElse("not available")));
        warnings.forEach(warning -> report.append("\nNote: ").append(warning));
        return report.append("\nElapsed: ").append(Duration.between(startedAt, completedAt).toMillis()).append("ms").toString();
    }

    private Object readResolve() {
        return toBuilder().build();
    }
}
