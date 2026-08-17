package org.simplejavamail.api.email.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Key rings used while reading OpenPGP/MIME. Secret-key passphrases are held only in memory and this type is
 * intentionally not serializable.
 */
public final class OpenPgpReceiveConfig {

    public interface PassphraseProvider {
        @Nullable char[] passphraseFor(long keyId);
    }

    public static final class SecretKeyRing {
        private final byte[] keyRing;
        private final char[] passphrase;

        private SecretKeyRing(final byte[] keyRing, final char[] passphrase) {
            this.keyRing = requireNonNull(keyRing, "keyRing").clone();
            this.passphrase = requireNonNull(passphrase, "passphrase").clone();
            if (keyRing.length == 0) {
                throw new IllegalArgumentException("OpenPGP secret key ring cannot be empty");
            }
        }

        public byte[] getKeyRing() {
            return keyRing.clone();
        }

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
        this.decryptionKeyRings = Collections.unmodifiableList(new ArrayList<>(decryptionKeyRings));
        this.passphraseProvider = passphraseProvider;
    }

    public static OpenPgpReceiveConfigBuilder builder() {
        return new OpenPgpReceiveConfigBuilder();
    }

    public List<byte[]> getVerificationKeyRings() {
        return mutableKeyRingCopy(verificationKeyRings);
    }

    public List<SecretKeyRing> getDecryptionKeyRings() {
        return new ArrayList<>(decryptionKeyRings);
    }

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

        public OpenPgpReceiveConfigBuilder addVerificationKeyRing(final byte[] publicKeyRing) {
            verificationKeyRings.add(requireNonNull(publicKeyRing, "publicKeyRing").clone());
            return this;
        }

        public OpenPgpReceiveConfigBuilder addDecryptionKeyRing(final byte[] secretKeyRing,
                                                                 final char[] passphrase) {
            decryptionKeyRings.add(new SecretKeyRing(secretKeyRing, passphrase));
            return this;
        }

        public OpenPgpReceiveConfigBuilder addDecryptionKeyRing(final byte[] secretKeyRing,
                                                                 final String passphrase) {
            return addDecryptionKeyRing(secretKeyRing, requireNonNull(passphrase, "passphrase").toCharArray());
        }

        public OpenPgpReceiveConfigBuilder passphraseProvider(final PassphraseProvider passphraseProvider) {
            this.passphraseProvider = requireNonNull(passphraseProvider, "passphraseProvider");
            return this;
        }

        public OpenPgpReceiveConfig build() {
            return new OpenPgpReceiveConfig(verificationKeyRings, decryptionKeyRings, passphraseProvider);
        }
    }
}
