package org.simplejavamail.internal.clisupport.valueinterpreters;

import org.bbottema.javareflection.valueconverter.IncompatibleTypeException;
import org.bbottema.javareflection.valueconverter.ValueFunction;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption;

/** Keeps the readable return-option names and existing FULL/HDRS aliases at the CLI conversion boundary. */
public final class StringToDsnReturnOptionFunction implements ValueFunction<String, ReturnOption> {

    @Override
    @NotNull
    public Class<String> getFromType() {
        return String.class;
    }

    @Override
    @NotNull
    public Class<ReturnOption> getTargetType() {
        return ReturnOption.class;
    }

    @Override
    @NotNull
    public ReturnOption convertValue(@NotNull final String value) {
        try {
            return DeliveryStatusNotification.parseReturnOption(value);
        } catch (final IllegalArgumentException failure) {
            throw new IncompatibleTypeException(value, String.class, ReturnOption.class, failure);
        }
    }
}
