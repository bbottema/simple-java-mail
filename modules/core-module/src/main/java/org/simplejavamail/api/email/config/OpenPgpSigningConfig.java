package org.simplejavamail.api.email.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

import static java.util.Objects.requireNonNull;
import static org.simplejavamail.api.email.EmailStartingBuilder.DEFAULT_OPENPGP_SIGNING_HASH_ALGORITHM;

/**
 * In-memory OpenPGP signing material. This type intentionally is not serializable: the secret key ring and
 * passphrase must be supplied again after an {@code Email} serialization round trip.
 */
public final class OpenPgpSigningConfig {

    public enum HashAlgorithm {
        SHA256, SHA384, SHA512
    }

    private final byte[] secretKeyRing;
    private final char[] passphrase;
    @Nullable
    private final Long signingKeyId;
    @NotNull
    private final HashAlgorithm hashAlgorithm;

    private OpenPgpSigningConfig(final byte[] secretKeyRing,
                                 final char[] passphrase,
                                 @Nullable final Long signingKeyId,
                                 @NotNull final HashAlgorithm hashAlgorithm) {
        this.secretKeyRing = requireNonNull(secretKeyRing, "secretKeyRing").clone();
        this.passphrase = requireNonNull(passphrase, "passphrase").clone();
        this.signingKeyId = signingKeyId;
        this.hashAlgorithm = requireNonNull(hashAlgorithm, "hashAlgorithm");
        if (secretKeyRing.length == 0) {
            throw new IllegalArgumentException("secretKeyRing cannot be empty");
        }
    }

    public static OpenPgpSigningConfigBuilder builder() {
        return new OpenPgpSigningConfigBuilder();
    }

    public byte[] getSecretKeyRing() {
        return secretKeyRing.clone();
    }

    public char[] getPassphrase() {
        return passphrase.clone();
    }

    /**
     * Returns the selected signing key, or null when a suitable key is selected automatically.
     * @see OpenPgpSigningConfigBuilder#signingKeyId(long)
     */
    @Nullable
    public Long getSigningKeyId() {
        return signingKeyId;
    }

    /** @see OpenPgpSigningConfigBuilder#hashAlgorithm(HashAlgorithm) */
    @NotNull
    public HashAlgorithm getHashAlgorithm() {
        return hashAlgorithm;
    }

    @Override
    public String toString() {
        return "OpenPgpSigningConfig{secretKeyRing=***, passphrase=***, signingKeyId="
                + (signingKeyId == null ? "automatic" : Long.toHexString(signingKeyId))
                + ", hashAlgorithm=" + hashAlgorithm + '}';
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OpenPgpSigningConfig)) {
            return false;
        }
        final OpenPgpSigningConfig that = (OpenPgpSigningConfig) other;
        return Arrays.equals(secretKeyRing, that.secretKeyRing)
                && Arrays.equals(passphrase, that.passphrase)
                && java.util.Objects.equals(signingKeyId, that.signingKeyId)
                && hashAlgorithm == that.hashAlgorithm;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(secretKeyRing);
        result = 31 * result + Arrays.hashCode(passphrase);
        result = 31 * result + (signingKeyId != null ? signingKeyId.hashCode() : 0);
        result = 31 * result + hashAlgorithm.hashCode();
        return result;
    }

    public static final class OpenPgpSigningConfigBuilder {
        private byte[] secretKeyRing;
        private char[] passphrase = new char[0];
        private Long signingKeyId;
        private HashAlgorithm hashAlgorithm = DEFAULT_OPENPGP_SIGNING_HASH_ALGORITHM;

        public OpenPgpSigningConfigBuilder secretKeyRing(final byte[] secretKeyRing) {
            this.secretKeyRing = requireNonNull(secretKeyRing, "secretKeyRing").clone();
            return this;
        }

        public OpenPgpSigningConfigBuilder passphrase(final char[] passphrase) {
            this.passphrase = requireNonNull(passphrase, "passphrase").clone();
            return this;
        }

        public OpenPgpSigningConfigBuilder passphrase(final String passphrase) {
            return passphrase(requireNonNull(passphrase, "passphrase").toCharArray());
        }

        /**
         * Chooses a particular signing key from the supplied secret key ring instead of selecting a suitable key automatically.
         * Clear the preference with {@link #clearSigningKeyId()} to return to automatic selection without replacing the key ring or passphrase.
         *
         * @param signingKeyId Identifier of the signing key to use.
         */
        public OpenPgpSigningConfigBuilder signingKeyId(final long signingKeyId) {
            this.signingKeyId = signingKeyId;
            return this;
        }

        /** @see #signingKeyId(long) */
        public OpenPgpSigningConfigBuilder clearSigningKeyId() {
            signingKeyId = null;
            return this;
        }

        /**
         * Chooses the hash used to create the OpenPGP signature. The default is
         * {@link org.simplejavamail.api.email.EmailStartingBuilder#DEFAULT_OPENPGP_SIGNING_HASH_ALGORITHM SHA-256}.
         * Calling this method again replaces the algorithm; {@link #resetHashAlgorithm()} restores the default without changing signing material.
         *
         * @param hashAlgorithm Supported hash to use for signing.
         */
        public OpenPgpSigningConfigBuilder hashAlgorithm(final HashAlgorithm hashAlgorithm) {
            this.hashAlgorithm = requireNonNull(hashAlgorithm, "hashAlgorithm");
            return this;
        }

        /** @see #hashAlgorithm(HashAlgorithm) */
        public OpenPgpSigningConfigBuilder resetHashAlgorithm() {
            return hashAlgorithm(DEFAULT_OPENPGP_SIGNING_HASH_ALGORITHM);
        }

        public OpenPgpSigningConfig build() {
            return new OpenPgpSigningConfig(secretKeyRing, passphrase, signingKeyId, hashAlgorithm);
        }

        @Override
        public String toString() {
            return "OpenPgpSigningConfigBuilder{secretKeyRing=***, passphrase=***, signingKeyId="
                    + (signingKeyId == null ? "automatic" : Long.toHexString(signingKeyId))
                    + ", hashAlgorithm=" + hashAlgorithm + '}';
        }
    }
}
