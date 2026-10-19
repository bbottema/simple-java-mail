package org.simplejavamail.api.email.config;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.config.Pkcs12Config;

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
     * @see EmailPopulatingBuilder#signWithSmime(File, String, String, String, String)
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
         * @see EmailPopulatingBuilder#signWithSmime(File, String, String, String, String)
         */
        public SmimeSigningConfigBuilder pkcs12Config(@NotNull Pkcs12Config pkcs12Config) {
            this.pkcs12Config = pkcs12Config;
            return this;
        }

        /**
         * Delegates to {@link #pkcs12Config(InputStream, String, String, String)}.
         */
        public SmimeSigningConfigBuilder pkcs12Config(@NotNull File pkcs12StoreFile, @NotNull String storePassword,
                @NotNull String keyAlias, @NotNull String keyPassword) {
            return pkcs12Config(pkcs12StoreFile.toPath(), storePassword, keyAlias, keyPassword);
        }

        /**
         * Reads the PKCS12 store immediately through {@link #pkcs12Config(InputStream, String, String, String)} and closes the stream opened for the path.
         *
         * @param pkcs12StorePath Path to the PKCS12 store.
         * @param storePassword Password for the store.
         * @param keyAlias Alias of the signing key.
         * @param keyPassword Password for the signing key.
         * @return This builder.
         */
        public SmimeSigningConfigBuilder pkcs12Config(@NotNull final Path pkcs12StorePath, @NotNull final String storePassword,
                @NotNull final String keyAlias, @NotNull final String keyPassword) {
            try (InputStream pkcs12StoreStream = Files.newInputStream(pkcs12StorePath)) {
                return pkcs12Config(pkcs12StoreStream, storePassword, keyAlias, keyPassword);
            } catch (IOException e) {
                throw new IllegalStateException(format("Error reading from path: %s", pkcs12StorePath), e);
            }
        }

        /**
         * Delegates to {@link #pkcs12Config(byte[], String, String, String)}.
         */
        public SmimeSigningConfigBuilder pkcs12Config(@NotNull InputStream pkcs12StoreStream, @NotNull String storePassword,
                @NotNull String keyAlias, @NotNull String keyPassword) {
            final byte[] pkcs12StoreData;
            try {
                pkcs12StoreData = readInputStreamToBytes(pkcs12StoreStream);
            } catch (IOException e) {
                throw new IllegalStateException("Was unable to read S/MIME data from input stream", e);
            }
            return pkcs12Config(pkcs12StoreData, storePassword, keyAlias, keyPassword);
        }

        /**
         * Delegates to {@link #pkcs12Config(Pkcs12Config)}.
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
