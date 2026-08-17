package org.simplejavamail.api.email.config;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Public-key material and algorithm preference for an RFC 3156 encrypted message. */
public final class OpenPgpEncryptionConfig {

    public enum SymmetricAlgorithm {
        AES_128, AES_192, AES_256
    }

    private final List<byte[]> recipientPublicKeyRings;
    @NotNull
    private final SymmetricAlgorithm symmetricAlgorithm;

    private OpenPgpEncryptionConfig(final List<byte[]> recipientPublicKeyRings,
                                    @NotNull final SymmetricAlgorithm symmetricAlgorithm) {
        if (recipientPublicKeyRings == null || recipientPublicKeyRings.isEmpty()) {
            throw new IllegalArgumentException("At least one recipient OpenPGP public key ring is required");
        }
        this.recipientPublicKeyRings = immutableDeepCopy(recipientPublicKeyRings);
        this.symmetricAlgorithm = requireNonNull(symmetricAlgorithm, "symmetricAlgorithm");
    }

    public static OpenPgpEncryptionConfigBuilder builder() {
        return new OpenPgpEncryptionConfigBuilder();
    }

    public List<byte[]> getRecipientPublicKeyRings() {
        return mutableDeepCopy(recipientPublicKeyRings);
    }

    @NotNull
    public SymmetricAlgorithm getSymmetricAlgorithm() {
        return symmetricAlgorithm;
    }

    @Override
    public String toString() {
        return "OpenPgpEncryptionConfig{recipientPublicKeyRings=" + recipientPublicKeyRings.size()
                + ", symmetricAlgorithm=" + symmetricAlgorithm + '}';
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) return true;
        if (!(other instanceof OpenPgpEncryptionConfig)) return false;
        final OpenPgpEncryptionConfig that = (OpenPgpEncryptionConfig) other;
        if (symmetricAlgorithm != that.symmetricAlgorithm
                || recipientPublicKeyRings.size() != that.recipientPublicKeyRings.size()) {
            return false;
        }
        for (int i = 0; i < recipientPublicKeyRings.size(); i++) {
            if (!java.util.Arrays.equals(recipientPublicKeyRings.get(i), that.recipientPublicKeyRings.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = symmetricAlgorithm.hashCode();
        for (byte[] keyRing : recipientPublicKeyRings) {
            result = 31 * result + java.util.Arrays.hashCode(keyRing);
        }
        return result;
    }

    private static List<byte[]> immutableDeepCopy(final List<byte[]> values) {
        return Collections.unmodifiableList(mutableDeepCopy(values));
    }

    private static List<byte[]> mutableDeepCopy(final List<byte[]> values) {
        final List<byte[]> copy = new ArrayList<>();
        for (byte[] value : values) {
            if (value == null || value.length == 0) {
                throw new IllegalArgumentException("OpenPGP public key rings cannot be null or empty");
            }
            copy.add(value.clone());
        }
        return copy;
    }

    public static final class OpenPgpEncryptionConfigBuilder {
        private final List<byte[]> recipientPublicKeyRings = new ArrayList<>();
        private SymmetricAlgorithm symmetricAlgorithm = SymmetricAlgorithm.AES_256;

        public OpenPgpEncryptionConfigBuilder addRecipientPublicKeyRing(final byte[] publicKeyRing) {
            recipientPublicKeyRings.add(requireNonNull(publicKeyRing, "publicKeyRing").clone());
            return this;
        }

        public OpenPgpEncryptionConfigBuilder recipientPublicKeyRings(final List<byte[]> publicKeyRings) {
            recipientPublicKeyRings.clear();
            recipientPublicKeyRings.addAll(mutableDeepCopy(requireNonNull(publicKeyRings, "publicKeyRings")));
            return this;
        }

        public OpenPgpEncryptionConfigBuilder symmetricAlgorithm(final SymmetricAlgorithm symmetricAlgorithm) {
            this.symmetricAlgorithm = requireNonNull(symmetricAlgorithm, "symmetricAlgorithm");
            return this;
        }

        public OpenPgpEncryptionConfig build() {
            return new OpenPgpEncryptionConfig(recipientPublicKeyRings, symmetricAlgorithm);
        }
    }
}
