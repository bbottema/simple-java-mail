package org.simplejavamail.api.email.config;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.EmailPopulatingBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.simplejavamail.internal.util.MiscUtil.readInputStreamToBytes;

/**
 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
 * @see #getUseLengthParam()
 * @see #getHeaderCanonicalization()
 * @see #getBodyCanonicalization()
 * @see #getSigningAlgorithm()
 */
@ToString(exclude = "dkimPrivateKeyData")
@Getter
@EqualsAndHashCode
public class DkimConfig implements Serializable {

    public enum Canonicalization {
        SIMPLE, RELAXED
    }

    private static final long serialVersionUID = 1234567L;

    /**
     * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
     * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
     */
    private final byte[] dkimPrivateKeyData;

    /**
     * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
     * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
     */
    private final String dkimSigningDomain;

    /**
     * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
     * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
     */
    private final String dkimSelector;

    /**
     * Controls the inclusion of the l= parameter (body length tag) in the DKIM signature. The l= parameter specifies the exact length of the body content that
     * was signed.
     * <p>
     * <strong>Default Value:</strong> null (indicating that the default behavior is determined by the library, which typically excludes the l= parameter).
     * </p>
     * <p>
     * <strong>Warning:</strong> Exercise caution when enabling this parameter. Its inclusion can enhance robustness in scenarios where emails undergo
     * legitimate alterations after sending (such as the addition of footers by mailing lists). However, it also poses security risks by allowing attackers to
     * append malicious content to the message without compromising the integrity of the DKIM signature. It is advisable to disable this option (set to false)
     * unless a specific scenario necessitates its use.
     * </p>
     */
    @Nullable
    private final Boolean useLengthParam;

    /**
     * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
     * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
     */
    @Nullable
    private final Set<String> excludedHeadersFromDkimDefaultSigningList;

    /**
     * Specifies the canonicalization algorithm for header fields in the DKIM signature. Canonicalization is the process of standardizing data into a consistent
     * format before signing. This affects the processing and signing of email headers.
     * <p>
     * <strong>Available Values:</strong><br>
     * - <strong>SIMPLE:</strong> Applies minimal canonicalization, aiming to preserve the original form of headers as closely as possible.<br> -
     * <strong>RELAXED:</strong> Implements a more forgiving canonicalization, accommodating minor alterations in whitespace and case sensitivity.
     * </p>
     * <p><strong>Default Value:</strong> RELAXED</p>
     * <p>
     * <strong>NOTE:</strong> The choice between SIMPLE and RELAXED can impact the success rate of DKIM verification across different mail servers. The RELAXED
     * option tends to normalize minor discrepancies in headers, offering broader compatibility.
     * </p>
     */
    @Nullable
    private final Canonicalization headerCanonicalization;

    /**
     * Defines the canonicalization algorithm for the email body, mirroring the functionality described for {@link #headerCanonicalization}. It dictates the
     * standardization process for the body content before signing.
     */
    @Nullable
    private final Canonicalization bodyCanonicalization;

    /**
     * Selects the cryptographic algorithm for generating the DKIM signature. This choice influences the security and interoperability of your DKIM signature.
     * <p>
     * <strong>Default Value:</strong> SHA256withRSA, which balances robust security with good performance and is broadly supported across platforms.
     * </p>
     * <p>
     * <strong>Supported Signature Algorithms:</strong><br>
     * - <strong>SHA256_WITH_RSA</strong> (Recommended)<br> - <strong>SHA1_WITH_RSA</strong><br> - <strong>SHA256_WITH_ED25519</strong><br>
     * </p>
     * <p>
     * <strong>NOTE:</strong> The rsa-sha256 algorithm (or another algorithm offering similar security) is strongly recommended. Weaker algorithms, such as
     * rsa-sha1, are no longer deemed secure against contemporary cryptographic threats.
     * </p>
     */
    @Nullable
    private final String signingAlgorithm;


    DkimConfig(byte[] dkimPrivateKeyData, String dkimSigningDomain, String dkimSelector, Boolean useLengthParam,
               @Nullable Set<String> excludedHeadersFromDkimDefaultSigningList,
               @Nullable Canonicalization headerCanonicalization,
               @Nullable Canonicalization bodyCanonicalization,
               @Nullable String signingAlgorithm) {
        this.dkimPrivateKeyData = dkimPrivateKeyData.clone();
        this.dkimSigningDomain = dkimSigningDomain;
        this.dkimSelector = dkimSelector;
        this.useLengthParam = useLengthParam;
        this.excludedHeadersFromDkimDefaultSigningList = excludedHeadersFromDkimDefaultSigningList;
        this.headerCanonicalization = headerCanonicalization;
        this.bodyCanonicalization = bodyCanonicalization;
        this.signingAlgorithm = signingAlgorithm;
    }

    /**
     * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
     * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
     */
    public byte[] getDkimPrivateKeyData() {
        return dkimPrivateKeyData.clone();
    }

    public static DkimConfigBuilder builder() {
        return new DkimConfigBuilder();
    }

    @ToString
    public static class DkimConfigBuilder {
        private byte[] dkimPrivateKeyData;
        private String dkimSigningDomain;
        private String dkimSelector;
        @Nullable Boolean useLengthParam;
        @Nullable private Set<String> excludedHeadersFromDkimDefaultSigningList;
        @Nullable private Canonicalization headerCanonicalization;
        @Nullable private Canonicalization bodyCanonicalization;
        @Nullable private String signingAlgorithm;

        /**
         * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
         * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
         */
        public DkimConfigBuilder dkimPrivateKeyPath(String dkimPrivateKeyFile) {
            return dkimPrivateKeyPath(new File(dkimPrivateKeyFile));
        }

        /**
         * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
         * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
         */
        public DkimConfigBuilder dkimPrivateKeyPath(File dkimPrivateKeyFile) {
            try (FileInputStream dkimPrivateKeyInputStream = new FileInputStream(dkimPrivateKeyFile)) {
                dkimPrivateKeyData(dkimPrivateKeyInputStream);
            } catch (IOException e) {
                throw new IllegalStateException(format("error reading DKIM private key file[%s]", dkimPrivateKeyFile), e);
            }
            return this;
        }

        /**
         * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
         * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
         */
        public DkimConfigBuilder dkimPrivateKeyData(InputStream dkimPrivateKeyInputStream) {
            try {
                dkimPrivateKeyData(readInputStreamToBytes(dkimPrivateKeyInputStream));
            } catch (IOException e) {
                throw new IllegalStateException("error reading DKIM private key inputstream", e);
            }
            return this;
        }

        /**
         * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
         * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
         */
        public DkimConfigBuilder dkimPrivateKeyData(String dkimPrivateKeyData) {
            dkimPrivateKeyData(dkimPrivateKeyData.getBytes(UTF_8));
            return this;
        }

        /**
         * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
         * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
         */
        public DkimConfigBuilder dkimPrivateKeyData(byte[] dkimPrivateKeyData) {
            this.dkimPrivateKeyData = dkimPrivateKeyData.clone();
            return this;
        }

        /**
         * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
         * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
         */
        public DkimConfigBuilder dkimSigningDomain(String dkimSigningDomain) {
            this.dkimSigningDomain = dkimSigningDomain;
            return this;
        }

        /**
         * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
         * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
         */
        public DkimConfigBuilder dkimSelector(String dkimSelector) {
            this.dkimSelector = dkimSelector;
            return this;
        }

        /**
         * @see DkimConfig#getUseLengthParam()
         */
        public DkimConfigBuilder useLengthParam(@Nullable Boolean useLengthParam) {
            this.useLengthParam = useLengthParam;
            return this;
        }

        /**
         * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
         * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
         */
        public DkimConfigBuilder excludedHeadersFromDkimDefaultSigningList(@Nullable Set<String> excludedHeadersFromDkimDefaultSigningList) {
            this.excludedHeadersFromDkimDefaultSigningList = excludedHeadersFromDkimDefaultSigningList;
            return this;
        }

        /**
         * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
         * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
         */
        public DkimConfigBuilder excludedHeadersFromDkimDefaultSigningList(@Nullable String... excludedHeadersFromDkimDefaultSigningList) {
            this.excludedHeadersFromDkimDefaultSigningList = new HashSet<>(asList(excludedHeadersFromDkimDefaultSigningList));
            return this;
        }

        /**
         * @see DkimConfig#getHeaderCanonicalization()
         */
        public DkimConfigBuilder headerCanonicalization(@Nullable Canonicalization headerCanonicalization) {
            this.headerCanonicalization = headerCanonicalization;
            return this;
        }

        /**
         * @see DkimConfig#getBodyCanonicalization()
         */
        public DkimConfigBuilder bodyCanonicalization(@Nullable Canonicalization bodyCanonicalization) {
            this.bodyCanonicalization = bodyCanonicalization;
            return this;
        }

        /**
         * @see DkimConfig#getSigningAlgorithm()
         */
        public DkimConfigBuilder signingAlgorithm(@Nullable String signingAlgorithm) {
            this.signingAlgorithm = signingAlgorithm;
            return this;
        }

        public DkimConfig build() {
            return new DkimConfig(dkimPrivateKeyData, dkimSigningDomain, dkimSelector, useLengthParam,
                    excludedHeadersFromDkimDefaultSigningList, headerCanonicalization, bodyCanonicalization, signingAlgorithm);
        }
    }
}