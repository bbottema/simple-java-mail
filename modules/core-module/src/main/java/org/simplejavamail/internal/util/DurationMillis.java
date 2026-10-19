package org.simplejavamail.internal.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;

import static java.util.Objects.requireNonNull;

/** Exact representation boundary for settings whose providers accept signed integer milliseconds. */
public final class DurationMillis {
    private DurationMillis() { }

    /** Checks representation only; owning features decide whether zero or negative values have meaning. */
    public static int toInt(@NotNull final Duration duration, final String setting) {
        requireNonNull(duration, setting);
        if (duration.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException(setting + " must be a whole number of milliseconds");
        }
        try {
            return Math.toIntExact(duration.toMillis());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(setting + " exceeds the supported integer-millisecond range", overflow);
        }
    }

    /** @return The exact value, retaining absence. */
    @Nullable public static Integer toNullableInt(@Nullable final Duration duration, final String setting) {
        return duration == null ? null : toInt(duration, setting);
    }

}
