package org.simplejavamail.api.email.config;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.internal.util.CertificationUtil;
import org.simplejavamail.internal.util.KeyMaterialReadException;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import static java.lang.String.format;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * @see SmimeEncryptionConfigBuilder#keyEncapsulationAlgorithm(String)
 * @see SmimeEncryptionConfigBuilder#cipherAlgorithm(String)
 * @see EmailPopulatingBuilder#encryptWithSmime(SmimeEncryptionConfig)
 */
@ToString
@EqualsAndHashCode
@AllArgsConstructor
public class SmimeEncryptionConfig implements Serializable {

    private static final long serialVersionUID = 1234567L;

    @NotNull
    final X509Certificate x509Certificate;
    @Nullable
    final String keyEncapsulationAlgorithm;

    @Nullable
    final String cipherAlgorithm;

    /** @see SmimeEncryptionConfigBuilder#x509Certificate(X509Certificate) */
    @NotNull
    public X509Certificate getX509Certificate() {
        return x509Certificate;
    }

    /** @see SmimeEncryptionConfigBuilder#keyEncapsulationAlgorithm(String) */
    @Nullable
    public String getKeyEncapsulationAlgorithm() {
        return keyEncapsulationAlgorithm;
    }

    /** @see SmimeEncryptionConfigBuilder#cipherAlgorithm(String) */
    @Nullable
    public String getCipherAlgorithm() {
        return cipherAlgorithm;
    }

    public static SmimeEncryptionConfigBuilder builder() {
        return new SmimeEncryptionConfigBuilder();
    }

    @ToString
    public static class SmimeEncryptionConfigBuilder {
        private X509Certificate x509Certificate;
        private String keyEncapsulationAlgorithm;
        private String cipherAlgorithm;

        /**
         * Selects the required recipient certificate, retaining its identity without interpreting it or loading the S/MIME module.
         * Calling again replaces the certificate while retaining both algorithm preferences. A null argument is rejected
         * before changing an earlier choice. Optional algorithm nulls still select their documented module defaults.
         *
         * @param x509Certificate The required recipient certificate, borrowed without copying.
         * @return This builder.
         * @throws IllegalArgumentException If the certificate is null; the previous selection remains intact.
         * @see EmailPopulatingBuilder#encryptWithSmime(SmimeEncryptionConfig)
         * @see org.simplejavamail.api.email.config.SmimeEncryptionConfig.SmimeEncryptionConfigBuilder#x509Certificate(File)
         */
        public SmimeEncryptionConfigBuilder x509Certificate(@NotNull final X509Certificate x509Certificate) {
            this.x509Certificate = checkNonEmptyArgument(x509Certificate, "x509Certificate");
            return this;
        }

        /**
         * Reads and closes the PEM file at this path, as with {@link #x509Certificate(Path)}.
         * @throws MailException With kind {@link MailFailureKind#PREPARATION} if opening, reading or closing the file fails with an {@link IOException}.
         */
        public SmimeEncryptionConfigBuilder x509Certificate(@NotNull final String pemFile) {
            return x509Certificate(new File(pemFile));
        }

        /**
         * Reads and closes the PEM file immediately, parsing the recipient's public X.509 certificate for encryption.
         * Select key and content algorithms separately through {@link #keyEncapsulationAlgorithm(String)} and {@link #cipherAlgorithm(String)};
         * omitted algorithms retain the S/MIME module's defaults. The optional S/MIME module performs encryption when converting or sending.
         * @param pemFile The recipient's public certificate to use for encryption.
         * @return This builder.
         * @throws MailException With kind {@link MailFailureKind#PREPARATION} if opening, reading or closing the file fails with an {@link IOException}.
         * @see #x509Certificate(Path)
         * @see EmailPopulatingBuilder#encryptWithSmime(SmimeEncryptionConfig)
         */
        public SmimeEncryptionConfigBuilder x509Certificate(@NotNull final File pemFile) {
            return x509Certificate(pemFile.toPath());
        }

        /**
         * Parses the certificate immediately and closes the stream opened for the path.
         *
         * This accepts non-default Java filesystems. Choose key and content algorithms separately through
         * {@link #keyEncapsulationAlgorithm(String)} and {@link #cipherAlgorithm(String)}; null retains the owning module's defaults.
         * @param pemPath The recipient's public certificate path to use for encryption.
         * @return This builder.
         * @throws MailException With kind {@link MailFailureKind#PREPARATION} if opening, reading or closing fails with an {@link IOException}.
         *         The original I/O cause retains any suppressed closing failure. Malformed certificate data retains its parsing failure.
         */
        public SmimeEncryptionConfigBuilder x509Certificate(@NotNull final Path pemPath) {
            try (InputStream pemStream = Files.newInputStream(pemPath)) {
                return x509Certificate(readCertificate(pemStream));
            } catch (IOException e) {
                throw new KeyMaterialReadException(format("Error reading from path: %s", pemPath), e);
            } catch (CertificateException e) {
                throw new IllegalStateException("Was unable to convert PEM data to X509 certificate", e);
            }
        }

        /**
         * Parses the recipient's public X.509 certificate immediately and delegates to {@link #x509Certificate(X509Certificate)}.
         * The supplied stream remains caller-owned and is not closed.
         * @param pemStream Application-owned PEM certificate stream.
         * @return This builder.
         * @throws MailException With kind {@link MailFailureKind#PREPARATION} if reading fails with an {@link IOException}, retained as the cause.
         *         Malformed certificate data retains its parsing failure.
         */
        public SmimeEncryptionConfigBuilder x509Certificate(@NotNull final InputStream pemStream) {
            try {
                return x509Certificate(readCertificate(pemStream));
            } catch (IOException e) {
                throw new KeyMaterialReadException("Was unable to read PEM data from input stream", e);
            } catch (CertificateException e) {
                throw new IllegalStateException("Was unable to convert PEM data to X509 certificate", e);
            }
        }

        private static X509Certificate readCertificate(final InputStream input) throws IOException, CertificateException {
            final CertificateInputStream tracked = new CertificateInputStream(input);
            try {
                final X509Certificate certificate = CertificationUtil.readFromPem(tracked);
                if (tracked.readFailure != null) {
                    throw tracked.readFailure;
                }
                return certificate;
            } catch (CertificateException e) {
                // Certificate providers also use IOException for malformed data; only an actual source failure is I/O here.
                if (tracked.readFailure != null) {
                    throw tracked.readFailure;
                }
                throw e;
            }
        }

        private static final class CertificateInputStream extends FilterInputStream {
            private IOException readFailure;

            private CertificateInputStream(final InputStream input) {
                super(input);
            }

            @Override public int read() throws IOException {
                try {
                    return in.read();
                } catch (IOException e) {
                    readFailure = e;
                    throw e;
                }
            }

            @Override public int read(final byte[] bytes, final int offset, final int length) throws IOException {
                try {
                    return in.read(bytes, offset, length);
                } catch (IOException e) {
                    readFailure = e;
                    throw e;
                }
            }
        }

        /**
         * Chooses how the S/MIME module protects the message's encryption key for the recipient, for example {@code RSA_OAEP_SHA256}.
         * Use a name supported by that module. Null leaves its default in place. Calling again replaces the preference;
         * {@link #clearKeyEncapsulationAlgorithm()} removes it without replacing the certificate or content-encryption algorithm.
         * <p>
         * The module supports {@code RSA} (the compatibility default), {@code RSA_OAEP_SHA224}, {@code RSA_OAEP_SHA256},
         * {@code RSA_OAEP_SHA384}, and {@code RSA_OAEP_SHA512}. The OAEP choices use the named digest for RSA key encapsulation.
         * Choose one supported by the recipient's certificate and mail client. The supported names are defined by the module's
         * {@code org.simplejavamail.utils.mail.smime.KeyEncapsulationAlgorithm}.
         *
         * @param keyEncapsulationAlgorithm S/MIME key-encapsulation algorithm name, or null for the module's default.
         * @see EmailPopulatingBuilder#encryptWithSmime(SmimeEncryptionConfig)
         */
        public SmimeEncryptionConfigBuilder keyEncapsulationAlgorithm(@Nullable String keyEncapsulationAlgorithm) {
            this.keyEncapsulationAlgorithm = keyEncapsulationAlgorithm;
            return this;
        }

        /**
         * Chooses the algorithm used to encrypt message content, for example {@code AES256_CBC}. Use a name supported by the S/MIME module.
         * Null leaves the choice to that module. Calling again replaces the preference; {@link #clearCipherAlgorithm()}
         * removes it without replacing the recipient certificate or key-encapsulation algorithm.
         * <p>
         * Common choices are {@code AES128_CBC}, {@code AES192_CBC}, and {@code AES256_CBC}. The module's compatibility default is
         * {@code DES_EDE3_CBC}; leaving this unset does not select AES. Choose a cipher supported by the recipient's mail client.
         * Names are resolved against Bouncy Castle's {@code CMSAlgorithm}; the module checks them when encryption is requested.
         *
         * @param cipherAlgorithm S/MIME content-encryption algorithm name, or null for the module's default.
         * @see EmailPopulatingBuilder#encryptWithSmime(SmimeEncryptionConfig)
         */
        public SmimeEncryptionConfigBuilder cipherAlgorithm(@Nullable String cipherAlgorithm) {
            this.cipherAlgorithm = cipherAlgorithm;
            return this;
        }

        /** @see #x509Certificate(X509Certificate) */
        @Nullable
        public X509Certificate getX509Certificate() {
            return x509Certificate;
        }

        /** @see #keyEncapsulationAlgorithm(String) */
        @Nullable
        public String getKeyEncapsulationAlgorithm() {
            return keyEncapsulationAlgorithm;
        }

        /** @see #cipherAlgorithm(String) */
        @Nullable
        public String getCipherAlgorithm() {
            return cipherAlgorithm;
        }

        /** @see #keyEncapsulationAlgorithm(String) */
        public SmimeEncryptionConfigBuilder clearKeyEncapsulationAlgorithm() {
            keyEncapsulationAlgorithm = null;
            return this;
        }

        /** @see #cipherAlgorithm(String) */
        public SmimeEncryptionConfigBuilder clearCipherAlgorithm() {
            cipherAlgorithm = null;
            return this;
        }

        public SmimeEncryptionConfig build() {
            return new SmimeEncryptionConfig(this.x509Certificate, this.keyEncapsulationAlgorithm, this.cipherAlgorithm);
        }
    }
}
