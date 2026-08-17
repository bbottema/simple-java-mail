package org.simplejavamail.mailer.internal.util;

import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.api.mailer.spi.MailTransportAdapter;
import org.simplejavamail.api.mailer.spi.PreparedMail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

final class MailTransportAdapterResolver {

    private MailTransportAdapterResolver() {
    }

    @Nullable
    static SmtpServerResponse sendMessage(@NotNull final Transport transport,
                                          @NotNull final PreparedMail preparedMail)
            throws MessagingException {
        return sendMessage(transport, preparedMail, ServiceLoader.load(MailTransportAdapter.class));
    }

    @Nullable
    static SmtpServerResponse sendMessage(@NotNull final Transport transport,
                                          @NotNull final PreparedMail preparedMail,
                                          @NotNull final Iterable<MailTransportAdapter> adapters)
            throws MessagingException {
        final List<MailTransportAdapter> matches = new ArrayList<>();
        for (MailTransportAdapter adapter : adapters) {
            if (adapter.supports(transport)) {
                matches.add(adapter);
            }
        }

        if (matches.size() > 1) {
            Collections.sort(matches, Comparator.comparing(adapter -> adapter.getClass().getName()));
            final StringBuilder names = new StringBuilder();
            for (MailTransportAdapter match : matches) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(match.getClass().getName());
            }
            throw new MessagingException("Multiple mail transport adapters support "
                    + transport.getClass().getName() + ": " + names);
        }
        if (matches.size() == 1) {
            return matches.get(0).sendMessage(transport, preparedMail);
        }
        if (preparedMail.getDeliveryEnvelope().hasProviderSpecificOptions()) {
            throw new MessagingException("No mail transport adapter for " + transport.getClass().getName()
                    + " supports the requested envelope sender or delivery-status notification options. "
                    + "Install a matching MailTransportAdapter or remove those provider-specific options");
        }
        transport.sendMessage(preparedMail.getMimeMessage(), preparedMail.getRecipients());
        return null;
    }
}
