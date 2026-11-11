package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.List;

/** An application assessment explanation. Use its stable code rather than parsing human-readable text. */
public interface ProtectionReason extends Serializable {
    /** Stable reason category, independent of explanatory wording. */
    @NotNull ProtectionReasonCode getCode();
    /** Human-readable explanation of the assessment condition. */
    @NotNull String getMessage();
    /** Captured occurrences that explain this condition, or an empty list when unavailable. */
    @NotNull List<EmailSourceElement> getSources();
}
