package org.simplejavamail.api.email.config;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.internal.util.KeyMaterialReadException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.String.format;
import static org.simplejavamail.internal.util.MiscUtil.readInputStreamToBytes;

/**
 * S/MIME signing configuration. Since 9.2.0 this object is serializable so an {@code Email} keeps its signing behavior across a Java serialization
 * round trip. Its serialized form includes the complete {@link Pkcs12Config}, including private-key material and passwords, and must be protected
 * as sensitive data.
 *
 * @see SmimeSigningConfigBuilder#signatureAlgorithm(String)
 * @see EmailPopulatingBuilder#signWithSmime(SmimeSigningConfig)
 */
@ToString
@EqualsAndHashCode
@AllArgsConstructor
public class SmimeSigningConfig implements Serializable {

    private static final long serialVersionUID = 1234567L;

    /**
     * @see EmailPopulatingBuilder#signWithSmime(SmimeSigningConfig)
     * @see org.simplejavamail.api.email.config.SmimeSigningConfig.SmimeSigningConfigBuilder#pkcs12Config(File, String, String, String)
     */
    @NotNull
    private final Pkcs12Config pkcs12Config;
    @Nullable
    private final String signatureAlgorithm;

    /** @see SmimeSigningConfigBuilder#pkcs12Config(Pkcs12Config) */
    @NotNull
    public Pkcs12Config getPkcs12Config() {
        return pkcs12Config;
    }

    /** @see SmimeSigningConfigBuilder#signatureAlgorithm(String) */
    @Nullable
    public String getSignatureAlgorithm() {
        return signatureAlgorithm;
    }

    public static SmimeSigningConfigBuilder builder() {
        return new SmimeSigningConfigBuilder();
    }

    @ToString
    public static class SmimeSigningConfigBuilder {
        private Pkcs12Config pkcs12Config;
        private String signatureAlgorithm;

        /**
         * @see EmailPopulatingBuilder#signWithSmime(SmimeSigningConfig)
         * @see org.simplejavamail.api.email.config.SmimeSigningConfig.SmimeSigningConfigBuilder#pkcs12Config(File, String, String, String)
         */
        public SmimeSigningConfigBuilder pkcs12Config(@NotNull Pkcs12Config pkcs12Config) {
            this.pkcs12Config = pkcs12Config;
            return this;
        }

        /**
         * Reads and closes the PKCS12 key-store file immediately, retaining the complete store bytes, passwords and selected alias.
         * The actual S/MIME signature is produced when converting or sending using the optional S/MIME module.
         * Select the signature algorithm separately with {@link #signatureAlgorithm(String)}; an omitted algorithm keeps the module's default.
         * @param pkcs12StoreFile The key store file to use to find the indicated key.
         * @param storePassword The store's password.
         * @param keyAlias The name of the certificate in the key store to use.
         * @param keyPassword The password of the certificate.
         * @return This builder.
         * @throws MailException With kind {@link MailFailureKind#PREPARATION} if opening, reading or closing the file fails with an {@link IOException}.
         * @see #pkcs12Config(Path, String, String, String)
         * @see EmailPopulatingBuilder#signWithSmime(SmimeSigningConfig)
         */
        public SmimeSigningConfigBuilder pkcs12Config(@NotNull File pkcs12StoreFile, @NotNull String storePassword,
                @NotNull String keyAlias, @NotNull String keyPassword) {
            return pkcs12Config(pkcs12StoreFile.toPath(), storePassword, keyAlias, keyPassword);
        }

        /**
         * Reads the PKCS12 store bytes immediately and closes the stream opened for the path.
         *
         * The selected alias and passwords are retained with the bytes. Select the signing algorithm separately through
         * {@link #signatureAlgorithm(String)}; null keeps the module's default. This works with non-default Java filesystems.
         * @param pkcs12StorePath The key store path to use to find the indicated key.
         * @param storePassword Password for the store.
         * @param keyAlias Alias of the signing key.
         * @param keyPassword Password for the signing key.
         * @return This builder.
         * @throws MailException With kind {@link MailFailureKind#PREPARATION} if opening, reading or closing fails with an {@link IOException}.
         *         The original I/O cause retains any suppressed closing failure.
         */
        public SmimeSigningConfigBuilder pkcs12Config(@NotNull final Path pkcs12StorePath, @NotNull final String storePassword,
                @NotNull final String keyAlias, @NotNull final String keyPassword) {
            try (InputStream pkcs12StoreStream = Files.newInputStream(pkcs12StorePath)) {
                return pkcs12Config(readInputStreamToBytes(pkcs12StoreStream), storePassword, keyAlias, keyPassword);
            } catch (IOException e) {
                throw new KeyMaterialReadException(format("Error reading from path: %s", pkcs12StorePath), e);
            }
        }

        /**
         * Reads the PKCS12 store immediately and delegates to {@link #pkcs12Config(byte[], String, String, String)}.
         * The supplied stream remains caller-owned and is not closed.
         * @param pkcs12StoreStream Application-owned PKCS12 store stream.
         * @param storePassword The store's password.
         * @param keyAlias The signing-key alias.
         * @param keyPassword The signing-key password.
         * @return This builder.
         * @throws MailException With kind {@link MailFailureKind#PREPARATION} if reading fails with an {@link IOException}, retained as the cause.
         */
        public SmimeSigningConfigBuilder pkcs12Config(@NotNull InputStream pkcs12StoreStream, @NotNull String storePassword,
                @NotNull String keyAlias, @NotNull String keyPassword) {
            final byte[] pkcs12StoreData;
            try {
                pkcs12StoreData = readInputStreamToBytes(pkcs12StoreStream);
            } catch (IOException e) {
                throw new KeyMaterialReadException("Was unable to read S/MIME data from input stream", e);
            }
            return pkcs12Config(pkcs12StoreData, storePassword, keyAlias, keyPassword);
        }

        /**
         * Copies the complete PKCS12 bytes immediately and delegates to {@link #pkcs12Config(Pkcs12Config)}.
         * @param pkcs12StoreData Complete PKCS12 store bytes.
         * @param storePassword The store's password.
         * @param keyAlias The signing-key alias.
         * @param keyPassword The signing-key password.
         * @return This builder.
         */
        public SmimeSigningConfigBuilder pkcs12Config(byte @NotNull [] pkcs12StoreData, @NotNull String storePassword,
                @NotNull String keyAlias, @NotNull String keyPassword) {
            return pkcs12Config(Pkcs12Config.builder()
                    .pkcs12Store(pkcs12StoreData)
                    .storePassword(storePassword)
                    .keyAlias(keyAlias)
                    .keyPassword(keyPassword)
                    .build());
        }

        /**
         * Chooses the algorithm used to sign the message, for example {@code SHA256withRSA}. Use a name supported by the S/MIME module.
         * Null leaves the choice to that module. Calling again replaces the preference; {@link #clearSignatureAlgorithm()}
         * removes it without replacing the PKCS12 store or signing key.
         * <p>
         * The S/MIME module defaults to {@code SHA256withRSA}. Other commonly supported names include:
         * <ul>
         *     <li>RSA: {@code SHA256withRSA}, {@code SHA384withRSA}, {@code SHA512withRSA}.</li>
         *     <li>RSA-PSS: {@code SHA256withRSAandMGF1}, {@code SHA384withRSAandMGF1}, {@code SHA512withRSAandMGF1}.</li>
         *     <li>ECDSA: {@code SHA256withECDSA}, {@code SHA384withECDSA}, {@code SHA512withECDSA}.</li>
         *     <li>DSA: {@code SHA256withDSA}, {@code SHA384withDSA}, {@code SHA512withDSA}.</li>
         *     <li>SHA-3 variants: {@code SHA3-256withRSA}, {@code SHA3-384withRSA}, {@code SHA3-512withRSA},
         *         {@code SHA3-256withECDSA}, {@code SHA3-384withECDSA}, {@code SHA3-512withECDSA}.</li>
         *     <li>SM2: {@code SHA256withSM2} and {@code SM3withSM2}.</li>
         * </ul>
         * These are examples, not a whitelist. The name must be supported by the module's cryptographic provider, match your private key,
         * and be understood by recipients' mail clients. Bouncy Castle's {@code DefaultSignatureAlgorithmIdentifierFinder} lists its recognized names;
         * recognizing a name alone does not establish compatibility with a particular key or client.
         *
         * @param signatureAlgorithm S/MIME signature algorithm name, or null for the module's default.
         * @see EmailPopulatingBuilder#signWithSmime(SmimeSigningConfig)
         */
        public SmimeSigningConfigBuilder signatureAlgorithm(@Nullable String signatureAlgorithm) {
            this.signatureAlgorithm = signatureAlgorithm;
            return this;
        }

        /** @see #pkcs12Config(Pkcs12Config) */
        @Nullable
        public Pkcs12Config getPkcs12Config() {
            return pkcs12Config;
        }

        /** @see #signatureAlgorithm(String) */
        @Nullable
        public String getSignatureAlgorithm() {
            return signatureAlgorithm;
        }

        /** @see #signatureAlgorithm(String) */
        public SmimeSigningConfigBuilder clearSignatureAlgorithm() {
            signatureAlgorithm = null;
            return this;
        }

        public SmimeSigningConfig build() {
            return new SmimeSigningConfig(this.pkcs12Config, this.signatureAlgorithm);
        }
    }
}
