package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Session;
import org.eclipse.angus.mail.util.PropUtil;
import org.simplejavamail.api.mailer.SmtpTlsDetails;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

/** Observes the supported post-handshake hook on a private Session without replacing its verification decisions. */
final class AngusProbeTlsObserver {
    /** Bounds diagnostic output only; certificate verification still receives the full certificate. */
    private static final int MAX_REPORTED_PEER_IDENTITIES = 32;
    private final boolean identityCheckEnabled;
    private final boolean unavailable;
    private SmtpTlsDetails details;
    private boolean peerIdentitiesTruncated;

    AngusProbeTlsObserver(final Session session, final String protocol) {
        final Properties properties = session.getProperties();
        final String prefix = "mail." + protocol + ".ssl.";
        identityCheckEnabled = PropUtil.getBooleanProperty(properties, prefix + "checkserveridentity", true);
        final Object configured = properties.get(prefix + "hostnameverifier");
        final String configuredClass = properties.getProperty(prefix + "hostnameverifier.class");
        unavailable = configured == null && configuredClass != null && !configuredClass.isEmpty();
        if (!unavailable && (configured == null || configured instanceof HostnameVerifier)) {
            final HostnameVerifier delegate = (HostnameVerifier) configured;
            properties.put(prefix + "hostnameverifier", (HostnameVerifier) (host, tlsSession) -> {
                Boolean accepted = null;
                try {
                    if (delegate != null) {
                        accepted = delegate.verify(host, tlsSession);
                        return accepted;
                    }
                    return true; // JDK endpoint identification already ran; no caller verifier was configured.
                } finally {
                    captureDetails(tlsSession, accepted);
                }
            });
        }
    }

    private void captureDetails(final SSLSession tlsSession, final Boolean customVerifierAccepted) {
        try {
            final List<String> identities = peerIdentities(tlsSession);
            final int reportedIdentityCount = Math.min(identities.size(), MAX_REPORTED_PEER_IDENTITIES);
            details = new SmtpTlsDetails(tlsSession.getProtocol(), tlsSession.getCipherSuite(), identities.subList(0, reportedIdentityCount),
                    identityCheckEnabled, customVerifierAccepted);
            peerIdentitiesTruncated = identities.size() > MAX_REPORTED_PEER_IDENTITIES;
        } catch (RuntimeException | SSLPeerUnverifiedException | CertificateParsingException unavailableMetadata) {
            // Optional diagnostics must not change the outcome of an application verifier or the handshake itself.
            reset();
        }
    }

    private static List<String> peerIdentities(final SSLSession tlsSession) throws SSLPeerUnverifiedException, CertificateParsingException {
        final List<String> identities = new ArrayList<>();
        final Certificate[] certificates = tlsSession.getPeerCertificates();
        if (certificates.length > 0 && certificates[0] instanceof X509Certificate) {
            final X509Certificate certificate = (X509Certificate) certificates[0];
            identities.add(certificate.getSubjectX500Principal().getName());
            final Collection<List<?>> alternatives = certificate.getSubjectAlternativeNames();
            if (alternatives != null) {
                for (final List<?> alternative : alternatives) {
                    if (alternative.size() >= 2 && (Integer.valueOf(2).equals(alternative.get(0))
                            || Integer.valueOf(7).equals(alternative.get(0))) && alternative.get(1) instanceof String) {
                        identities.add((String) alternative.get(1));
                        // One extra identity distinguishes an exactly full list from a truncated one.
                        if (identities.size() > MAX_REPORTED_PEER_IDENTITIES) {
                            return identities;
                        }
                    }
                }
            }
        }
        return identities;
    }

    void reset() {
        details = null;
        peerIdentitiesTruncated = false;
    }

    SmtpTlsDetails getDetails() { return details; }

    List<String> getWarnings() {
        if (unavailable) {
            return List.of("TLS metadata is unavailable because the configured hostname-verifier class was left unchanged.");
        }
        if (peerIdentitiesTruncated) {
            return List.of("Only the first " + MAX_REPORTED_PEER_IDENTITIES + " certificate identities are shown; additional names were omitted.");
        }
        return List.of();
    }
}
