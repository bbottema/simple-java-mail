package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.internal.email.SigningKeyFingerprint;

import java.io.Serializable;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable signing keys explicitly approved by the application, including replacement keys during rotation.
 * Certificate approval pins its public signing key; it does not approve certificate claims or perform PKI trust discovery,
 * certificate-path, validity-time, revocation or claimed-sender checks. No private keys or provider objects are retained.
 */
public final class ApprovedSigners implements Serializable {
    private static final long serialVersionUID = 1L;
    private final Set<String> smimeSigningKeyFingerprints;
    private final Set<String> openPgpSigningKeyFingerprints;

    private ApprovedSigners(final Builder builder) {
        smimeSigningKeyFingerprints = snapshot(builder.smimeSigningKeyFingerprints);
        final Set<String> pgp = new LinkedHashSet<>();
        for (String fingerprint : builder.openPgpSigningKeyFingerprints) pgp.add(SigningKeyFingerprint.forOpenPgp(fingerprint));
        openPgpSigningKeyFingerprints = snapshot(pgp);
        if (smimeSigningKeyFingerprints.isEmpty() && openPgpSigningKeyFingerprints.isEmpty()) {
            throw new IllegalArgumentException("At least one approved signing key is required");
        }
    }

    @NotNull public static Builder builder() { return new Builder(); }

    /** Returns an independent builder with these already captured key choices. */
    @NotNull public Builder toBuilder() {
        final Builder builder = new Builder();
        builder.smimeSigningKeyFingerprints.addAll(smimeSigningKeyFingerprints);
        builder.openPgpSigningKeyFingerprints.addAll(openPgpSigningKeyFingerprints);
        return builder;
    }

    /** Immutable normalized public-key identities; no certificate is read by this accessor.
     * @see Builder#addSmimeCertificate(X509Certificate)
     */
    @NotNull public Set<String> getSmimeSigningKeyFingerprints() { return smimeSigningKeyFingerprints; }
    /** Immutable full signing-key fingerprints.
     * @see Builder#addOpenPgpSigningKeyFingerprint(String)
     */
    @NotNull public Set<String> getOpenPgpSigningKeyFingerprints() { return openPgpSigningKeyFingerprints; }

    private static Set<String> snapshot(final Set<String> source) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }

    /** Collects application-approved keys; incomplete choices can be inspected before build. */
    public static final class Builder {
        private final Set<String> smimeSigningKeyFingerprints = new LinkedHashSet<>();
        private final Set<String> openPgpSigningKeyFingerprints = new LinkedHashSet<>();

        private Builder() { }

        /**
         * Approves the certificate's actual public signing key, snapshotting its normalized identity now without retaining
         * the certificate or verifying any message. A renewed certificate using the same key still matches; another key
         * must be approved separately. RSA, DSA and EC identities use public mathematical components across providers;
         * other public keys use their canonical JCA public encoding. {@link #clearSmimeCertificates()} removes only these approvals.
         * @param certificate application-supplied certificate whose key is approved
         * @throws NullPointerException if the certificate is null; existing choices are preserved
         */
        @NotNull public Builder addSmimeCertificate(@NotNull final X509Certificate certificate) {
            final String fingerprint = SigningKeyFingerprint.forSmime(Objects.requireNonNull(certificate, "certificate").getPublicKey());
            smimeSigningKeyFingerprints.add(fingerprint);
            return this;
        }

        /**
         * Approves exactly one full OpenPGP signing-key fingerprint. Supply the actual signing subkey's fingerprint when
         * it makes the signature; approval of a primary key does not automatically approve its subkeys. No key material is
         * loaded and no unvalidated subkey binding is trusted. Case, spaces and colons are normalized; {@link #build()}
         * rejects text other than 40 or 64 hexadecimal digits, including short key IDs. Unfinished text remains inspectable.
         * @param fingerprint full actual signing-key fingerprint
         * @throws NullPointerException if the fingerprint is null; existing choices are preserved
         */
        @NotNull public Builder addOpenPgpSigningKeyFingerprint(@NotNull final String fingerprint) {
            openPgpSigningKeyFingerprints.add(SigningKeyFingerprint.normalizeOpenPgp(fingerprint));
            return this;
        }

        /** Returns immutable captured choices without certificate access.
         * @see #addSmimeCertificate(X509Certificate)
         */
        @NotNull public Set<String> getSmimeSigningKeyFingerprints() { return snapshot(smimeSigningKeyFingerprints); }
        /** Returns immutable supplied text, including unfinished fingerprints, without validation.
         * @see #addOpenPgpSigningKeyFingerprint(String)
         */
        @NotNull public Set<String> getOpenPgpSigningKeyFingerprints() { return snapshot(openPgpSigningKeyFingerprints); }
        /** Removes only S/MIME key approvals, preserving OpenPGP choices.
         * @see #addSmimeCertificate(X509Certificate)
         */
        @NotNull public Builder clearSmimeCertificates() { smimeSigningKeyFingerprints.clear(); return this; }
        /** Removes only OpenPGP key approvals, preserving S/MIME choices.
         * @see #addOpenPgpSigningKeyFingerprint(String)
         */
        @NotNull public Builder clearOpenPgpSigningKeyFingerprints() { openPgpSigningKeyFingerprints.clear(); return this; }

        /**
         * Creates an independent approved set, validating full fingerprint shape and requiring at least one key.
         * This does not load optional modules, discover trust or verify a signature.
         * @throws IllegalArgumentException if no key is approved or an OpenPGP fingerprint is incomplete
         */
        @NotNull public ApprovedSigners build() { return new ApprovedSigners(this); }
    }
}
