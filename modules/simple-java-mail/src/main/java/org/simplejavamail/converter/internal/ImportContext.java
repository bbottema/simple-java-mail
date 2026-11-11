package org.simplejavamail.converter.internal;

import org.simplejavamail.api.converter.config.ImportLimitsConfig;
import org.simplejavamail.config.ConfigKey;
import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.config.ConfigurationSnapshot;
import org.simplejavamail.internal.configresolution.ConfigurationChoice;
import org.simplejavamail.internal.configresolution.ConfigurationResolver;
import org.simplejavamail.internal.converter.ImportTraversal;
import org.jetbrains.annotations.ApiStatus;

/** Effective import choices and the traversal scope of one selected source; never stored on returned Email values. */
@ApiStatus.Internal
public final class ImportContext {
    final long maximumInputBytes;
    final ImportTraversal traversal;
    final boolean rawSource;
    private final Materialization materialization;

    private ImportContext(final long maximumInputBytes, final ImportTraversal traversal, final boolean rawSource) {
        this(maximumInputBytes, traversal, new Materialization(), rawSource);
    }

    private ImportContext(final long maximumInputBytes, final ImportTraversal traversal, final Materialization materialization, final boolean rawSource) {
        this.maximumInputBytes = maximumInputBytes;
        this.traversal = traversal;
        this.materialization = materialization;
        this.rawSource = rawSource;
    }

    /** CLI message arguments become native MIME before builder import; reject unsupported raw caps before reading those files. */
    public static void validateSuppliedMime(final SimpleJavaMailConfig config) {
        prepare(config, ImportLimitsConfig.builder().build(), false);
    }

    static ImportContext prepare(final SimpleJavaMailConfig config, final ImportLimitsConfig supplied, final boolean rawSource) {
        final ImportLimitsConfig selected = ImportLimitsConfig.builder()
                .withMaximumInputBytes(resolve(config, ConfigKeys.IMPORT_MAXIMUM_INPUT_BYTES, supplied.getMaximumInputBytes(),
                        ImportLimitsConfig.Builder.DEFAULT_MAXIMUM_INPUT_BYTES))
                .withMaximumPartCount(resolve(config, ConfigKeys.IMPORT_MAXIMUM_PART_COUNT, supplied.getMaximumPartCount(),
                        ImportLimitsConfig.Builder.DEFAULT_MAXIMUM_PART_COUNT))
                .withMaximumNestingDepth(resolve(config, ConfigKeys.IMPORT_MAXIMUM_NESTING_DEPTH, supplied.getMaximumNestingDepth(),
                        ImportLimitsConfig.Builder.DEFAULT_MAXIMUM_NESTING_DEPTH))
                .build();
        if (!rawSource && selected.getMaximumInputBytes() > 0) {
            throw new IllegalStateException("A supplied MimeMessage has no reliable original input size. Supply raw EML input, "
                    + "or select only part/depth limits by setting maximumInputBytes to zero (subject to factory locks).");
        }
        return new ImportContext(selected.getMaximumInputBytes(),
                ImportTraversal.start(selected.getMaximumPartCount(), selected.getMaximumNestingDepth()), rawSource);
    }

    private static <T> T resolve(final SimpleJavaMailConfig config, final ConfigKey<T> key, final T supplied, final T fallback) {
        final T selected = ConfigurationResolver.resolveValue(config, key, ConfigurationChoice.ofNullable(supplied), fallback,
                "ImportLimitsConfig." + key.key()).valueOrNull();
        ConfigurationSnapshot.locksOf(config).verify(key.key(), selected);
        return selected;
    }

    /** Extracted content already belongs to the accepted raw source; only its shared traversal allowance continues. */
    ImportContext at(final ImportTraversal scope) { return new ImportContext(0, scope, materialization, false); }

    ImportContext rawAt(final ImportTraversal scope) { return new ImportContext(0, scope, materialization, true); }

    /** A retained representation shares the operation's allowance but reports what this actual parse materialized. */
    ImportContext newRepresentation() { return new ImportContext(0, traversal, false); }

    void noteProjection(final boolean readableBody, final boolean limitStop) {
        materialization.readableBody |= readableBody;
        materialization.limitStop |= limitStop;
    }

    boolean hasReadableBody() { return materialization.readableBody; }
    /** A limit left this representation without any materialized readable body; a later omitted file alone does not qualify. */
    boolean bodyWasLimited() { return materialization.limitStop && !materialization.readableBody; }

    private static final class Materialization {
        private boolean readableBody;
        private boolean limitStop;
    }
}
