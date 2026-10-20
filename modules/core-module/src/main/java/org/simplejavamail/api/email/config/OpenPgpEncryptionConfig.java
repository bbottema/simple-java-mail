package org.simplejavamail.api.email.config;

import org.jetbrains.annotations.NotNull;

import java.io.InvalidObjectException;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static org.simplejavamail.api.email.EmailStartingBuilder.DEFAULT_OPENPGP_ENCRYPTION_SYMMETRIC_ALGORITHM;

/**
 * Public-key material and algorithm preference for an RFC 3156 encrypted message. Key-ring lists and their arrays are
 * defensively copied. Java serialization retains all recipient public key rings and the cipher preference, including in
 * an {@link org.simplejavamail.api.email.Email} snapshot. This encryption-only configuration contains no secret keys or passphrase.
 */
public final class OpenPgpEncryptionConfig implements Serializable {

    private static final long serialVersionUID = 1L;

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

    /** Reapply validation and detach lists/arrays that may be aliased elsewhere in the serialized graph. */
    private Object readResolve() throws ObjectStreamException {
        try {
            return new OpenPgpEncryptionConfig(recipientPublicKeyRings, symmetricAlgorithm);
        } catch (RuntimeException cause) {
            final InvalidObjectException failure = new InvalidObjectException("Invalid OpenPGP encryption configuration");
            failure.initCause(cause);
            throw failure;
        }
    }

    public static OpenPgpEncryptionConfigBuilder builder() {
        return new OpenPgpEncryptionConfigBuilder();
    }

    /**
     * Returns a modifiable copy of the supplied key rings, with independent arrays.
     *
     * @see OpenPgpEncryptionConfigBuilder#recipientPublicKeyRings(List)
     */
    public List<byte[]> getRecipientPublicKeyRings() {
        return mutableDeepCopy(recipientPublicKeyRings);
    }

    /** @see OpenPgpEncryptionConfigBuilder#symmetricAlgorithm(SymmetricAlgorithm) */
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
        private SymmetricAlgorithm symmetricAlgorithm = DEFAULT_OPENPGP_ENCRYPTION_SYMMETRIC_ALGORITHM;

        /**
         * Returns a modifiable copy of the stored key rings, with independent arrays. Inspection does not validate unfinished key material.
         *
         * @see #recipientPublicKeyRings(List)
         */
        @NotNull
        public List<byte[]> getRecipientPublicKeyRings() {
            return recipientPublicKeyRings.stream().map(byte[]::clone).collect(Collectors.toCollection(ArrayList::new));
        }

        /** @see #recipientPublicKeyRings(List) */
        @NotNull
        public OpenPgpEncryptionConfigBuilder clearRecipientPublicKeyRings() {
            recipientPublicKeyRings.clear();
            return this;
        }

        /** @see #symmetricAlgorithm(SymmetricAlgorithm) */
        @NotNull
        public SymmetricAlgorithm getSymmetricAlgorithm() {
            return symmetricAlgorithm;
        }

        /**
         * Adds another recipient's public key ring, retaining a copy of the supplied bytes. Other recipients and the selected cipher are retained.
         *
         * @see #recipientPublicKeyRings(List)
         */
        public OpenPgpEncryptionConfigBuilder addRecipientPublicKeyRing(final byte[] publicKeyRing) {
            recipientPublicKeyRings.add(requireNonNull(publicKeyRing, "publicKeyRing").clone());
            return this;
        }

        /**
         * Replaces the recipient key rings, retaining copies of the list and its arrays. Use {@link #clearRecipientPublicKeyRings()} to remove
         * them without changing the cipher. At least one nonempty key ring is needed when {@link #build()} creates the configuration.
         */
        public OpenPgpEncryptionConfigBuilder recipientPublicKeyRings(final List<byte[]> publicKeyRings) {
            recipientPublicKeyRings.clear();
            recipientPublicKeyRings.addAll(mutableDeepCopy(requireNonNull(publicKeyRings, "publicKeyRings")));
            return this;
        }

        /**
         * Chooses the cipher used to encrypt the message content for the configured recipients. The default is
         * {@link org.simplejavamail.api.email.EmailStartingBuilder#DEFAULT_OPENPGP_ENCRYPTION_SYMMETRIC_ALGORITHM AES-256}.
         * Calling this method again replaces the algorithm; {@link #resetSymmetricAlgorithm()} restores the default without replacing recipient keys.
         *
         * @param symmetricAlgorithm Supported content-encryption cipher.
         */
        public OpenPgpEncryptionConfigBuilder symmetricAlgorithm(final SymmetricAlgorithm symmetricAlgorithm) {
            this.symmetricAlgorithm = requireNonNull(symmetricAlgorithm, "symmetricAlgorithm");
            return this;
        }

        /** @see #symmetricAlgorithm(SymmetricAlgorithm) */
        public OpenPgpEncryptionConfigBuilder resetSymmetricAlgorithm() {
            return symmetricAlgorithm(DEFAULT_OPENPGP_ENCRYPTION_SYMMETRIC_ALGORITHM);
        }

        public OpenPgpEncryptionConfig build() {
            return new OpenPgpEncryptionConfig(recipientPublicKeyRings, symmetricAlgorithm);
        }
    }
}
