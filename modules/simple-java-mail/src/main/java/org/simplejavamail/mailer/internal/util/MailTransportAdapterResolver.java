package org.simplejavamail.mailer.internal.util;

import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.MailTransportAdapter;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

final class MailTransportAdapterResolver {

    private MailTransportAdapterResolver() {
    }

    @NotNull
    static MailTransportResult sendMessage(@NotNull final Transport transport,
                                           @NotNull final PreparedMail preparedMail)
            throws MessagingException {
        return sendMessage(transport, preparedMail, ServiceLoader.load(MailTransportAdapter.class));
    }

    @NotNull
    static MailTransportResult sendMessage(@NotNull final Transport transport,
                                           @NotNull final PreparedMail preparedMail,
                                           @NotNull final Iterable<MailTransportAdapter> availableAdapters)
            throws MessagingException {
        final List<MailTransportAdapter> supportingAdapters = findSupportingAdapters(transport, availableAdapters);
        if (supportingAdapters.size() > 1) {
            throw buildAmbiguousAdapterException(transport, supportingAdapters);
        }
        if (supportingAdapters.size() == 1) {
            return requireNonNull(supportingAdapters.get(0).sendMessage(transport, preparedMail),
                    "MailTransportAdapter result");
        }
        requireProviderNeutralEnvelope(transport, preparedMail.getDeliveryEnvelope());
        return sendUsingGenericTransport(transport, preparedMail);
    }

    @NotNull
    private static List<MailTransportAdapter> findSupportingAdapters(@NotNull final Transport transport,
                                                                      @NotNull final Iterable<MailTransportAdapter> availableAdapters) {
        final List<MailTransportAdapter> supportingAdapters = new ArrayList<>();
        for (MailTransportAdapter adapter : availableAdapters) {
            if (adapter.supports(transport)) {
                supportingAdapters.add(adapter);
            }
        }
        return supportingAdapters;
    }

    @NotNull
    private static MessagingException buildAmbiguousAdapterException(@NotNull final Transport transport,
                                                                      @NotNull final List<MailTransportAdapter> supportingAdapters) {
        final String adapterClassNames = supportingAdapters.stream()
                .map(adapter -> adapter.getClass().getName())
                .sorted()
                .collect(joining(", "));
        return new MessagingException("Multiple mail transport adapters support "
                + transport.getClass().getName() + ": " + adapterClassNames);
    }

    private static void requireProviderNeutralEnvelope(@NotNull final Transport transport,
                                                       @NotNull final DeliveryEnvelope deliveryEnvelope)
            throws MessagingException {
        if (deliveryEnvelope.hasProviderSpecificOptions()) {
            throw new MessagingException("No mail transport adapter for " + transport.getClass().getName()
                    + " supports the requested envelope sender or delivery-status notification options. "
                    + "Install a matching MailTransportAdapter or remove those provider-specific options");
        }
    }

    @NotNull
    private static MailTransportResult sendUsingGenericTransport(@NotNull final Transport transport,
                                                                  @NotNull final PreparedMail preparedMail) {
        try {
            transport.sendMessage(preparedMail.getMimeMessage(), preparedMail.getRecipients());
            return MailTransportResult.accepted(preparedMail.getRecipients(), null);
        } catch (final MessagingException failure) {
            return MailTransportResult.failed(failure, null);
        }
    }
}
