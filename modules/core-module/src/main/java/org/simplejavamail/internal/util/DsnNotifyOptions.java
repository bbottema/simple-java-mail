package org.simplejavamail.internal.util;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;

/** Centralizes NOTIFY validation so Email, recipient builders and provider envelopes cannot apply different rules. */
public final class DsnNotifyOptions {

    private DsnNotifyOptions() {
    }

    /** An empty set means no local preference; NEVER is an explicit preference and must stand alone. */
    @NotNull
    public static Set<NotifyOption> copyOf(@NotNull final Collection<NotifyOption> options) {
        final Set<NotifyOption> copy = new LinkedHashSet<>(requireNonNull(options, "notifyOptions"));
        assumeTrue(!copy.contains(null), "Delivery status notification options cannot contain null values");
        assumeTrue(!copy.contains(NotifyOption.NEVER) || copy.size() == 1,
                "NEVER cannot be combined with other delivery status notification options");
        return unmodifiableSet(copy);
    }
}
