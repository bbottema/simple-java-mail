package org.simplejavamail.api.email.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Key rings used while reading OpenPGP/MIME. Secret-key passphrases are held only in memory and this type is
 * intentionally not serializable.
 */
public final class OpenPgpReceiveConfig {

    public interface PassphraseProvider {
        @Nullable char[] passphraseFor(long keyId);
    }

    /**
     * An immutable view of supplied decryption material. A builder's inspection view may contain unfinished empty key bytes;
     * a completed {@link OpenPgpReceiveConfig} requires every supplied key ring to be nonempty.
     */
    public static final class SecretKeyRing {
        private final byte[] keyRing;
        private final char[] passphrase;

        private SecretKeyRing(final byte[] keyRing, final char[] passphrase) {
            this.keyRing = requireNonNull(keyRing, "keyRing").clone();
            this.passphrase = requireNonNull(passphrase, "passphrase").clone();
        }

        /**
         * Returns a copy of the supplied key bytes, including an unfinished empty array when inspecting a builder.
         * @see OpenPgpReceiveConfigBuilder#addDecryptionKeyRing(byte[], char[])
         */
        public byte[] getKeyRing() {
            return keyRing.clone();
        }

        /**
         * Returns a copy of the supplied passphrase.
         * @see OpenPgpReceiveConfigBuilder#addDecryptionKeyRing(byte[], char[])
         */
        public char[] getPassphrase() {
            return passphrase.clone();
        }

        @Override
        public String toString() {
            return "SecretKeyRing{keyRing=***, passphrase=***}";
        }
    }

    private final List<byte[]> verificationKeyRings;
    private final List<SecretKeyRing> decryptionKeyRings;
    @Nullable
    private final PassphraseProvider passphraseProvider;

    private OpenPgpReceiveConfig(final List<byte[]> verificationKeyRings,
                                 final List<SecretKeyRing> decryptionKeyRings,
                                 @Nullable final PassphraseProvider passphraseProvider) {
        this.verificationKeyRings = immutableKeyRingCopy(verificationKeyRings);
        for (SecretKeyRing keyRing : decryptionKeyRings) {
            if (keyRing.keyRing.length == 0) {
                throw new IllegalArgumentException("OpenPGP secret key ring cannot be empty");
            }
        }
        this.decryptionKeyRings = Collections.unmodifiableList(new ArrayList<>(decryptionKeyRings));
        this.passphraseProvider = passphraseProvider;
    }

    public static OpenPgpReceiveConfigBuilder builder() {
        return new OpenPgpReceiveConfigBuilder();
    }

    /**
     * Returns a modifiable copy of the verification key rings, with independent arrays.
     *
     * @see OpenPgpReceiveConfigBuilder#addVerificationKeyRing(byte[])
     */
    public List<byte[]> getVerificationKeyRings() {
        return mutableKeyRingCopy(verificationKeyRings);
    }

    /**
     * Returns a modifiable copy of the decryption-key list. Its entries return copies of their key and passphrase arrays.
     *
     * @see OpenPgpReceiveConfigBuilder#addDecryptionKeyRing(byte[], char[])
     */
    public List<SecretKeyRing> getDecryptionKeyRings() {
        return new ArrayList<>(decryptionKeyRings);
    }

    /** @see OpenPgpReceiveConfigBuilder#passphraseProvider(PassphraseProvider) */
    @Nullable
    public PassphraseProvider getPassphraseProvider() {
        return passphraseProvider;
    }

    @Override
    public String toString() {
        return "OpenPgpReceiveConfig{verificationKeyRings=" + verificationKeyRings.size()
                + ", decryptionKeyRings=" + decryptionKeyRings.size()
                + ", passphraseProvider=" + (passphraseProvider == null ? "none" : "configured") + '}';
    }

    private static List<byte[]> immutableKeyRingCopy(final List<byte[]> values) {
        return Collections.unmodifiableList(mutableKeyRingCopy(values));
    }

    private static List<byte[]> mutableKeyRingCopy(final List<byte[]> values) {
        final List<byte[]> result = new ArrayList<>();
        for (byte[] value : values) {
            if (value == null || value.length == 0) {
                throw new IllegalArgumentException("OpenPGP key rings cannot be null or empty");
            }
            result.add(value.clone());
        }
        return result;
    }

    public static final class OpenPgpReceiveConfigBuilder {
        private final List<byte[]> verificationKeyRings = new ArrayList<>();
        private final List<SecretKeyRing> decryptionKeyRings = new ArrayList<>();
        private PassphraseProvider passphraseProvider;

        /**
         * Returns a modifiable copy of the stored verification keys, with independent arrays. Inspection does not validate unfinished key material.
         *
         * @see #addVerificationKeyRing(byte[])
         */
        @NotNull
        public List<byte[]> getVerificationKeyRings() {
            return verificationKeyRings.stream().map(byte[]::clone).collect(Collectors.toCollection(ArrayList::new));
        }

        /** @see #addVerificationKeyRing(byte[]) */
        @NotNull
        public OpenPgpReceiveConfigBuilder clearVerificationKeyRings() {
            verificationKeyRings.clear();
            return this;
        }

        /**
         * Returns a modifiable copy of the stored decryption-key list. Its entries return copies of their key and passphrase arrays.
         * Inspection retains unfinished empty key bytes without validation and does not invoke the passphrase provider.
         *
         * @see #addDecryptionKeyRing(byte[], char[])
         */
        @NotNull
        public List<SecretKeyRing> getDecryptionKeyRings() {
            return new ArrayList<>(decryptionKeyRings);
        }

        /** @see #addDecryptionKeyRing(byte[], char[]) */
        @NotNull
        public OpenPgpReceiveConfigBuilder clearDecryptionKeyRings() {
            decryptionKeyRings.clear();
            return this;
        }

        /**
         * Returns the supplied provider, or {@code null} when none was supplied, without invoking it.
         *
         * @see #passphraseProvider(PassphraseProvider)
         */
        @Nullable
        public PassphraseProvider getPassphraseProvider() {
            return passphraseProvider;
        }

        /** @see #passphraseProvider(PassphraseProvider) */
        @NotNull
        public OpenPgpReceiveConfigBuilder clearPassphraseProvider() {
            passphraseProvider = null;
            return this;
        }

        /**
         * Adds a public key ring for checking signatures, retaining a copy of its bytes.
         * Empty bytes may be stored and inspected while configuring the builder; {@link #build()} rejects empty key rings.
         * Use {@link #clearVerificationKeyRings()} to remove only verification keys, retaining decryption keys and the passphrase provider.
         * @throws NullPointerException if the key ring is null; previous choices are retained.
         */
        public OpenPgpReceiveConfigBuilder addVerificationKeyRing(final byte[] publicKeyRing) {
            verificationKeyRings.add(requireNonNull(publicKeyRing, "publicKeyRing").clone());
            return this;
        }

        /**
         * Adds a secret key ring and its passphrase for decrypting received messages, retaining copies of both arrays.
         * Empty key bytes may be stored and inspected while configuring the builder; {@link #build()} rejects empty key rings.
         * Collection and inspection do not parse keys, load the OpenPGP module or invoke the passphrase provider.
         * Use an empty passphrase for an unprotected key. A configured {@link #passphraseProvider(PassphraseProvider)} may supply another passphrase.
         * {@link #clearDecryptionKeyRings()} removes only these keys, retaining verification keys and the provider.
         * @throws NullPointerException if the key ring or passphrase is null; previous choices are retained.
         */
        public OpenPgpReceiveConfigBuilder addDecryptionKeyRing(final byte[] secretKeyRing,
                                                                 final char[] passphrase) {
            decryptionKeyRings.add(new SecretKeyRing(secretKeyRing, passphrase));
            return this;
        }

        /**
         * Adds a decryption key ring with a passphrase supplied as text.
         * Retains a copy of the key bytes and passphrase, including unfinished empty key bytes until {@link #build()}.
         *
         * @throws NullPointerException if the key ring or passphrase is null; previous choices are retained.
         * @see #addDecryptionKeyRing(byte[], char[])
         */
        public OpenPgpReceiveConfigBuilder addDecryptionKeyRing(final byte[] secretKeyRing,
                                                                 final String passphrase) {
            return addDecryptionKeyRing(secretKeyRing, requireNonNull(passphrase, "passphrase").toCharArray());
        }

        /**
         * Obtains a passphrase for each requested key while reading a message. A later call replaces the provider.
         * Returning {@code null} for a key retains the passphrase supplied with that key ring.
         * Use {@link #clearPassphraseProvider()} to return to the passphrases supplied with the decryption key rings.
         * The callback is retained for later use; configuring or inspecting it never invokes it.
         */
        public OpenPgpReceiveConfigBuilder passphraseProvider(final PassphraseProvider passphraseProvider) {
            this.passphraseProvider = requireNonNull(passphraseProvider, "passphraseProvider");
            return this;
        }

        /**
         * Creates an independent configuration, rejecting every supplied empty verification or decryption key ring.
         * Either list may be empty. This checks data shape without parsing keys, loading the OpenPGP module or invoking the passphrase provider.
         * @throws IllegalArgumentException if any supplied key ring is empty.
         */
        public OpenPgpReceiveConfig build() {
            return new OpenPgpReceiveConfig(verificationKeyRings, decryptionKeyRings, passphraseProvider);
        }
    }
}
