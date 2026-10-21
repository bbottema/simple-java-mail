package org.simplejavamail.api.email.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.DELAY;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.NEVER;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.HEADERS_ONLY;

class SmtpDeliveryOptionsValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "bad\r\nvalue", "bad\tvalue", "bad\u0000value", "bad\u007fvalue", "caf\u00e9",
            "++++++++++++++++++++++++++++++++"})
    void invalidIdentifiersCanBeInspectedAndCorrectedAfterAFailedBuild(final String identifier) {
        final SmtpDeliveryOptions.Builder builder = SmtpDeliveryOptions.builder()
                .withTlsRequiredForOnwardDelivery().withSendingToAcceptedRecipients(false).fixingEnvelopeId(identifier);
        assertThat(builder.getEnvelopeId()).isEqualTo(identifier);
        assertThat(builder.getDeliveryStatusNotificationNotifyOptions()).isEmpty();
        assertThat(builder.getDeliveryStatusNotificationReturnOption()).isNull();
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DSN envelope identifier");
        assertThat(builder.getEnvelopeId()).isEqualTo(identifier);

        final SmtpDeliveryOptions corrected = builder.fixingEnvelopeId("invoice-42").build();
        assertThat(corrected.getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("invoice-42");
        assertThat(corrected.isTlsRequiredForOnwardDelivery()).isTrue();
        assertThat(corrected.getSendingToAcceptedRecipients()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("componentEventSetters")
    void componentEventSettersCollectInvalidChoicesUntilBuild(final String name,
            final BiConsumer<SmtpDeliveryOptions.Builder, NotifyOption[]> setter) {
        final NotifyOption[] events = {NEVER, FAILURE};
        final SmtpDeliveryOptions.Builder builder = SmtpDeliveryOptions.builder()
                .withDeliveryStatusNotificationReturnOption(HEADERS_ONLY).fixingEnvelopeId("fixed");
        setter.accept(builder, events);
        events[0] = DELAY;
        final Set<NotifyOption> inspected = builder.getDeliveryStatusNotificationNotifyOptions();
        assertThat(inspected).containsExactly(NEVER, FAILURE);
        inspected.clear();
        assertThat(builder.getDeliveryStatusNotificationNotifyOptions()).containsExactly(NEVER, FAILURE);
        assertThat(builder.getDeliveryStatusNotificationReturnOption()).isEqualTo(HEADERS_ONLY);
        assertThat(builder.getEnvelopeId()).isEqualTo("fixed");
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NEVER");

        setter.accept(builder, new NotifyOption[] {FAILURE, DELAY, FAILURE});
        final DeliveryStatusNotification corrected = builder.build().getDeliveryStatusNotification();
        assertThat(corrected.getNotifyOptions()).containsExactly(FAILURE, DELAY);
        assertThat(corrected.getReturnOption()).isEqualTo(HEADERS_ONLY);
        assertThat(corrected.getEnvelopeId()).isEqualTo("fixed");
    }

    private static Stream<Arguments> componentEventSetters() {
        final BiConsumer<SmtpDeliveryOptions.Builder, NotifyOption[]> named =
                SmtpDeliveryOptions.Builder::withDeliveryStatusNotificationNotifyOptions;
        final BiConsumer<SmtpDeliveryOptions.Builder, NotifyOption[]> convenience =
                SmtpDeliveryOptions.Builder::withDeliveryStatusNotification;
        return Stream.of(Arguments.of("named notification setter", named), Arguments.of("event-only convenience", convenience));
    }

    @Test
    void completeSetterCollectsChoicesAndReplacesThePreviousIdentifier() {
        final NotifyOption[] events = {NEVER, FAILURE};
        final SmtpDeliveryOptions.Builder builder = SmtpDeliveryOptions.builder().fixingEnvelopeId("old")
                .withDeliveryStatusNotification(HEADERS_ONLY, events);
        events[0] = DELAY;
        assertThat(builder.getEnvelopeId()).isNull();
        assertThat(builder.getDeliveryStatusNotificationReturnOption()).isEqualTo(HEADERS_ONLY);
        assertThat(builder.getDeliveryStatusNotificationNotifyOptions()).containsExactly(NEVER, FAILURE);
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NEVER");
        builder.withDeliveryStatusNotificationNotifyOptions(FAILURE);
        assertThat(builder.build().getDeliveryStatusNotification()).isEqualTo(DeliveryStatusNotification.of(HEADERS_ONLY, FAILURE));

        builder.withDeliveryStatusNotification(null, new NotifyOption[0]);
        assertThat(builder.getDeliveryStatusNotificationReturnOption()).isNull();
        assertThat(builder.getDeliveryStatusNotificationNotifyOptions()).isEmpty();
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("At least one");
        assertThat(builder.withDeliveryStatusNotificationReturnOption(HEADERS_ONLY).build().getDeliveryStatusNotification())
                .isEqualTo(DeliveryStatusNotification.of(HEADERS_ONLY));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("componentEventSetters")
    void explicitlyEmptyEventsNeedACompleteRequestWhereNamedClearsAllowAbsence(final String name,
            final BiConsumer<SmtpDeliveryOptions.Builder, NotifyOption[]> setter) {
        final SmtpDeliveryOptions.Builder builder = SmtpDeliveryOptions.builder().withTlsRequiredForOnwardDelivery();
        assertThat(builder.build().getDeliveryStatusNotification()).isNull();
        setter.accept(builder, new NotifyOption[0]);
        assertThat(builder.getDeliveryStatusNotificationNotifyOptions()).isEmpty();
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("At least one");
        assertThat(builder.clearDeliveryStatusNotificationNotifyOptions().build().getDeliveryStatusNotification()).isNull();
        assertThat(builder.isTlsRequiredForOnwardDelivery()).isTrue();
        setter.accept(builder, new NotifyOption[0]);
        assertThat(builder.fixingEnvelopeId("fixed").build().getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("fixed");
        assertThat(builder.fixingEnvelopeId(null).build().getDeliveryStatusNotification()).isNull();
    }

    @Test
    void requiredArrayAndEventArgumentsAreCheckedBeforeChangingChoices() {
        final SmtpDeliveryOptions.Builder builder = SmtpDeliveryOptions.builder()
                .withDeliveryStatusNotification(HEADERS_ONLY, FAILURE).fixingEnvelopeId("fixed");
        for (Arguments argument : componentEventSetters().toArray(Arguments[]::new)) {
            @SuppressWarnings("unchecked") final BiConsumer<SmtpDeliveryOptions.Builder, NotifyOption[]> setter =
                    (BiConsumer<SmtpDeliveryOptions.Builder, NotifyOption[]>) argument.get()[1];
            assertThatThrownBy(() -> setter.accept(builder, null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> setter.accept(builder, new NotifyOption[] {DELAY, null}))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("null");
        }
        assertThatThrownBy(() -> builder.withDeliveryStatusNotification(null, (NotifyOption[]) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.withDeliveryStatusNotification(HEADERS_ONLY, DELAY, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("null");
        assertThat(builder.getDeliveryStatusNotificationNotifyOptions()).containsExactly(FAILURE);
        assertThat(builder.getDeliveryStatusNotificationReturnOption()).isEqualTo(HEADERS_ONLY);
        assertThat(builder.getEnvelopeId()).isEqualTo("fixed");
    }

    @Test
    void editsClearsCopiesAndSerializationPreserveCompletedSnapshots() throws Exception {
        final SmtpDeliveryOptions.Builder builder = SmtpDeliveryOptions.builder()
                .withDeliveryStatusNotification(HEADERS_ONLY, FAILURE).fixingEnvelopeId("fixed")
                .withTlsRequiredForOnwardDelivery().withSendingToAcceptedRecipients(false);
        final SmtpDeliveryOptions original = builder.build();
        builder.getDeliveryStatusNotificationNotifyOptions().clear();
        builder.withDeliveryStatusNotificationNotifyOptions(NEVER, FAILURE);
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
        assertThat(original.getDeliveryStatusNotification().getNotifyOptions()).containsExactly(FAILURE);
        assertThatThrownBy(() -> original.getDeliveryStatusNotification().getNotifyOptions().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        final SmtpDeliveryOptions.Builder copied = original.toBuilder();
        copied.clearDeliveryStatusNotificationReturnOption().clearDeliveryStatusNotificationNotifyOptions();
        assertThat(copied.build().getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("fixed");
        assertThat(copied.clearEnvelopeId().build().getDeliveryStatusNotification()).isNull();
        assertThat(copied.isTlsRequiredForOnwardDelivery()).isTrue();
        assertThat(copied.getSendingToAcceptedRecipients()).isFalse();
        assertThat(original.toBuilder().build()).isEqualTo(original);

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(original); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThat(input.readObject()).isEqualTo(original);
        }
        assertThat(builder.withDeliveryStatusNotification((DeliveryStatusNotification) null).build().getDeliveryStatusNotification()).isNull();
        assertThat(builder.isTlsRequiredForOnwardDelivery()).isTrue();
        assertThat(builder.getSendingToAcceptedRecipients()).isFalse();
        assertThat(original.getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("fixed");
    }

    @Test
    void standaloneConstructionAndExternalParsingKeepTheirValidationBoundaries() {
        assertThatThrownBy(() -> DeliveryStatusNotification.of(NEVER, FAILURE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeliveryStatusNotification.of(new NotifyOption[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeliveryStatusNotification.builder().notifyOptions(NEVER, FAILURE).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeliveryStatusNotification.builder().envelopeId("").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeliveryStatusNotification.parseNotifyOptions("NEVER,FAILURE"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
