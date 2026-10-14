package org.simplejavamail.internal.config;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Keeps the historic enum names in Email's wire form while exposing only the current public selector in application code. */
@ApiStatus.Internal
public final class EmailPropertySerialization {
    private EmailPropertySerialization() {
    }

    @Nullable
    public static Set<EmailProperty> serializedSelectors(@Nullable final Set<org.simplejavamail.api.email.EmailProperty> properties) {
        if (properties == null) {
            return null;
        }
        return properties.stream().map(property -> EmailProperty.valueOf(property.name()))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(EmailProperty.class)));
    }

    @Nullable
    public static Set<org.simplejavamail.api.email.EmailProperty> publicSelectors(@Nullable final Set<? extends Enum<?>> properties) {
        if (properties == null) {
            return null;
        }
        if (properties.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(properties.stream().map(property -> org.simplejavamail.api.email.EmailProperty.valueOf(property.name()))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(org.simplejavamail.api.email.EmailProperty.class))));
    }
}
