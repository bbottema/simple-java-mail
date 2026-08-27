package org.simplejavamail.mailer.internal.util;

import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.MailTransportAdapter;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/**
 * Selects the provider-specific submission path for an already prepared email and dispatches it on an already connected {@link Transport}.
 * <p>
 * Despite its name, this class does not resolve or manage the {@code Transport} itself. Its place in the send process is:
 * <pre>{@code
 * Mailer send / batch / open-connection API
 *     -> TransportRunner acquires and connects a Transport
 *     -> SessionBasedEmailToMimeMessageConverter creates PreparedMail
 *     -> MailTransportAdapterResolver selects the submission path and sends
 *     -> TransportRunner converts MailTransportResult to a receipt or exception
 * }</pre>
 * Transport lifecycle remains with the caller: this resolver never connects, closes, releases, or invalidates it.
 * <p>
 * The only production adapter currently shipped by Simple Java Mail is
 * {@code org.simplejavamail.internal.mailprovider.angus.AngusMailTransportAdapter}, defined in the Maven artifact
 * {@code angus-mail-provider-module} under {@code modules/angus-mail-provider-module} (JPMS module
 * {@code org.simplejavamail.mailprovider.angus}). The main {@code simple-java-mail} artifact includes that module as a runtime dependency. It recognizes
 * Angus {@code SMTPTransport} instances, applies envelope-sender and delivery-status-notification options, honors every {@link ContentRequirement}, and
 * captures the final Angus SMTP response. Other {@code MailTransportAdapter} implementations in this repository are test doubles only.
 * <p>
 * Discovery uses the standard {@link ServiceLoader} mechanism. On the classpath, the Angus adapter JAR registers its implementation in
 * {@code META-INF/services/org.simplejavamail.api.mailer.spi.MailTransportAdapter}. On the module path, {@code org.simplejavamail} declares {@code uses}
 * and {@code org.simplejavamail.mailprovider.angus} declares the corresponding {@code provides ... with ...}. A third-party provider adapter is discovered
 * through either of those same registrations after implementing {@link MailTransportAdapter}.
 * <p>
 * The resolver asks every discovered adapter whether it supports the concrete transport. Exactly one match receives the prepared mail; multiple matches
 * fail rather than depending on classpath order. When no adapter matches, no adapter is involved: the resolver calls the provider-neutral
 * {@link Transport#sendMessage(jakarta.mail.Message, jakarta.mail.Address[])} method directly. That fallback is permitted only for ordinary content and an
 * envelope without provider-specific options.
 * <p>
 * Adapter ambiguity and capability mismatches are detected before submission. Actual submission failures are represented by an unsuccessful
 * {@link MailTransportResult}, allowing {@link TransportRunner} to retain SMTP response and recipient details when it creates the caller-facing result.
 *
 * @see MailTransportAdapter
 * @see PreparedMail
 * @see TransportRunner
 */
final class MailTransportAdapterResolver {

    private MailTransportAdapterResolver() {
    }

    /**
     * Creates a {@link ServiceLoader} for this submission and delegates selection and submission to the explicit-candidates overload. With the standard
     * {@code simple-java-mail} runtime dependencies, the discovered candidates include the Angus adapter described above.
     *
     * @param transport A transport that the caller has already acquired and connected.
     * @param preparedMail Finalized MIME content plus its SMTP-envelope and content-preservation requirements.
     * @return The adapter-neutral submission result. SMTP submission failure is returned as an unsuccessful result rather than thrown directly.
     * @throws MessagingException If adapter selection is ambiguous or no available submission path can honor the prepared mail's requirements.
     */
    @NotNull
    static MailTransportResult sendMessage(@NotNull final Transport transport,
                                           @NotNull final PreparedMail preparedMail)
            throws MessagingException {
        return sendMessage(transport, preparedMail, ServiceLoader.load(MailTransportAdapter.class));
    }

    /**
     * Selects from an explicit set of candidate adapters and performs submission. This overload separates service discovery from dispatch and provides a
     * deterministic seam for resolver tests.
     *
     * @param transport A transport that the caller has already acquired and connected.
     * @param preparedMail Finalized MIME content plus its SMTP-envelope and content-preservation requirements.
     * @param availableAdapters Candidate adapters; each is asked once whether it supports the concrete transport.
     * @return The adapter-neutral submission result. SMTP submission failure is returned as an unsuccessful result rather than thrown directly.
     * @throws MessagingException If adapter selection is ambiguous or no available submission path can honor the prepared mail's requirements.
     */
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
            return sendUsingAdapter(supportingAdapters.get(0), transport, preparedMail);
        }
        requireProviderNeutralContent(transport, preparedMail.getContentRequirement());
        requireProviderNeutralEnvelope(transport, preparedMail.getDeliveryEnvelope());
        return sendUsingGenericTransport(transport, preparedMail);
    }

    @NotNull
    private static List<MailTransportAdapter> findSupportingAdapters(@NotNull final Transport transport,
                                                                     @NotNull final Iterable<MailTransportAdapter> availableAdapters) {
        final List<MailTransportAdapter> supportingAdapters = new ArrayList<>();
        for (final MailTransportAdapter adapter : availableAdapters) {
            if (adapter.supports(transport)) {
                supportingAdapters.add(adapter);
            }
        }
        return supportingAdapters;
    }

    @NotNull
    private static MailTransportCompatibilityException buildAmbiguousAdapterException(@NotNull final Transport transport,
                                                                                       @NotNull final List<MailTransportAdapter> supportingAdapters) {
        final String adapterClassNames = supportingAdapters.stream()
                .map(adapter -> adapter.getClass().getName())
                .sorted()
                .collect(joining(", "));
        return new MailTransportCompatibilityException("Multiple mail transport adapters support "
                + transport.getClass().getName() + ": " + adapterClassNames);
    }

    @NotNull
    private static MailTransportResult sendUsingAdapter(@NotNull final MailTransportAdapter adapter,
                                                        @NotNull final Transport transport,
                                                        @NotNull final PreparedMail preparedMail)
            throws MessagingException {
        requireSupportedContent(adapter, transport, preparedMail.getContentRequirement());
        return requireNonNull(adapter.sendMessage(transport, preparedMail), "MailTransportAdapter result");
    }

    private static void requireSupportedContent(@NotNull final MailTransportAdapter adapter,
                                                @NotNull final Transport transport,
                                                @NotNull final ContentRequirement contentRequirement)
            throws MailTransportCompatibilityException {
        if (!adapter.supportsContentRequirement(contentRequirement)) {
            throw new MailTransportCompatibilityException(adapter.getClass().getName() + " supports "
                    + transport.getClass().getName() + " but cannot honor content requirement " + contentRequirement);
        }
    }

    private static void requireProviderNeutralContent(@NotNull final Transport transport,
                                                      @NotNull final ContentRequirement contentRequirement)
            throws MailTransportCompatibilityException {
        if (contentRequirement != ContentRequirement.NORMAL) {
            throw new MailTransportCompatibilityException("No mail transport adapter for " + transport.getClass().getName()
                    + " can honor content requirement " + contentRequirement);
        }
    }

    private static void requireProviderNeutralEnvelope(@NotNull final Transport transport,
                                                       @NotNull final DeliveryEnvelope deliveryEnvelope)
            throws MailTransportCompatibilityException {
        if (deliveryEnvelope.hasProviderSpecificOptions()) {
            throw new MailTransportCompatibilityException("No mail transport adapter for " + transport.getClass().getName()
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
