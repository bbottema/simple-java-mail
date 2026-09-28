package org.simplejavamail.internal.util;

import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.Objects;

/** SIZE facts from one EHLO, independent of how much of that reply fits in a diagnostic report. */
public final class SmtpSizeSupport implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final SmtpSizeSupport UNADVERTISED = new SmtpSizeSupport(false, null);
    private final boolean advertised;
    @Nullable private final Long maximumMessageSize;

    private SmtpSizeSupport(final boolean advertised, @Nullable final Long maximumMessageSize) {
        this.advertised = advertised;
        this.maximumMessageSize = maximumMessageSize;
    }

    public static SmtpSizeSupport unadvertised() {
        return UNADVERTISED;
    }

    /** An unusable or conflicting advertisement permanently makes the maximum unknown for this EHLO, but SIZE remains advertised. */
    public SmtpSizeSupport withAdvertisement(final CharSequence parameter) {
        final Long maximum = positiveMaximum(parameter);
        return new SmtpSizeSupport(true, !advertised || Objects.equals(maximumMessageSize, maximum) ? maximum : null);
    }

    public boolean isAdvertised() {
        return advertised;
    }

    @Nullable
    public Long getMaximumMessageSize() {
        return maximumMessageSize;
    }

    @Nullable
    private static Long positiveMaximum(final CharSequence parameter) {
        if (parameter.length() == 0 || parameter.chars().anyMatch(character -> character < '0' || character > '9')) {
            return null;
        }
        try {
            final long maximum = Long.parseLong(parameter, 0, parameter.length(), 10);
            return maximum > 0 ? maximum : null;
        } catch (NumberFormatException overflow) {
            return null;
        }
    }
}
