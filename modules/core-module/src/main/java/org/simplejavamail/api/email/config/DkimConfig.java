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
        this.excludedHeadersFromDkimDefaultSigningList = copyExcludedHeaders(excludedHeadersFromDkimDefaultSigningList);
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

    @ToString(exclude = "dkimPrivateKeyData")
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
         * Chooses whether the DKIM signature includes a body-length tag. Enabling it leaves content appended after that length unsigned,
         * so use it only for a known relay that appends content. False explicitly disables the tag; null uses the module's default, which omits it.
         * Calling again replaces the choice. {@link #clearUseLengthParam()} restores absence without changing the key or domain.
         *
         * @param useLengthParam Whether to include the length tag, or null to use the module's default.
         */
        public DkimConfigBuilder useLengthParam(@Nullable Boolean useLengthParam) {
            this.useLengthParam = useLengthParam;
            return this;
        }

        /**
         * Leaves selected headers out of the DKIM module's default signing list, for example when a known relay rewrites them.
         * The DKIM module rejects exclusion of {@code From} when signing, since a DKIM signature must cover that header.
         * Calling again replaces the exclusions rather than adding to them.
         * Null or {@link #clearExcludedHeadersFromDkimDefaultSigningList()} restores the module's default list without replacing key material.
         *
         * @param excludedHeadersFromDkimDefaultSigningList Headers to exclude, or null for no supplied exclusion choice.
         * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
         */
        public DkimConfigBuilder excludedHeadersFromDkimDefaultSigningList(@Nullable Set<String> excludedHeadersFromDkimDefaultSigningList) {
            this.excludedHeadersFromDkimDefaultSigningList = excludedHeadersFromDkimDefaultSigningList;
            return this;
        }

        /**
         * Delegates to {@link #excludedHeadersFromDkimDefaultSigningList(Set)}.
         *
         * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
         * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
         */
        public DkimConfigBuilder excludedHeadersFromDkimDefaultSigningList(@Nullable String... excludedHeadersFromDkimDefaultSigningList) {
            return excludedHeadersFromDkimDefaultSigningList(excludedHeadersFromDkimDefaultSigningList == null
                    ? null
                    : new HashSet<>(asList(excludedHeadersFromDkimDefaultSigningList)));
        }

        /**
         * Chooses how the DKIM module normalizes headers before signing. SIMPLE preserves their form more closely; RELAXED tolerates
         * certain whitespace and case changes. The module defaults to RELAXED when this is null. Calling again replaces the preference;
         * {@link #clearHeaderCanonicalization()} removes it without changing body normalization or key material.
         *
         * @param headerCanonicalization Header-normalization preference, or null for the module's default.
         */
        public DkimConfigBuilder headerCanonicalization(@Nullable Canonicalization headerCanonicalization) {
            this.headerCanonicalization = headerCanonicalization;
            return this;
        }

        /**
         * Chooses how the DKIM module normalizes the message body before signing. SIMPLE preserves body whitespace;
         * RELAXED tolerates changes to spaces and tabs. Both normalize trailing empty lines. The module defaults to RELAXED when this is null.
         * Calling again replaces this preference; {@link #clearBodyCanonicalization()} removes it without changing header normalization or the key.
         *
         * @param bodyCanonicalization Body-normalization preference, or null for the module's default.
         * @see #headerCanonicalization(Canonicalization)
         */
        public DkimConfigBuilder bodyCanonicalization(@Nullable Canonicalization bodyCanonicalization) {
            this.bodyCanonicalization = bodyCanonicalization;
            return this;
        }

        /**
         * Chooses the signing algorithm understood by the DKIM module, for example {@code SHA256_WITH_RSA}.
         * Null leaves the choice to that module. Calling again replaces the preference; {@link #clearSigningAlgorithm()}
         * removes it without replacing the key, domain or selector.
         * <p>
         * The module accepts {@code SHA256_WITH_RSA} (the default), {@code SHA256_WITH_ED25519}, and {@code SHA1_WITH_RSA}
         * for legacy compatibility. The private key must match the selected algorithm. Prefer the SHA-256 choices for new deployments.
         *
         * @param signingAlgorithm DKIM module algorithm name, or null for its default.
         */
        public DkimConfigBuilder signingAlgorithm(@Nullable String signingAlgorithm) {
            this.signingAlgorithm = signingAlgorithm;
            return this;
        }

        /**
         * Returns a copy of the supplied private-key bytes, or null when none have been supplied.
         *
         * @see #dkimPrivateKeyData(byte[])
         */
        @Nullable
        public byte[] getDkimPrivateKeyData() {
            return dkimPrivateKeyData == null ? null : dkimPrivateKeyData.clone();
        }

        /** @see #dkimSigningDomain(String) */
        @Nullable
        public String getDkimSigningDomain() {
            return dkimSigningDomain;
        }

        /** @see #dkimSelector(String) */
        @Nullable
        public String getDkimSelector() {
            return dkimSelector;
        }

        /** @see #useLengthParam(Boolean) */
        @Nullable
        public Boolean getUseLengthParam() {
            return useLengthParam;
        }

        /**
         * Returns a detached set of supplied exclusions, or null when no exclusion choice is configured.
         *
         * @see #excludedHeadersFromDkimDefaultSigningList(Set)
         */
        @Nullable
        public Set<String> getExcludedHeadersFromDkimDefaultSigningList() {
            return excludedHeadersFromDkimDefaultSigningList == null ? null : new HashSet<>(excludedHeadersFromDkimDefaultSigningList);
        }

        /** @see #headerCanonicalization(Canonicalization) */
        @Nullable
        public Canonicalization getHeaderCanonicalization() {
            return headerCanonicalization;
        }

        /** @see #bodyCanonicalization(Canonicalization) */
        @Nullable
        public Canonicalization getBodyCanonicalization() {
            return bodyCanonicalization;
        }

        /** @see #signingAlgorithm(String) */
        @Nullable
        public String getSigningAlgorithm() {
            return signingAlgorithm;
        }

        /** @see #useLengthParam(Boolean) */
        public DkimConfigBuilder clearUseLengthParam() {
            useLengthParam = null;
            return this;
        }

        /** @see #excludedHeadersFromDkimDefaultSigningList(Set) */
        public DkimConfigBuilder clearExcludedHeadersFromDkimDefaultSigningList() {
            excludedHeadersFromDkimDefaultSigningList = null;
            return this;
        }

        /** @see #headerCanonicalization(Canonicalization) */
        public DkimConfigBuilder clearHeaderCanonicalization() {
            headerCanonicalization = null;
            return this;
        }

        /** @see #bodyCanonicalization(Canonicalization) */
        public DkimConfigBuilder clearBodyCanonicalization() {
            bodyCanonicalization = null;
            return this;
        }

        /** @see #signingAlgorithm(String) */
        public DkimConfigBuilder clearSigningAlgorithm() {
            signingAlgorithm = null;
            return this;
        }

        public DkimConfig build() {
            return new DkimConfig(dkimPrivateKeyData, dkimSigningDomain, dkimSelector, useLengthParam,
                    excludedHeadersFromDkimDefaultSigningList, headerCanonicalization, bodyCanonicalization, signingAlgorithm);
        }
    }

    @Nullable
    private static Set<String> copyExcludedHeaders(@Nullable final Set<String> excludedHeaders) {
        return excludedHeaders == null ? null : Collections.unmodifiableSet(new HashSet<>(excludedHeaders));
    }
}
