package org.simplejavamail.api.converter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceData;

import java.util.List;

/**
 * A converted artifact together with observations made while producing it and available diagnostic source evidence.
 * Email imports return their inspection Email directly; this envelope is only used for MIME and EML artifact targets.
 * <p>
 * A usable value can have recoverable problems. A failure means no usable artifact could be produced, not that every source
 * component was valid or invalid. The immutable wrapper does not freeze a mutable target such as a MimeMessage or byte array.
 * Invalid use and unrelated application failures retain their normal exception behavior.
 *
 * @param <T> The existing artifact target type.
 */
public interface EmailConversionResult<T> {

    /** Whether a usable, completed artifact was produced. This does not certify sendability or complete source interpretation. */
    boolean hasValue();

    /** The artifact, or null when the operation could not produce one. Its existing mutability/ownership contract is unchanged. */
    @Nullable T getValue();

    /**
     * Immutable observations first made during this requested operation, including delegated import stages.
     * Findings already present on an input Email are not reported again as new conversion events.
     */
    @NotNull List<EmailProblem> getProblems();

    /**
     * Immutable source views available during this operation, including selectively retained originals that need no Problem.
     * Access does not acquire missing content, retry interpretation or run protection processing.
     */
    @NotNull List<EmailSourceData> getSources();

    /** The original recognized conversion failure when no artifact exists; null when a usable value exists. */
    @Nullable MailException getFailure();
}
