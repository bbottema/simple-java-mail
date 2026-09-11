package org.simplejavamail.internal.clisupport.valueinterpreters;

import org.bbottema.javareflection.valueconverter.IncompatibleTypeException;
import org.bbottema.javareflection.valueconverter.ValueFunction;

import java.time.Duration;
import java.time.format.DateTimeParseException;

/** Accepts the same ISO-8601 duration spelling used by configuration properties, for example PT30S. */
public final class StringToDurationFunction implements ValueFunction<String, Duration> {
    @Override
    public Class<String> getFromType() {
        return String.class;
    }

    @Override
    public Class<Duration> getTargetType() {
        return Duration.class;
    }

    @Override
    public Duration convertValue(final String value) {
        try {
            return Duration.parse(value);
        } catch (DateTimeParseException failure) {
            throw new IncompatibleTypeException(value, String.class, Duration.class, failure);
        }
    }
}
