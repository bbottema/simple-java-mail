package org.simplejavamail.api.email.config;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.EmailPopulatingBuilder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.simplejavamail.internal.util.MiscUtil.readInputStreamToBytes;

/**
 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
 * @see DkimConfigBuilder#useLengthParam(Boolean)
 * @see DkimConfigBuilder#headerCanonicalization(DkimConfig.Canonicalization)
 * @see DkimConfigBuilder#bodyCanonicalization(DkimConfig.Canonicalization)
 * @see DkimConfigBuilder#signingAlgorithm(String)
 */
@ToString(exclude = "dkimPrivateKeyData")
@EqualsAndHashCode
public class DkimConfig implements Serializable {

    public enum Canonicalization {
        SIMPLE, RELAXED
    }

    private static final long serialVersionUID = 1234567L;
    private static final String MANDATORY_SIGNED_HEADER = "From";

    /**
     * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
     * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
     */
    private final byte[] dkimPrivateKeyData;

    private final String dkimSigningDomain;

    /**
     * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
     * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
     */
    private final String dkimSelector;

    @Nullable
    private final Boolean useLengthParam;

    /**
     * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
     * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
     */
    @Nullable
    private final Set<String> excludedHeadersFromDkimDefaultSigningList;

    @Nullable
    private final Canonicalization headerCanonicalization;

    @Nullable
    private final Canonicalization bodyCanonicalization;

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
        this.excludedHeadersFromDkimDefaultSigningList = copyAndValidateExcludedHeaders(excludedHeadersFromDkimDefaultSigningList);
        this.headerCanonicalization = headerCanonicalization;
        this.bodyCanonicalization = bodyCanonicalization;
        this.signingAlgorithm = signingAlgorithm;
    }

    /**
     * Returns a copy of the private-key bytes.
     *
     * @see DkimConfigBuilder#dkimPrivateKeyData(byte[])
     * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
     * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
     */
    public byte[] getDkimPrivateKeyData() {
        return dkimPrivateKeyData.clone();
    }

    /** @see DkimConfigBuilder#dkimSigningDomain(String) */
    public String getDkimSigningDomain() {
        return dkimSigningDomain;
    }

    /** @see DkimConfigBuilder#dkimSelector(String) */
    public String getDkimSelector() {
        return dkimSelector;
    }

    /** @see DkimConfigBuilder#useLengthParam(Boolean) */
    @Nullable
    public Boolean getUseLengthParam() {
        return useLengthParam;
    }

    /** @see DkimConfigBuilder#excludedHeadersFromDkimDefaultSigningList(Set) */
    @Nullable
    public Set<String> getExcludedHeadersFromDkimDefaultSigningList() {
        return excludedHeadersFromDkimDefaultSigningList;
    }

    /** @see DkimConfigBuilder#headerCanonicalization(Canonicalization) */
    @Nullable
    public Canonicalization getHeaderCanonicalization() {
        return headerCanonicalization;
    }

    /** @see DkimConfigBuilder#bodyCanonicalization(Canonicalization) */
    @Nullable
    public Canonicalization getBodyCanonicalization() {
        return bodyCanonicalization;
    }

    /** @see DkimConfigBuilder#signingAlgorithm(String) */
    @Nullable
    public String getSigningAlgorithm() {
        return signingAlgorithm;
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
            return dkimPrivateKeyPath(dkimPrivateKeyFile.toPath());
        }

        /**
         * Reads the private-key bytes immediately and closes the stream opened for the path.
         *
         * @param dkimPrivateKeyPath Path to the private-key data.
         * @return This builder.
         * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
         * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
         */
        public DkimConfigBuilder dkimPrivateKeyPath(@NotNull final Path dkimPrivateKeyPath) {
            try (InputStream dkimPrivateKeyInputStream = Files.newInputStream(dkimPrivateKeyPath)) {
                return dkimPrivateKeyData(dkimPrivateKeyInputStream);
            } catch (IOException e) {
                throw new IllegalStateException(format("error reading DKIM private key file [%s]", dkimPrivateKeyPath), e);
            }
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
         * Controls the inclusion of the l= parameter (body length tag) in the DKIM signature. The l= parameter specifies the exact length of the body
         * content that was signed.
         * <p>
         * <strong>Default Value:</strong> null (indicating that the default behavior is determined by the library, which typically excludes the l= parameter).
         * </p>
         * <p>
         * <strong>Warning:</strong> Exercise caution when enabling this parameter. Its inclusion can enhance robustness in scenarios where emails undergo
         * legitimate alterations after sending (such as the addition of footers by mailing lists). However, it also poses security risks by allowing
         * attackers to append malicious content to the message without compromising the integrity of the DKIM signature. It is advisable to disable
         * this option (set to false) unless a specific scenario necessitates its use.
         * </p>
         */
        public DkimConfigBuilder useLengthParam(@Nullable Boolean useLengthParam) {
            this.useLengthParam = useLengthParam;
            return this;
        }

        /**
         * Excludes selected headers from the DKIM library's default signing list for a known downstream relay that rewrites them.
         * {@code From} is mandatory in DKIM and cannot be excluded.
         *
         * @throws IllegalArgumentException when {@code From} is excluded, case-insensitively
         * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
         * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
        */
        public DkimConfigBuilder excludedHeadersFromDkimDefaultSigningList(@Nullable Set<String> excludedHeadersFromDkimDefaultSigningList) {
            validateExcludedHeaders(excludedHeadersFromDkimDefaultSigningList);
            this.excludedHeadersFromDkimDefaultSigningList = excludedHeadersFromDkimDefaultSigningList;
            return this;
        }

        /**
         * Delegates to {@link #excludedHeadersFromDkimDefaultSigningList(Set)}.
         *
         * @throws IllegalArgumentException when {@code From} is excluded, case-insensitively
         * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
         * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
         */
        public DkimConfigBuilder excludedHeadersFromDkimDefaultSigningList(@Nullable String... excludedHeadersFromDkimDefaultSigningList) {
            return excludedHeadersFromDkimDefaultSigningList(excludedHeadersFromDkimDefaultSigningList == null
                    ? null
                    : new HashSet<>(asList(excludedHeadersFromDkimDefaultSigningList)));
        }

        /**
         * Specifies the canonicalization algorithm for header fields in the DKIM signature. Canonicalization is the process of standardizing data
         * into a consistent format before signing. This affects the processing and signing of email headers.
         * <p>
         * <strong>Available Values:</strong><br>
         * - <strong>SIMPLE:</strong> Applies minimal canonicalization, aiming to preserve the original form of headers as closely as possible.<br> -
         * <strong>RELAXED:</strong> Implements a more forgiving canonicalization, accommodating minor alterations in whitespace and case sensitivity.
         * </p>
         * <p><strong>Default Value:</strong> RELAXED</p>
         * <p>
         * <strong>NOTE:</strong> The choice between SIMPLE and RELAXED can impact the success rate of DKIM verification across different mail servers.
         * The RELAXED option tends to normalize minor discrepancies in headers, offering broader compatibility.
         * </p>
         */
        public DkimConfigBuilder headerCanonicalization(@Nullable Canonicalization headerCanonicalization) {
            this.headerCanonicalization = headerCanonicalization;
            return this;
        }

        /**
         * Defines the canonicalization algorithm for the email body, mirroring the functionality described for
         * {@link #headerCanonicalization(Canonicalization)}. It dictates the standardization process for the body content before signing.
         */
        public DkimConfigBuilder bodyCanonicalization(@Nullable Canonicalization bodyCanonicalization) {
            this.bodyCanonicalization = bodyCanonicalization;
            return this;
        }

        /**
         * Selects the cryptographic algorithm for generating the DKIM signature. This choice influences the security and interoperability of your
         * DKIM signature.
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
        public DkimConfigBuilder signingAlgorithm(@Nullable String signingAlgorithm) {
            this.signingAlgorithm = signingAlgorithm;
            return this;
        }

        public DkimConfig build() {
            return new DkimConfig(dkimPrivateKeyData, dkimSigningDomain, dkimSelector, useLengthParam,
                    excludedHeadersFromDkimDefaultSigningList, headerCanonicalization, bodyCanonicalization, signingAlgorithm);
        }
    }

    @Nullable
    private static Set<String> copyAndValidateExcludedHeaders(@Nullable final Set<String> excludedHeaders) {
        validateExcludedHeaders(excludedHeaders);
        return excludedHeaders == null ? null : Collections.unmodifiableSet(new HashSet<>(excludedHeaders));
    }

    private static void validateExcludedHeaders(@Nullable final Set<String> excludedHeaders) {
        if (excludedHeaders == null) {
            return;
        }
        for (String excludedHeader : excludedHeaders) {
            if (excludedHeader != null && MANDATORY_SIGNED_HEADER.equalsIgnoreCase(excludedHeader.trim())) {
                throw new IllegalArgumentException("DKIM signatures must include the From header; it cannot be excluded from the default signing list");
            }
        }
    }
}
