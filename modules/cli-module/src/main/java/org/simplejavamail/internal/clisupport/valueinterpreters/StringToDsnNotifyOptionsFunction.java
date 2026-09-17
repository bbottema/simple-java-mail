package org.simplejavamail.internal.clisupport.valueinterpreters;

import org.bbottema.javareflection.valueconverter.IncompatibleTypeException;
import org.bbottema.javareflection.valueconverter.ValueFunction;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;

/** Converts one CLI value into typed notification events using the same spelling and validation as configuration properties. */
public final class StringToDsnNotifyOptionsFunction implements ValueFunction<String, NotifyOption[]> {

    @Override
    @NotNull
    public Class<String> getFromType() {
        return String.class;
    }

    @Override
    @NotNull
    public Class<NotifyOption[]> getTargetType() {
        return NotifyOption[].class;
    }

    @Override
    @NotNull
    public NotifyOption[] convertValue(@NotNull final String value) {
        try {
            return DeliveryStatusNotification.parseNotifyOptions(value).toArray(new NotifyOption[0]);
        } catch (final IllegalArgumentException failure) {
            throw new IncompatibleTypeException(value, String.class, NotifyOption[].class, failure);
        }
    }
}
