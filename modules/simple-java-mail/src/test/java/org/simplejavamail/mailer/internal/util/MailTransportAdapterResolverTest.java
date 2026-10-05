package org.simplejavamail.mailer.internal.util;

import org.simplejavamail.api.mailer.spi.MailTransportCompatibilityException;
import org.simplejavamail.api.mailer.spi.DeliveryRecipient;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.MailTransportAdapter;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailTransportAdapterResolverTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void genericEnvelopeSupportCannotSilentlyIgnoreRecipientRejectionHandling(final boolean choice) throws Exception {
        final RecordingAdapter adapter = new RecordingAdapter(true) {
            @Override
            public boolean supportsDeliveryEnvelope(final DeliveryEnvelope envelope) {
                return true;
            }
        };
        final PreparedMail mail = preparedMail(new DeliveryEnvelope(null, null, List.of(), false, false, choice));
        final AtomicInteger invocations = new AtomicInteger();
        assertThatThrownBy(() -> MailTransportAdapterResolver.sendMessage(new RecordingTransport(), mail, List.of(adapter), invocations::incrementAndGet))
                .isInstanceOf(MailTransportCompatibilityException.class)
                .hasMessageContaining(choice ? "continue sending to accepted recipients" : "withhold content for everyone")
                .hasMessageContaining("selected provider adapter does not support that choice")
                .hasMessageContaining("No message was submitted");
        assertThat(invocations).hasValue(0);
        assertThat(adapter.preparedMail).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void genericFallbackRejectsExplicitRecipientRejectionHandlingBeforeInvocation(final boolean choice) throws Exception {
        final RecordingTransport transport = new RecordingTransport();
        final PreparedMail mail = preparedMail(new DeliveryEnvelope(null, null, List.of(), false, false, choice));
        final AtomicInteger invocations = new AtomicInteger();
        assertThatThrownBy(() -> MailTransportAdapterResolver.sendMessage(transport, mail, List.of(), invocations::incrementAndGet))
                .isInstanceOf(MailTransportCompatibilityException.class).hasMessageContaining("no provider adapter is available to apply that choice");
        assertThat(invocations).hasValue(0);
        assertThat(transport.sentMessage).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void supportingThirdPartyAdapterReceivesTheExactChoice(final boolean choice) throws Exception {
        final RecordingAdapter adapter = new RecordingAdapter(true) {
            @Override
            public boolean supportsSendingToAcceptedRecipients() {
                return true;
            }
        };
        final PreparedMail mail = preparedMail(new DeliveryEnvelope(null, null, List.of(), false, false, choice));
        final AtomicInteger invocations = new AtomicInteger();
        assertThat(MailTransportAdapterResolver.sendMessage(new RecordingTransport(), mail, List.of(adapter), invocations::incrementAndGet)
                .getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
        assertThat(adapter.preparedMail).isSameAs(mail);
        assertThat(adapter.preparedMail.getDeliveryEnvelope().getSendingToAcceptedRecipients()).isEqualTo(choice);
        assertThat(invocations).hasValue(1);
    }

    @Test
    void envelopeSupportAloneDoesNotOptAnExistingAdapterIntoMandatoryDsn() throws Exception {
        final RecordingAdapter adapter = new RecordingAdapter(true) {
            @Override
            public boolean supportsDeliveryEnvelope(final DeliveryEnvelope envelope) {
                return true;
            }
        };
        final PreparedMail mail = preparedMail(new DeliveryEnvelope(null,
                DeliveryStatusNotification.of(DeliveryStatusNotification.NotifyOption.FAILURE), List.of(), false, true));
        assertThatThrownBy(() -> MailTransportAdapterResolver.sendMessage(new RecordingTransport(), mail, List.of(adapter)))
                .isInstanceOf(MailTransportCompatibilityException.class).hasMessageContaining("mandatory DSN");
        assertThat(adapter.preparedMail).isNull();
    }

    @Test
    void mandatoryNotificationSurvivesSerializationWithoutChangingOldConstructorBehavior() throws Exception {
        final DeliveryStatusNotification request = DeliveryStatusNotification.of(DeliveryStatusNotification.NotifyOption.FAILURE);
        final DeliveryEnvelope envelope = new DeliveryEnvelope(null, request, List.of(), false, true);
        final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream output = new java.io.ObjectOutputStream(bytes)) {
            output.writeObject(envelope);
        }
        try (java.io.ObjectInputStream input = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            final DeliveryEnvelope restored = (DeliveryEnvelope) input.readObject();
            assertThat(restored.isDeliveryStatusNotificationRequired()).isTrue();
            assertThat(restored.getDeliveryStatusNotification().getNotifyOptions()).containsExactly(DeliveryStatusNotification.NotifyOption.FAILURE);
        }
        assertThat(new DeliveryEnvelope(null, request).isDeliveryStatusNotificationRequired()).isFalse();
        assertThatThrownBy(() -> new DeliveryEnvelope(null, null, List.of(), false, true)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deliveryStatusNotificationRequired is true, but deliveryStatusNotification is null")
                .hasMessageContaining("Supply a DeliveryStatusNotification request")
                .hasMessageContaining("set deliveryStatusNotificationRequired to false");
    }

    @Test
    void olderAdapterCannotSilentlyIgnoreAnEnvelopeIdentifier() throws Exception {
        final RecordingAdapter adapter = new RecordingAdapter(true);
        final PreparedMail preparedMail = preparedMail(new DeliveryEnvelope(null,
                DeliveryStatusNotification.builder().envelopeId("attempt-42").build()));
        assertThatThrownBy(() -> MailTransportAdapterResolver.sendMessage(new RecordingTransport(), preparedMail, List.of(adapter)))
                .isInstanceOf(MailTransportCompatibilityException.class).hasMessageContaining("cannot send the requested SMTP envelope options")
                .hasMessageContaining("remove the unsupported options").hasMessageContaining("ENVID")
                .satisfies(failure -> {
                    final SendFailedException unsent = (SendFailedException) failure;
                    assertThat(unsent.getValidUnsentAddresses()).containsExactly(preparedMail.getRecipients());
                    assertThat(unsent.getValidSentAddresses()).isNull();
                    assertThat(MailTransportResult.failed(unsent, null).getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
                });
        assertThat(adapter.preparedMail).isNull();
    }

	@Test
	void olderAndUnknownProvidersCannotSilentlyIgnoreRequireTls() throws Exception {
		final DeliveryEnvelope envelope = new DeliveryEnvelope(null, null, List.of(), true);
		final PreparedMail preparedMail = preparedMail(envelope);
		final RecordingTransport transport = new RecordingTransport();
		final RecordingAdapter olderAdapter = new RecordingAdapter(true);

		assertThatThrownBy(() -> MailTransportAdapterResolver.sendMessage(transport, preparedMail, List.of(olderAdapter)))
				.isInstanceOf(MailTransportCompatibilityException.class)
				.hasMessageContaining("REQUIRETLS");
		assertThatThrownBy(() -> MailTransportAdapterResolver.sendMessage(transport, preparedMail, List.of()))
				.isInstanceOf(MailTransportCompatibilityException.class)
				.hasMessageContaining("REQUIRETLS")
				.hasMessageContaining("matching MailTransportAdapter");
		assertThat(olderAdapter.preparedMail).isNull();
		assertThat(transport.sentMessage).isNull();
	}

    @Test
    void adapterOptingIntoEnvelopeSupportReceivesTheExactUnencodedValue() throws Exception {
        final RecordingAdapter adapter = new RecordingAdapter(true) {
            @Override
            public boolean supportsDeliveryEnvelope(final DeliveryEnvelope envelope) {
                return true;
            }
        };
        final DeliveryStatusNotification notification = DeliveryStatusNotification.builder().envelopeId("attempt+42").build();
        final PreparedMail preparedMail = preparedMail(new DeliveryEnvelope(null, notification));
        MailTransportAdapterResolver.sendMessage(new RecordingTransport(), preparedMail, List.of(adapter));
        assertThat(adapter.preparedMail.getDeliveryEnvelope().getDeliveryStatusNotification()).isSameAs(notification);
    }

    @Test
    void unknownProviderUsesGenericFallbackForOrdinaryMail() throws Exception {
        final RecordingTransport transport = new RecordingTransport();
        final PreparedMail preparedMail = preparedMail(new DeliveryEnvelope(null, null));

        final MailTransportResult result = MailTransportAdapterResolver.sendMessage(
                transport, preparedMail, Collections.<MailTransportAdapter>emptyList());

        assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
        assertThat(result.getSmtpResponse()).isEmpty();
        assertThat(result.getEnvelopeId()).isNull();
        assertThat(result.getAcceptedRecipients()).containsExactly(preparedMail.getRecipients());
        assertThat(transport.sentMessage).isSameAs(preparedMail.getMimeMessage());
        assertThat(transport.sentRecipients).containsExactly(preparedMail.getRecipients());
    }

    @Test
    void olderAdapterKeepsOrdinarySendingWithoutInventingAnEnvelopeIdentifier() throws Exception {
        final RecordingAdapter adapter = new RecordingAdapter(true);
        final PreparedMail preparedMail = preparedMail(new DeliveryEnvelope(null, null));

        final MailTransportResult result = MailTransportAdapterResolver.sendMessage(new RecordingTransport(), preparedMail, List.of(adapter));

        assertThat(result.getEnvelopeId()).isNull();
        assertThat(adapter.preparedMail).isSameAs(preparedMail);
        assertThat(preparedMail.getDeliveryEnvelope().getDeliveryStatusNotification()).isNull();
    }

    @Test
    void genericFallbackReturnsPartialRecipientFactsAndOriginalFailure() throws Exception {
        final Address accepted = new InternetAddress("accepted@example.com");
        final Address unsent = new InternetAddress("unsent@example.com");
        final Address invalid = new InternetAddress("invalid@example.com");
        final SendFailedException failure = new SendFailedException("partial", null,
                new Address[]{accepted}, new Address[]{unsent}, new Address[]{invalid});
        final FailingTransport transport = new FailingTransport(failure);
        final PreparedMail preparedMail = preparedMail(new DeliveryEnvelope(null, null));

        final MailTransportResult result = MailTransportAdapterResolver.sendMessage(
                transport, preparedMail, Collections.<MailTransportAdapter>emptyList());

        assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.PARTIALLY_ACCEPTED);
        assertThat(result.getFailure()).containsSame(failure);
        assertThat(result.getAcceptedRecipients()).containsExactly(accepted);
        assertThat(result.getValidUnsentRecipients()).containsExactly(unsent);
        assertThat(result.getInvalidRecipients()).containsExactly(invalid);
    }

    @Test
    void unknownProviderRejectsDeliveryCapabilitiesBeforeSending() throws Exception {
        final RecordingTransport transport = new RecordingTransport();
        final PreparedMail preparedMail = preparedMail(new DeliveryEnvelope(
                "bounce@example.com",
                DeliveryStatusNotification.of(DeliveryStatusNotification.NotifyOption.FAILURE)));

        assertThatThrownBy(() -> MailTransportAdapterResolver.sendMessage(
                transport, preparedMail, Collections.<MailTransportAdapter>emptyList()))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining(RecordingTransport.class.getName())
                .hasMessageContaining("envelope sender or delivery-status notifications")
                .hasMessageContaining("matching MailTransportAdapter");
        assertThat(transport.sentMessage).isNull();
    }

    @Test
    void unknownProviderRejectsExactContentBeforeSending() throws Exception {
        final RecordingTransport transport = new RecordingTransport();
        final PreparedMail preparedMail = preparedMail(new DeliveryEnvelope(null, null), ContentRequirement.PRESERVE_ALL_BYTES);

        assertThatThrownBy(() -> MailTransportAdapterResolver.sendMessage(
                transport, preparedMail, Collections.<MailTransportAdapter>emptyList()))
                .isInstanceOf(MailTransportCompatibilityException.class)
                .hasMessageContaining("PRESERVE_ALL_BYTES");
        assertThat(transport.sentMessage).isNull();
    }

    @Test
    void adapterMustExplicitlySupportExactContent() throws Exception {
        final RecordingTransport transport = new RecordingTransport();
        final PreparedMail preparedMail = preparedMail(new DeliveryEnvelope(null, null), ContentRequirement.PRESERVE_ALL_BYTES);

        assertThatThrownBy(() -> MailTransportAdapterResolver.sendMessage(
                transport, preparedMail, Collections.<MailTransportAdapter>singletonList(new RecordingAdapter(true))))
                .isInstanceOf(MailTransportCompatibilityException.class)
                .hasMessageContaining(RecordingAdapter.class.getName())
                .hasMessageContaining("PRESERVE_ALL_BYTES");
        assertThat(transport.sentMessage).isNull();
    }

    @Test
    void exactlyOneSupportingAdapterOwnsSubmission() throws Exception {
        final RecordingTransport transport = new RecordingTransport();
        final PreparedMail preparedMail = preparedMail(new DeliveryEnvelope(null, null));
        final RecordingAdapter adapter = new RecordingAdapter(true);

        final MailTransportResult result = MailTransportAdapterResolver.sendMessage(
                transport, preparedMail, Arrays.<MailTransportAdapter>asList(new RecordingAdapter(false), adapter));

        assertThat(adapter.preparedMail).isSameAs(preparedMail);
        assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
        final SmtpServerResponse response = result.getSmtpResponse().get();
        assertThat(response.getReturnCode()).isEqualTo(250);
        assertThat(response.getResponse()).isEqualTo("queued");
        assertThat(transport.sentMessage).isNull();
    }

    @Test
    void ambiguousAdaptersFailInDeterministicClassNameOrder() throws Exception {
        final RecordingTransport transport = new RecordingTransport();
        final PreparedMail preparedMail = preparedMail(new DeliveryEnvelope(null, null));

        assertThatThrownBy(() -> MailTransportAdapterResolver.sendMessage(
                transport, preparedMail, Arrays.<MailTransportAdapter>asList(new ZAdapter(), new AAdapter())))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining(AAdapter.class.getName() + ", " + ZAdapter.class.getName());
    }

    @Test
    void recipientPreferencesCannotBeSilentlyDroppedByUnknownOrOlderAdapters() throws Exception {
        final RecordingTransport transport = new RecordingTransport();
        final PreparedMail mail = preparedMail(new DeliveryEnvelope(null, null, Collections.singletonList(
                new DeliveryRecipient("receiver@example.com", Collections.singleton(DeliveryStatusNotification.NotifyOption.NEVER)))));
        assertThatThrownBy(() -> MailTransportAdapterResolver.sendMessage(transport, mail, Collections.emptyList()))
                .isInstanceOf(MailTransportCompatibilityException.class);
        final RecordingAdapter olderAdapter = new RecordingAdapter(true);
        assertThatThrownBy(() -> MailTransportAdapterResolver.sendMessage(transport, mail, Collections.singletonList(olderAdapter)))
                .isInstanceOf(MailTransportCompatibilityException.class).hasMessageContaining("recipient-specific NOTIFY");
        assertThat(olderAdapter.preparedMail).isNull();
        assertThat(transport.sentMessage).isNull();
    }

    private static PreparedMail preparedMail(final DeliveryEnvelope envelope) throws MessagingException {
        return preparedMail(envelope, ContentRequirement.NORMAL);
    }

    private static PreparedMail preparedMail(final DeliveryEnvelope envelope, final ContentRequirement contentRequirement) throws MessagingException {
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setText("body");
        message.saveChanges();
        return new PreparedMail(message,
                new Address[]{new InternetAddress("receiver@example.com")}, envelope, contentRequirement);
    }

    private static final class RecordingTransport extends Transport {
        private Message sentMessage;
        private Address[] sentRecipients;

        private RecordingTransport() {
            super(Session.getInstance(new Properties()), new URLName("test", null, -1, null, null, null));
        }

        @Override
        public void sendMessage(final Message message, final Address[] addresses) {
            sentMessage = message;
            sentRecipients = addresses.clone();
        }
    }

    private static final class FailingTransport extends Transport {
        private final MessagingException failure;

        private FailingTransport(final MessagingException failure) {
            super(Session.getInstance(new Properties()), new URLName("test", null, -1, null, null, null));
            this.failure = failure;
        }

        @Override
        public void sendMessage(final Message message, final Address[] addresses) throws MessagingException {
            throw failure;
        }
    }

    private static class RecordingAdapter implements MailTransportAdapter {
        private final boolean supports;
        private PreparedMail preparedMail;

        private RecordingAdapter(final boolean supports) {
            this.supports = supports;
        }

        @Override
        public boolean supports(final Transport transport) {
            return supports;
        }

        @Override
        public MailTransportResult sendMessage(final Transport transport, final PreparedMail preparedMail) {
            this.preparedMail = preparedMail;
            return MailTransportResult.accepted(preparedMail.getRecipients(), new SmtpServerResponse(250, "queued"));
        }
    }

    private static final class AAdapter extends RecordingAdapter {
        private AAdapter() {
            super(true);
        }
    }

    private static final class ZAdapter extends RecordingAdapter {
        private ZAdapter() {
            super(true);
        }
    }
}
