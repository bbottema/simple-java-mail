package org.simplejavamail.api.email.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.config.Pkcs12Config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import static java.lang.String.format;
import static org.simplejavamail.internal.util.MiscUtil.readInputStreamToBytes;

/**
 * @see #getSignatureAlgorithm()
 * @see EmailPopulatingBuilder#signWithSmime(SmimeSigningConfig)
 */
@ToString
@Getter
@EqualsAndHashCode
@AllArgsConstructor
public class SmimeSigningConfig {

    /**
     * @see EmailPopulatingBuilder#signWithSmime(SmimeSigningConfig)
     * @see EmailPopulatingBuilder#signWithSmime(File, String, String, String, String)
     */
    @NotNull
    private final Pkcs12Config pkcs12Config;
    /**
     * <p>Configuration for S/MIME signing, including the certificate chain and private key information,
     * along with the signature algorithm. The choice of signature algorithm affects the security
     * and compatibility of the S/MIME signature.</p>
     *
     * <p><strong>Default Algorithm:</strong> SHA256withRSA. This is widely supported and recommended
     * for most use cases due to its balance of security and performance.</p>
     *
     * <p><strong>Allowed Signature Algorithms:</strong> This following list focuses on the most commonly used
     * and secure algorithms. Algorithms like MD5 and SHA1 are not included due to their known security
     * vulnerabilities and are not recommended for new applications. For a comprehensive and up-to-date
     * list, including less common algorithms, refer to the Bouncy Castle's {@code DefaultSignatureAlgorithmIdentifierFinder}
     * class to find an exhaustive list.</p>
     *
     * <ul>
     *     <li>SHA256withRSA (Recommended)</li>
     *     <li>SHA384withRSA</li>
     *     <li>SHA512withRSA</li>
     *     <li>SHA256withECDSA</li>
     *     <li>SHA384withECDSA</li>
     *     <li>SHA512withECDSA</li>
     *     <li>SHA256withDSA</li>
     *     <li>SHA384withDSA</li>
     *     <li>SHA512withDSA</li>
     *     <li>SHA256withRSAandMGF1</li>
     *     <li>SHA384withRSAandMGF1</li>
     *     <li>SHA512withRSAandMGF1</li>
     *     <li>SHA3-256withRSA</li>
     *     <li>SHA3-384withRSA</li>
     *     <li>SHA3-512withRSA</li>
     *     <li>SHA3-256withECDSA</li>
     *     <li>SHA3-384withECDSA</li>
     *     <li>SHA3-512withECDSA</li>
     *     <li>SHA256withSM2</li>
     *     <li>SHA3-256withSM2</li>
     *     <li>SHA3-384withSM2</li>
     *     <li>SHA3-512withSM2</li>
     * </ul>
     *
     * @see EmailPopulatingBuilder#signWithSmime(SmimeSigningConfig)
     * @see EmailPopulatingBuilder#signWithSmime(File, String, String, String, String)
     */
    @Nullable
    private final String signatureAlgorithm;

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
        @SuppressFBWarnings(value = "OBL_UNSATISFIED_OBLIGATION", justification = "Input stream being created should not be closed here")
        public SmimeSigningConfigBuilder pkcs12Config(@NotNull File pkcs12StoreFile, @NotNull String storePassword, @NotNull String keyAlias, @NotNull String keyPassword) {
            try {
                return pkcs12Config(new FileInputStream(pkcs12StoreFile), storePassword, keyAlias, keyPassword);
            } catch (IOException e) {
                throw new IllegalStateException(format("Error reading from file: %s", pkcs12StoreFile), e);
            }
        }

        /**
         * Delegates to {@link #pkcs12Config(byte[], String, String, String)}.
         */
        public SmimeSigningConfigBuilder pkcs12Config(@NotNull InputStream pkcs12StoreStream, @NotNull String storePassword, @NotNull String keyAlias, @NotNull String keyPassword) {
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
        public SmimeSigningConfigBuilder pkcs12Config(byte @NotNull [] pkcs12StoreData, @NotNull String storePassword, @NotNull String keyAlias, @NotNull String keyPassword) {
            return pkcs12Config(Pkcs12Config.builder()
                    .pkcs12Store(pkcs12StoreData)
                    .storePassword(storePassword)
                    .keyAlias(keyAlias)
                    .keyPassword(keyPassword)
                    .build());
        }

        /**
         * For detailed information, see {@link SmimeSigningConfig#signatureAlgorithm}.
         *
         * @see EmailPopulatingBuilder#signWithSmime(SmimeSigningConfig)
         * @see EmailPopulatingBuilder#signWithSmime(File, String, String, String, String)
         */
        public SmimeSigningConfigBuilder signatureAlgorithm(@Nullable String signatureAlgorithm) {
            this.signatureAlgorithm = signatureAlgorithm;
            return this;
        }

        public SmimeSigningConfig build() {
            return new SmimeSigningConfig(this.pkcs12Config, this.signatureAlgorithm);
        }
    }
}