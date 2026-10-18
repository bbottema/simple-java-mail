package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.internal.util.SmtpDiagnosticText;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * TLS metadata observed during this probe's handshake. Encryption and certificate names do not prove trust.
 * The identity-check flag describes configured provider policy, not independent verification by Simple Java Mail.
 * Metadata may already exist when a later custom verifier or trust check rejects the connection.
 * <p>
 * Certificate identity labels are not necessarily a complete list of certificate names. The bundled Angus probe reports
 * at most 32 identities, including the certificate subject; the enclosing {@link SmtpConnectionReport} notes omissions in
 * {@code getWarnings()}. This reporting limit does not affect certificate verification.
 */
public final class SmtpTlsDetails implements Serializable {
    private static final long serialVersionUID = 1L;
    @NotNull private final String protocol;
    @NotNull private final String cipherSuite;
    /** Public certificate identity labels; the enclosing report warns if this list was limited. */
    @NotNull private final List<String> peerIdentities;
    private final boolean serverIdentityCheckEnabled;
    @Nullable
    private final Boolean customHostnameVerifierAccepted;

    /** Copies public certificate identities and negotiated metadata; never pass private material or credentials. */
    public SmtpTlsDetails(@NotNull final String protocol, @NotNull final String cipherSuite,
            @NotNull final List<String> peerIdentities, final boolean serverIdentityCheckEnabled,
            @Nullable final Boolean customHostnameVerifierAccepted) {
        this.protocol = SmtpDiagnosticText.display(protocol);
        this.cipherSuite = SmtpDiagnosticText.display(cipherSuite);
        this.peerIdentities = peerIdentities.stream().map(SmtpDiagnosticText::display).collect(Collectors.toUnmodifiableList());
        this.serverIdentityCheckEnabled = serverIdentityCheckEnabled;
        this.customHostnameVerifierAccepted = customHostnameVerifierAccepted;
    }

    /** Returns the negotiated TLS protocol, such as TLSv1.3. */
    @NotNull
    public String getProtocol() {
        return protocol;
    }

    /** Returns the cipher suite negotiated for this connection. */
    @NotNull
    public String getCipherSuite() {
        return cipherSuite;
    }

    /** Returns an immutable list of public certificate identity labels, subject to the report's diagnostic limits. */
    @NotNull
    public List<String> getPeerIdentities() {
        return peerIdentities;
    }

    /** Returns whether server-identity checking was configured; this flag alone is not a successful verification result. */
    public boolean isServerIdentityCheckEnabled() {
        return serverIdentityCheckEnabled;
    }

    /** Empty when no caller-supplied hostname verifier returned a result; this is not a certificate-trust verdict. */
    @NotNull
    public Optional<Boolean> getCustomHostnameVerifierAccepted() {
        return Optional.ofNullable(customHostnameVerifierAccepted);
    }

    @Override
    public String toString() {
        return protocol + ", cipher=" + cipherSuite + ", peer=" + peerIdentities
                + ", configured identity check=" + serverIdentityCheckEnabled
                + ", custom hostname verifier=" + getCustomHostnameVerifierAccepted().map(Object::toString).orElse("not observed");
    }

    private Object readResolve() {
        return new SmtpTlsDetails(protocol, cipherSuite, peerIdentities, serverIdentityCheckEnabled, customHostnameVerifierAccepted);
    }
}
