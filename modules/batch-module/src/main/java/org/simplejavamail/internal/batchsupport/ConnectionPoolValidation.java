package org.simplejavamail.internal.batchsupport;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Checks effective batch-module settings before creating any upstream pool resources. */
@ApiStatus.Internal
@UtilityClass
public class ConnectionPoolValidation {
    public static void validateResolved(final int coreSize, final int maxSize, final int claimTimeoutMillis,
            final int expireAfterMillis, @Nullable final Integer expireAfterCreationMillis) {
        validateRanges(coreSize, maxSize, claimTimeoutMillis, expireAfterMillis, expireAfterCreationMillis);
        validateSizeRelationship(coreSize, maxSize);
    }

    private static void validateRanges(@Nullable final Integer coreSize, @Nullable final Integer maxSize,
            @Nullable final Integer claimTimeoutMillis, @Nullable final Integer expireAfterMillis,
            @Nullable final Integer expireAfterCreationMillis) {
        requireMinimum("coreSize", coreSize, 0);
        requireMinimum("maxSize", maxSize, 1);
        requireMinimum("claimTimeoutMillis", claimTimeoutMillis, 0);
        requireMinimum("expireAfterMillis", expireAfterMillis, 0);
        requireMinimum("expireAfterCreationMillis", expireAfterCreationMillis, 1);
    }

    /** Check pairs only when complete; inherited cluster values must be resolved before this call. */
    private static void validateSizeRelationship(@Nullable final Integer coreSize, @Nullable final Integer maxSize) {
        if (coreSize != null && maxSize != null && coreSize > maxSize) {
            throw new IllegalArgumentException("Connection pool coreSize must not exceed maxSize. "
                    + "Lower the core size or increase the maximum for this pool configuration.");
        }
    }

    private static void requireMinimum(final String setting, @Nullable final Integer value, final int minimum) {
        if (value != null && value < minimum) {
            final String constraint = minimum == 0 ? "zero or greater" : "positive (1 or greater)";
            throw new IllegalArgumentException("Connection pool " + setting + " must be " + constraint + ". "
                    + "Correct this value in the builder or configuration source.");
        }
    }
}
