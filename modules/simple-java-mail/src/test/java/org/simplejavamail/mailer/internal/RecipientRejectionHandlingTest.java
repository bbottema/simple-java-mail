package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.config.ConfigDiagnosticGroup;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.email.internal.InternalEmail;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_SEND_TO_ACCEPTED_RECIPIENTS;
import static org.simplejavamail.internal.config.EmailProperty.SENDING_TO_ACCEPTED_RECIPIENTS;

class RecipientRejectionHandlingTest {

    private final SimpleJavaMail ordinary = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private final byte[] exactBytes = "From: sender@example.org\r\nTo: receiver@example.org\r\nSubject: Exact\r\n\r\nbody\r\n"
            .getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void composedChoiceIsNullableReplaceableCopyableAndSerializable(final boolean choice) throws Exception {
        final EmailPopulatingBuilder builder = ordinary.emailBuilder().startingBlank();
        assertThat(builder.getSendingToAcceptedRecipients()).isNull();
        assertThat(builder.withSendingToAcceptedRecipients(!choice)).isSameAs(builder);
        assertThat(builder.withSendingToAcceptedRecipients(choice).getSendingToAcceptedRecipients()).isEqualTo(choice);
        final Email email = builder.buildEmail();
        assertThat(email.getSendingToAcceptedRecipients()).isEqualTo(choice);
        assertThat(email.toString()).contains("sendingToAcceptedRecipients=" + choice);
        assertThat(ordinary.emailBuilder().copying(email).buildEmail()).isEqualTo(email);
        assertThat(roundTrip(email).getSendingToAcceptedRecipients()).isEqualTo(choice);
        assertThat(ordinary.emailBuilder().copying(email).withSendingToAcceptedRecipients(!choice).buildEmail()).isNotEqualTo(email);
        assertThat(builder.clearSendingToAcceptedRecipients()).isSameAs(builder);
        assertThat(builder.getSendingToAcceptedRecipients()).isNull();
        assertThat(builder.buildEmail().getSendingToAcceptedRecipients()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void exactChoiceSurvivesCopyingAndSerializationWithoutChangingBytes(final boolean choice) throws Exception {
        final ExactEmailBuilder builder = exactBuilder();
        assertThat(builder.getSendingToAcceptedRecipients()).isNull();
        assertThat(builder.withSendingToAcceptedRecipients(!choice)).isSameAs(builder);
        final Email email = builder.withSendingToAcceptedRecipients(choice).buildEmail();
        final Email copied = ordinary.emailBuilder().copying(email).buildEmail();
        assertThat(copied.getSendingToAcceptedRecipients()).isEqualTo(choice);
        final Email restored = roundTrip(email);
        try (Mailer mailer = ordinary.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            // copying(Email) deliberately starts a new composition; exact originals and serialized snapshots retain authoritative bytes.
            for (final Email retained : List.of(email, restored)) {
                assertThat(retained.getSendingToAcceptedRecipients()).isEqualTo(choice);
                assertThat(mailer.rehearse(retained).getEmlBytes()).containsExactly(exactBytes);
            }
        }
        assertThat(builder.clearSendingToAcceptedRecipients()).isSameAs(builder);
        assertThat(builder.getSendingToAcceptedRecipients()).isNull();
        assertThat(builder.buildEmail().getSendingToAcceptedRecipients()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void defaultsAndOverridesPreserveFalseAndPropertySpecificSuppression(final boolean defaultChoice) throws Exception {
        final SimpleJavaMail factory = configured(Map.of("simplejavamail.defaults.sendtoacceptedrecipients", defaultChoice));
        final Email unset = factory.emailBuilder().startingBlank().buildEmail();
        assertThat(unset.getSendingToAcceptedRecipients()).as("Properties do not populate the local Email choice").isNull();
        final Email explicit = ordinary.emailBuilder().startingBlank().withSendingToAcceptedRecipients(!defaultChoice).buildEmail();
        try (Mailer mailer = factory.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            assertThat(prepared(mailer, unset).getSendingToAcceptedRecipients()).isEqualTo(defaultChoice);
            assertThat(prepared(mailer, explicit).getSendingToAcceptedRecipients()).isEqualTo(!defaultChoice);
            assertThat(prepared(mailer, ordinary.emailBuilder().copying(explicit).clearSendingToAcceptedRecipients().buildEmail())
                    .getSendingToAcceptedRecipients()).isEqualTo(defaultChoice);
            assertThat(prepared(mailer, ordinary.emailBuilder().startingBlank().ignoringDefaults().buildEmail())
                    .getSendingToAcceptedRecipients()).isNull();
            assertThat(prepared(mailer, ordinary.emailBuilder().startingBlank().dontApplyDefaultValueFor(SENDING_TO_ACCEPTED_RECIPIENTS)
                    .buildEmail()).getSendingToAcceptedRecipients()).isNull();
        }
        final Email override = ordinary.emailBuilder().startingBlank().withSendingToAcceptedRecipients(defaultChoice).buildEmail();
        try (Mailer mailer = factory.mailerBuilder().withEmailOverrides(override).withTransportModeLoggingOnly(true).buildMailer()) {
            assertThat(prepared(mailer, explicit).getSendingToAcceptedRecipients()).isEqualTo(defaultChoice);
            assertThat(prepared(mailer, ordinary.emailBuilder().copying(explicit).ignoringOverrides().buildEmail())
                    .getSendingToAcceptedRecipients()).isEqualTo(!defaultChoice);
            assertThat(prepared(mailer, ordinary.emailBuilder().copying(explicit)
                    .dontApplyOverrideValueFor(SENDING_TO_ACCEPTED_RECIPIENTS).buildEmail()).getSendingToAcceptedRecipients()).isEqualTo(!defaultChoice);
        }
        final Email replacement = ordinary.emailBuilder().startingBlank().buildEmail();
        try (Mailer mailer = factory.mailerBuilder().withEmailDefaults(replacement).withTransportModeLoggingOnly(true).buildMailer()) {
            assertThat(prepared(mailer, unset).getSendingToAcceptedRecipients()).isNull();
        }
        assertThat(ordinary.emailBuilder().startingBlank().buildEmailCompletedWithDefaultsAndOverrides().getSendingToAcceptedRecipients()).isNull();
        assertThat(factory.emailBuilder().startingBlank().buildEmailCompletedWithDefaultsAndOverrides().getSendingToAcceptedRecipients())
                .isEqualTo(defaultChoice);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void locksSurviveClearingSuppressionAndTemplatesAndRejectConflicts(final boolean lockedChoice) throws Exception {
        final SimpleJavaMail factory = configured(Map.of("simplejavamail.locked.defaults.sendtoacceptedrecipients", lockedChoice,
                "simplejavamail.defaults.sendtoacceptedrecipients", !lockedChoice));
        final Email emptyTemplate = ordinary.emailBuilder().startingBlank().buildEmail();
        final Email suppressed = ordinary.emailBuilder().startingBlank().withSendingToAcceptedRecipients(!lockedChoice)
                .clearSendingToAcceptedRecipients().ignoringDefaults().ignoringOverrides()
                .dontApplyDefaultValueFor(SENDING_TO_ACCEPTED_RECIPIENTS).dontApplyOverrideValueFor(SENDING_TO_ACCEPTED_RECIPIENTS).buildEmail();
        final Email equal = ordinary.emailBuilder().startingBlank().withSendingToAcceptedRecipients(lockedChoice).buildEmail();
        final Email conflicting = ordinary.emailBuilder().startingBlank().withSendingToAcceptedRecipients(!lockedChoice).buildEmail();
        try (Mailer mailer = factory.mailerBuilder().withEmailDefaults(emptyTemplate).withEmailOverrides(emptyTemplate)
                .withTransportModeLoggingOnly(true).buildMailer()) {
            assertThat(prepared(mailer, suppressed).getSendingToAcceptedRecipients()).isEqualTo(lockedChoice);
            assertThat(prepared(mailer, equal).getSendingToAcceptedRecipients()).isEqualTo(lockedChoice);
            assertThatThrownBy(() -> prepared(mailer, conflicting)).hasMessageContaining("simplejavamail.locked.defaults.sendtoacceptedrecipients");
        }
        try (Mailer defaults = factory.mailerBuilder().withTransportModeLoggingOnly(true).withEmailDefaults(conflicting).buildMailer();
             Mailer overrides = factory.mailerBuilder().withTransportModeLoggingOnly(true).withEmailOverrides(conflicting).buildMailer()) {
            assertThatThrownBy(() -> prepared(defaults, suppressed)).hasMessageContaining("simplejavamail.locked.defaults.sendtoacceptedrecipients");
            assertThatThrownBy(() -> prepared(overrides, suppressed)).hasMessageContaining("simplejavamail.locked.defaults.sendtoacceptedrecipients");
        }
        assertThat(ordinary.emailBuilder().startingBlank().buildEmailCompletedWithDefaultsAndOverrides().getSendingToAcceptedRecipients()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void exactEmailsBypassOrdinaryTemplatesButRetainCompatibleEnvelopeLocks(final boolean choice) throws Exception {
        final SimpleJavaMail factory = configured(Map.of("simplejavamail.defaults.sendtoacceptedrecipients", !choice));
        final Email explicit = exactBuilder().withSendingToAcceptedRecipients(choice).buildEmail();
        try (Mailer mailer = factory.mailerBuilder().withEmailOverrides(ordinary.emailBuilder().startingBlank()
                .withSendingToAcceptedRecipients(!choice).buildEmail()).withTransportModeLoggingOnly(true).buildMailer()) {
            assertThat(prepared(mailer, explicit).getSendingToAcceptedRecipients()).isEqualTo(choice);
            assertThat(prepared(mailer, exactBuilder().buildEmail())
                    .getSendingToAcceptedRecipients()).isNull();
        }
        final SimpleJavaMail locked = configured(Map.of("simplejavamail.locked.defaults.sendtoacceptedrecipients", choice));
        try (Mailer mailer = locked.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            final Email absent = exactBuilder().buildEmail();
            assertThat(prepared(mailer, absent).getSendingToAcceptedRecipients()).isEqualTo(choice);
            assertThat(mailer.rehearse(absent).getEmlBytes()).containsExactly(exactBytes);
            assertThat(mailer.rehearse(explicit).getEmlBytes()).containsExactly(exactBytes);
            final Email conflict = exactBuilder().withSendingToAcceptedRecipients(!choice).buildEmail();
            assertThatThrownBy(() -> mailer.rehearse(conflict)).hasMessageContaining("locked.defaults.sendtoacceptedrecipients");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void customMailersReceiveTheGovernedChoiceButCannotClaimToHonorItsLock(final boolean choice) throws Exception {
        final SimpleJavaMail factory = configured(Map.of("simplejavamail.defaults.sendtoacceptedrecipients", choice));
        final AtomicReference<Email> received = new AtomicReference<>();
        final CustomMailer customMailer = mock(CustomMailer.class);
        doAnswer(call -> {
            received.set(call.getArgument(2));
            return null;
        }).when(customMailer).sendMessage(any(), any(), any(), any());
        try (Mailer mailer = factory.mailerBuilder().withCustomMailer(customMailer).buildMailer()) {
            mailer.sync().sendMail(ordinary.emailBuilder().startingBlank().from("sender@example.org")
                    .withRecipients(new Recipient(null, "receiver@example.org", jakarta.mail.Message.RecipientType.TO, null))
                    .withPlainText("body").buildEmail());
            assertThat(received.get().getSendingToAcceptedRecipients()).isEqualTo(choice);
        }
        final SimpleJavaMail locked = configured(Map.of("simplejavamail.locked.defaults.sendtoacceptedrecipients", choice));
        assertThatThrownBy(() -> locked.mailerBuilder().withCustomMailer(customMailer).buildMailer())
                .hasMessageContaining("CustomMailer").hasMessageContaining("simplejavamail.locked.defaults.sendtoacceptedrecipients");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void propertyHasBooleanTypeProvenanceAndAnUnredactedEmailDefaultDiagnostic(final boolean choice) {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap("deployment", Map.of(
                "simplejavamail.defaults.sendtoacceptedrecipients", Boolean.toString(choice))).load();
        assertThat(config.getBooleanProperty(DEFAULT_SEND_TO_ACCEPTED_RECIPIENTS)).isEqualTo(choice);
        assertThat(config.getPropertySource(DEFAULT_SEND_TO_ACCEPTED_RECIPIENTS)).isEqualTo("deployment");
        assertThat(config.getDiagnostics().getProperties(ConfigDiagnosticGroup.EMAIL_DEFAULTS)).anySatisfy(entry -> {
            assertThat(entry.getPropertyName()).isEqualTo("simplejavamail.defaults.sendtoacceptedrecipients");
            assertThat(entry.getDisplayValue()).isEqualTo(Boolean.toString(choice));
            assertThat(entry.getSourceName()).isEqualTo("deployment");
            assertThat(entry.isRedacted()).isFalse();
        });
        assertThat(ConfigLoader.builder().load().hasProperty(DEFAULT_SEND_TO_ACCEPTED_RECIPIENTS)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void loggingOnlyStillReportsCompletionWithoutAnSmtpInvocation(final boolean choice) throws Exception {
        final AtomicReference<MailSendOutcome> observed = new AtomicReference<>();
        try (Mailer mailer = ordinary.mailerBuilder().withTransportModeLoggingOnly(true).withProperty("mail.smtp.sendpartial", true)
                .withMailSendObserver(observed::set).buildMailer()) {
            final Email email = ordinary.emailBuilder().startingBlank().from("sender@example.org")
                    .withRecipients(new Recipient(null, "receiver@example.org", jakarta.mail.Message.RecipientType.TO, null))
                    .withPlainText("body").withSendingToAcceptedRecipients(choice).buildEmail();
            final MailSubmissionReceipt receipt = mailer.sync().sendMail(email);
            assertThat(observed.get().isLoggingOnly()).isTrue();
            assertThat(observed.get().isSuccessful()).isTrue();
            assertThat(observed.get().getSubmissionReceipt()).containsSame(receipt);
            assertThat(receipt.getSmtpResponse()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void envelopePreservesFalseAndNullableChoiceThroughSerialization(final boolean choice) throws Exception {
        final DeliveryEnvelope envelope = new DeliveryEnvelope(null, null, List.of(), false, false, choice);
        assertThat(envelope.hasProviderSpecificOptions()).isTrue();
        assertThat(roundTrip(envelope).getSendingToAcceptedRecipients()).isEqualTo(choice);
        assertThat(new DeliveryEnvelope(null, null).getSendingToAcceptedRecipients()).isNull();
        assertThat(roundTrip(new DeliveryEnvelope(null, null)).getSendingToAcceptedRecipients()).isNull();
    }

    @Test
    void olderSerializedEnvelopeRestoresAnUnsetChoice() throws Exception {
        // Written by the pre-feature DeliveryEnvelope from commit 6599fdd0 using JDK 11; its descriptor contains no new field.
        final byte[] fixture;
        try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream(
                "/serialization/delivery-envelope-before-recipient-rejection.base64"))) {
            fixture = Base64.getDecoder().decode(new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim());
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(fixture))) {
            final DeliveryEnvelope restored = (DeliveryEnvelope) input.readObject();
            assertThat(restored.getEnvelopeFrom()).isEqualTo("sender@example.org");
            assertThat(restored.getSendingToAcceptedRecipients()).isNull();
            assertThat(restored.getRecipientOptions()).isEmpty();
            assertThat(restored.hasProviderSpecificOptions()).as("The older snapshot still carries its explicit envelope sender").isTrue();
        }
    }

    private static Email prepared(final Mailer mailer, final Email email) {
        return InternalEmail.requireInternalEmail(email).prepareForConversion(mailer.getEmailGovernance());
    }

    private ExactEmailBuilder exactBuilder() {
        return ordinary.emailBuilder().startingFromExactEml(exactBytes).withEnvelopeRecipients("receiver@example.org");
    }

    private static SimpleJavaMail configured(final Map<String, ?> values) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(values).load());
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(final T value) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) input.readObject();
        }
    }
}
