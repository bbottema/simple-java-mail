package org.simplejavamail.api.email.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.internal.util.CertificationUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.Serializable;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import static java.lang.String.format;

@ToString
@Getter
@EqualsAndHashCode
@AllArgsConstructor
public class SmimeEncryptionConfig implements Serializable {

    private static final long serialVersionUID = 1234567L;

    @NotNull
    final X509Certificate x509Certificate;
    /**
     * Configuration for S/MIME encryption, specifying the key encapsulation algorithm used for securing
     * the encryption key. This setting is crucial for the security of the encrypted message, ensuring that
     * the encryption key itself is transmitted securely and can only be accessed by the intended recipient.
     *
     * <p><strong>Default Algorithm:</strong> RSA. Selected for broad compatibility and historical reasons,
     * RSA without OAEP padding is the default. However, for enhanced security, it's recommended to use RSA
     * with OAEP padding and SHA-256 or higher.</p>
     *
     * <p><strong>Recommended Algorithms:</strong> The use of RSA with OAEP padding is advised due to its
     * improved security properties over plain RSA. Algorithms with SHA-256 or higher offer a stronger level
     * of security and are recommended for most applications:</p>
     *
     * <ul>
     *     <li>RSA (Default, suitable for broad compatibility)</li>
     *     <li>RSA_OAEP_SHA224 (More secure than plain RSA, consider for transitional applications)</li>
     *     <li>RSA_OAEP_SHA256 (Highly recommended for new applications)</li>
     *     <li>RSA_OAEP_SHA384 (Enhanced security for higher requirements)</li>
     *     <li>RSA_OAEP_SHA512 (Maximum security level, recommended for very sensitive communications)</li>
     * </ul>
     *
     * <p>Refer to {@code org.simplejavamail.utils.mail.smime.KeyEncapsulationAlgorithm} for the most current
     * list of supported algorithms.</p>
     *
     * <p><strong>Note:</strong> While the default RSA is widely compatible, the move towards RSA with OAEP padding
     * is encouraged to ensure a higher level of security against modern cryptographic attacks. The choice of SHA-256
     * or higher as the hashing algorithm for OAEP provides a good balance between security and performance.</p>
     */
    @Nullable
    final String keyEncapsulationAlgorithm;

    /**
     * Configuration for S/MIME encryption, specifying the algorithm used for encrypting the email content.
     * The choice of encryption algorithm impacts both the security of the encrypted message and its compatibility.
     *
     * <p><strong>Default Algorithm:</strong> DES_EDE3_CBC. While this is provided for broad compatibility,
     * it's recommended to use AES-based algorithms for enhanced security.</p>
     *
     * <p><strong>Recommended Encryption Algorithms:</strong> The following list focuses on secure and commonly
     * used algorithms for S/MIME encryption. Note that AES is preferred due to its strong security features and
     * performance efficiency. For a complete and updated list, consult the Bouncy Castle documentation. For a complete
     * list of supported algorithms, refer to the Bouncy Castle's {@code see org.bouncycastle.cms.CMSAlgorithm} class.</p>
     *
     * <ul>
     *     <li>AES256_CBC (Recommended)</li>
     *     <li>AES192_CBC</li>
     *     <li>AES128_CBC</li>
     *     <li>DES_EDE3_CBC (for legacy compatibility)</li>
     * </ul>
     *
     * <p><strong>Note:</strong> While DES_EDE3_CBC is supported for backward compatibility, AES (128, 192, 256)
     * is strongly recommended for new applications due to its enhanced security and efficiency.</p>
     */
    @Nullable
    final String cipherAlgorithm;

    public static SmimeEncryptionConfigBuilder builder() {
        return new SmimeEncryptionConfigBuilder();
    }

    @ToString
    public static class SmimeEncryptionConfigBuilder {
        private X509Certificate x509Certificate;
        private String keyEncapsulationAlgorithm;
        private String cipherAlgorithm;

        /**
         * @see EmailPopulatingBuilder#encryptWithSmime(SmimeEncryptionConfig)
         * @see EmailPopulatingBuilder#encryptWithSmime(File, String, String)
         */
        public SmimeEncryptionConfigBuilder x509Certificate(@NotNull final X509Certificate x509Certificate) {
            this.x509Certificate = x509Certificate;
            return this;
        }

        /**
         * Delegates to {@link #x509Certificate(InputStream)}.
         */
        @SuppressFBWarnings(value = "OBL_UNSATISFIED_OBLIGATION", justification = "Input stream being created should not be closed here")
        public SmimeEncryptionConfigBuilder x509Certificate(@NotNull final String pemFile) {
            try {
                return x509Certificate(new FileInputStream(pemFile));
            } catch (FileNotFoundException e) {
                throw new IllegalStateException(format("Error reading from file: %s", pemFile), e);
            }
        }

        /**
         * Delegates to {@link #x509Certificate(InputStream)},
         */
        @SuppressFBWarnings(value = "OBL_UNSATISFIED_OBLIGATION", justification = "Input stream being created should not be closed here")
        public SmimeEncryptionConfigBuilder x509Certificate(@NotNull final File pemFile) {
            try {
                return x509Certificate(new FileInputStream(pemFile));
            } catch (FileNotFoundException e) {
                throw new IllegalStateException(format("Error reading from file: %s", pemFile), e);
            }
        }

        /**
         * Delegates to {@link #x509Certificate(X509Certificate)}.
         */
        public SmimeEncryptionConfigBuilder x509Certificate(@NotNull final InputStream pemStream) {
            try {
                return x509Certificate(CertificationUtil.readFromPem(pemStream));
            } catch (CertificateException e) {
                throw new IllegalStateException("Was unable to convert PEM data to X509 certificate", e);
            } catch (NoSuchProviderException e) {
                throw new IllegalStateException("Unable to load certificate (missing bouncy castle), is the S/MIME module on the class path?", e);
            }
        }

        /**
         * For detailed information, see {@link SmimeEncryptionConfig#keyEncapsulationAlgorithm}.
         *
         * @see EmailPopulatingBuilder#encryptWithSmime(SmimeEncryptionConfig)
         * @see EmailPopulatingBuilder#encryptWithSmime(File, String, String)
         */
        public SmimeEncryptionConfigBuilder keyEncapsulationAlgorithm(@Nullable String keyEncapsulationAlgorithm) {
            this.keyEncapsulationAlgorithm = keyEncapsulationAlgorithm;
            return this;
        }

        /**
         * For detailed information, see {@link SmimeEncryptionConfig#cipherAlgorithm}.
         *
         * @see EmailPopulatingBuilder#encryptWithSmime(SmimeEncryptionConfig)
         * @see EmailPopulatingBuilder#encryptWithSmime(File, String, String)
         */
        public SmimeEncryptionConfigBuilder cipherAlgorithm(@Nullable String cipherAlgorithm) {
            this.cipherAlgorithm = cipherAlgorithm;
            return this;
        }

        public SmimeEncryptionConfig build() {
            return new SmimeEncryptionConfig(this.x509Certificate, this.keyEncapsulationAlgorithm, this.cipherAlgorithm);
        }
    }
}