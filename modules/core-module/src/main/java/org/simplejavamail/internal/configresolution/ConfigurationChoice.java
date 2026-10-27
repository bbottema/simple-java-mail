package org.simplejavamail.internal.configresolution;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import static java.util.Objects.requireNonNull;

/** A local choice; removal is explicit and therefore cannot be mistaken for inheritance. */
@ApiStatus.Internal
public final class ConfigurationChoice<T> {
    public enum State { INHERIT, SET, REMOVE }

    private final State state;
    @Nullable private final T value;

    private ConfigurationChoice(final State state, @Nullable final T value) {
        this.state = state;
        this.value = value;
    }

    public static <T> ConfigurationChoice<T> inherit() { return new ConfigurationChoice<>(State.INHERIT, null); }
    public static <T> ConfigurationChoice<T> set(final T value) { return new ConfigurationChoice<>(State.SET, requireNonNull(value, "value")); }
    public static <T> ConfigurationChoice<T> remove() { return new ConfigurationChoice<>(State.REMOVE, null); }

    /** Nullable draft members inherit; an explicit clear must use {@link #remove()} instead. */
    public static <T> ConfigurationChoice<T> ofNullable(@Nullable final T value) {
        return value == null ? inherit() : set(value);
    }

    public State getState() { return state; }
    @Nullable public T valueOrNull() { return value; }

    @Override public String toString() { return state.name(); }
}
