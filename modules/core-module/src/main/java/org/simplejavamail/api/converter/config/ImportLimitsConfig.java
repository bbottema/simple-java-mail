package org.simplejavamail.api.converter.config;

import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;

/**
 * Immutable supplied choices for received-mail import limits. Each absent member inherits the owning factory's captured configuration;
 * zero explicitly selects unlimited and positive values select a bound. The library fallbacks are unlimited.
 * <p>
 * Reuse this value across conversions and threads. Each conversion creates its own allowance, shared by its selected nested imports and
 * extracted content. This value contains no counters, sources or property lookups. Its limits describe controlled import work, not a
 * whole-JVM memory bound or allocations already performed by callers, native parsers or cryptographic providers.
 *
 * @since 10.0.0
 */
@Value
public class ImportLimitsConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Supplied input-size state.
     * @serial Null inherits and zero explicitly selects unlimited.
     */
    @Nullable Long maximumInputBytes;
    /**
     * Supplied node-count state.
     * @serial Null inherits and zero explicitly selects unlimited.
     */
    @Nullable Integer maximumPartCount;
    /**
     * Supplied depth state.
     * @serial Null inherits and zero explicitly selects unlimited.
     */
    @Nullable Integer maximumNestingDepth;

    private ImportLimitsConfig(final Builder builder) {
        maximumInputBytes = builder.maximumInputBytes;
        maximumPartCount = builder.maximumPartCount;
        maximumNestingDepth = builder.maximumNestingDepth;
        validateChoices();
    }

    /**
     * Creates a builder with no supplied choices.
     * @return A builder whose members inherit the owning factory.
     */
    @NotNull public static Builder builder() { return new Builder(); }

    /**
     * Copies the supplied state into an independent draft.
     * @return A builder preserving absent, unlimited and positive choices.
     */
    @NotNull public Builder toBuilder() { return new Builder(this); }

    /**
     * Returns the supplied byte limit, including explicit zero, or null for factory inheritance.
     * @return The supplied choice without default resolution.
     * @see Builder#withMaximumInputBytes(long)
     */
    @Nullable public Long getMaximumInputBytes() { return maximumInputBytes; }

    /**
     * Returns the supplied node count, including explicit zero, or null for factory inheritance.
     * @return The supplied choice without default resolution.
     * @see Builder#withMaximumPartCount(int)
     */
    @Nullable public Integer getMaximumPartCount() { return maximumPartCount; }

    /**
     * Returns the supplied depth, including explicit zero, or null for factory inheritance.
     * @return The supplied choice without default resolution.
     * @see Builder#withMaximumNestingDepth(int)
     */
    @Nullable public Integer getMaximumNestingDepth() { return maximumNestingDepth; }

    private void validateChoices() {
        requireNonNegative(maximumInputBytes, "maximumInputBytes");
        requireNonNegative(maximumPartCount, "maximumPartCount");
        requireNonNegative(maximumNestingDepth, "maximumNestingDepth");
    }

    private static void requireNonNegative(final Number value, final String field) {
        if (value != null && value.longValue() < 0) {
            throw new IllegalArgumentException(field + " must be zero (unlimited) or positive");
        }
    }

    /**
     * Restores supplied choices while retaining completed-value invariants.
     * @param input Serialized input.
     * @throws IOException When reading fails or a restored choice is negative.
     * @throws ClassNotFoundException When a serialized value type cannot be resolved.
     */
    private void readObject(final ObjectInputStream input) throws IOException, ClassNotFoundException {
        input.defaultReadObject();
        try {
            validateChoices();
        } catch (IllegalArgumentException failure) {
            final InvalidObjectException invalid = new InvalidObjectException(failure.getMessage());
            invalid.initCause(failure);
            throw invalid;
        }
    }

    /** Supplied import-limit choices. Inspection is passive; {@link #build()} rejects negative completed choices. */
    public static final class Builder {

        /** Published fallback for raw input bytes: zero means unlimited. */
        public static final long DEFAULT_MAXIMUM_INPUT_BYTES = 0L;
        /** Published fallback for admitted parts: zero means unlimited. */
        public static final int DEFAULT_MAXIMUM_PART_COUNT = 0;
        /** Published fallback for nesting depth: zero means unlimited. */
        public static final int DEFAULT_MAXIMUM_NESTING_DEPTH = 0;

        private Long maximumInputBytes;
        private Integer maximumPartCount;
        private Integer maximumNestingDepth;

        private Builder() { }

        private Builder(final ImportLimitsConfig config) {
            maximumInputBytes = config.maximumInputBytes;
            maximumPartCount = config.maximumPartCount;
            maximumNestingDepth = config.maximumNestingDepth;
        }

        /**
         * Sets the maximum encoded size accepted from the original raw EML or Outlook MSG source, in bytes. Zero explicitly selects unlimited;
         * an absent choice inherits the captured factory configuration before the {@value #DEFAULT_MAXIMUM_INPUT_BYTES} library fallback.
         * EML String input uses the converter's UTF-8 interpretation. A negative choice is rejected by {@link #build()}.
         * <p>
         * Raw stream acquisition stops at the bound, allowing at most one additional byte to establish that the source is larger. Available
         * evidence can be an incomplete prefix; it is never represented as a complete normal resource. Caller-owned streams remain open.
         * Existing byte-array copying and String encoding may already have occurred before a later conversion-stage choice is supplied.
         * <p>
         * An externally supplied MimeMessage has no reliable original encoded length. A finite byte bound is incompatible with that source
         * and is rejected before consuming its conversion; use original raw input or select only traversal limits. No serialization is
         * performed merely to invent an original length. This setting does not limit later application reads of deferred attachments.
         *
         * @param maximumInputBytes Zero for unlimited, or the positive accepted raw-input size in bytes.
         * @return This builder with the supplied choice stored.
         * @see #resetMaximumInputBytes()
         * @see #useConfiguredMaximumInputBytes()
         */
        @NotNull public Builder withMaximumInputBytes(final long maximumInputBytes) { this.maximumInputBytes = maximumInputBytes; return this; }

        /**
         * Inspects the supplied byte choice without building or resolving it.
         * @return The stored choice, or null for inheritance.
         * @see #withMaximumInputBytes(long)
         */
        @Nullable public Long getMaximumInputBytes() { return maximumInputBytes; }

        /**
         * Explicitly selects the published unlimited fallback.
         * @return This builder.
         * @see #withMaximumInputBytes(long)
         */
        @NotNull public Builder resetMaximumInputBytes() { return withMaximumInputBytes(DEFAULT_MAXIMUM_INPUT_BYTES); }

        /**
         * Removes the local byte choice and restores captured factory inheritance.
         * @return This builder.
         * @see #withMaximumInputBytes(long)
         */
        @NotNull public Builder useConfiguredMaximumInputBytes() { maximumInputBytes = null; return this; }

        /**
         * Sets the maximum number of source/content nodes admitted to controlled import traversal, including the root, nested imported messages
         * and selected extracted views. The selected operation shares one allowance; nested imports do not receive a fresh count.
         * Zero explicitly selects unlimited. An absent choice inherits captured factory configuration before the
         * {@value #DEFAULT_MAXIMUM_PART_COUNT} library fallback. A negative choice is rejected by {@link #build()}.
         * <p>
         * At the bound, import preserves completed content, stops admitting further nodes and records the limitation in Problems. Repeated
         * metadata access to an admitted node does not count again, and skipped work does not produce a complete-looking truncated resource.
         * The limit does not undo parsing or allocation already performed by a supplied provider or native library.
         *
         * @param maximumPartCount Zero for unlimited, or a positive maximum admitted node count.
         * @return This builder with the supplied choice stored.
         * @see #resetMaximumPartCount()
         * @see #useConfiguredMaximumPartCount()
         */
        @NotNull public Builder withMaximumPartCount(final int maximumPartCount) { this.maximumPartCount = maximumPartCount; return this; }

        /**
         * Inspects the supplied node count without building or resolving it.
         * @return The stored choice, or null for inheritance.
         * @see #withMaximumPartCount(int)
         */
        @Nullable public Integer getMaximumPartCount() { return maximumPartCount; }

        /**
         * Explicitly selects the published unlimited fallback.
         * @return This builder.
         * @see #withMaximumPartCount(int)
         */
        @NotNull public Builder resetMaximumPartCount() { return withMaximumPartCount(DEFAULT_MAXIMUM_PART_COUNT); }

        /**
         * Removes the local node-count choice and restores captured factory inheritance.
         * @return This builder.
         * @see #withMaximumPartCount(int)
         */
        @NotNull public Builder useConfiguredMaximumPartCount() { maximumPartCount = null; return this; }

        /**
         * Sets the maximum depth admitted to controlled import traversal. The root is depth one; child entities and embedded imported messages
         * increase the depth. Selected extracted views retain the enclosing operation's allowance. Zero explicitly selects unlimited;
         * an absent choice inherits captured factory configuration before the {@value #DEFAULT_MAXIMUM_NESTING_DEPTH} library fallback.
         * A negative choice is rejected by {@link #build()}.
         * <p>
         * Deeper subtrees are skipped with a Problem while completed content and permitted siblings remain available under the part allowance.
         * This is a traversal boundary, not a promise to prevent allocations already performed by a caller, provider or native parser.
         *
         * @param maximumNestingDepth Zero for unlimited, or a positive maximum depth with the root at one.
         * @return This builder with the supplied choice stored.
         * @see #resetMaximumNestingDepth()
         * @see #useConfiguredMaximumNestingDepth()
         */
        @NotNull public Builder withMaximumNestingDepth(final int maximumNestingDepth) { this.maximumNestingDepth = maximumNestingDepth; return this; }

        /**
         * Inspects the supplied depth without building or resolving it.
         * @return The stored choice, or null for inheritance.
         * @see #withMaximumNestingDepth(int)
         */
        @Nullable public Integer getMaximumNestingDepth() { return maximumNestingDepth; }

        /**
         * Explicitly selects the published unlimited fallback.
         * @return This builder.
         * @see #withMaximumNestingDepth(int)
         */
        @NotNull public Builder resetMaximumNestingDepth() { return withMaximumNestingDepth(DEFAULT_MAXIMUM_NESTING_DEPTH); }

        /**
         * Removes the local depth choice and restores captured factory inheritance.
         * @return This builder.
         * @see #withMaximumNestingDepth(int)
         */
        @NotNull public Builder useConfiguredMaximumNestingDepth() { maximumNestingDepth = null; return this; }

        /**
         * Completes the supplied configuration and rejects negative choices without changing this builder.
         * @return An immutable supplied configuration.
         */
        @NotNull public ImportLimitsConfig build() { return new ImportLimitsConfig(this); }
    }
}
