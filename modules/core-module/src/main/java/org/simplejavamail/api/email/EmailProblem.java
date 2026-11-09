package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.Serializable;
import java.util.List;

/**
 * A historical processing observation, not a current validation verdict or live send log.
 * Retained source content does not by itself imply a problem: an expected successful conversion can retain its original independently.
 *
 * @since 10.0.0
 */
public interface EmailProblem extends Serializable {
    /** The operation that first observed this problem. Copies do not relabel inherited observations. */
    enum Operation { IMPORT, CONVERSION, COPY }
    /** Stable categories; human-readable message text is not a machine-readable classifier. */
    enum Code {
        VALUE_NOT_DECODED, FIELD_NOT_INTERPRETED, INTERPRETATION_WARNING, SIGNATURE_INVALID, PROTECTION_FAILED,
        CONTENT_READ_FAILED, CONTENT_NOT_REPRESENTED, CONVERSION_FAILED, RESOURCE_CLOSE_FAILED, IMPORT_LIMIT_EXCEEDED
    }
    /** Original input identity, retained through copying and serialization. */
    @NotNull EmailSourceIdentity getOrigin();
    /** Operation that first observed this problem. */
    @NotNull Operation getOperation();
    /** Category of the observation. */
    @NotNull Code getCode();
    /** Affected field, occurrence or qualified content location. */
    @NotNull String getLocation();
    /** Complete offending source text when available. */
    @Nullable String getSourceValue();
    /** Explanation for inspection; it is not automatically logged. */
    @NotNull String getMessage();
    /** Affected immutable source occurrences; possibly several for a conflict or empty for an operation-level issue. */
    @NotNull List<EmailSourceElement> getSources();
}
