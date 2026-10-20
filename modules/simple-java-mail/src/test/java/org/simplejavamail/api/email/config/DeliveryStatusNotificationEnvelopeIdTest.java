package org.simplejavamail.api.email.config;

import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.email.config.SmtpDeliveryOptions;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.api.email.EmailProperty;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.DELAY;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.HEADERS_ONLY;

class DeliveryStatusNotificationEnvelopeIdTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @Test
    void identifierIsIndependentOfNotifyAndReturnAndCopyBuildersAreIsolated() {
        final DeliveryStatusNotification notification = DeliveryStatusNotification.builder().envelopeId(" order+42= ").build();
        final DeliveryStatusNotification changed = notification.toBuilder().envelopeId("next").notifyOptions(FAILURE).build();
        assertThat(notification.getEnvelopeId()).isEqualTo(" order+42= ");
        assertThat(notification.getNotifyOptions()).isEmpty();
        assertThat(notification.getReturnOption()).isNull();
        assertThat(changed.getEnvelopeId()).isEqualTo("next");
        assertThat(notification.toBuilder().build()).isEqualTo(notification).hasSameHashCodeAs(notification);
        assertThat(changed).isNotEqualTo(notification);
        assertThatThrownBy(() -> changed.getNotifyOptions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bad\r\nRCPT TO:<other@example.test>", "bad\tvalue", "bad\u0000value", "bad\u007fvalue", "café", "\ud83d\ude00"})
    void invalidIdentifiersFailBeforeTheyCanReachAnEmail(final String identifier) {
        assertThatThrownBy(() -> DeliveryStatusNotification.builder().envelopeId(identifier).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DSN envelope identifier");
        assertThatThrownBy(() -> mail.emailBuilder().startingBlank().withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().fixingEnvelopeId(identifier).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> exactBuilder().withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().fixingEnvelopeId(identifier).build()).buildEmail())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitCountsEncodedCharactersAndNeverTruncates() {
        for (final String identifier : new String[]{"a".repeat(94), "+".repeat(31) + "x", "=".repeat(31) + "x", " ".repeat(31) + "x"}) {
            assertThat(DeliveryStatusNotification.builder().envelopeId(identifier).build().getEnvelopeId()).isEqualTo(identifier);
        }
        for (final String identifier : new String[]{"a".repeat(95), "+".repeat(31) + "xx", "=".repeat(32), " ".repeat(32)}) {
            assertThatThrownBy(() -> DeliveryStatusNotification.builder().envelopeId(identifier).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("94 characters").hasMessageContaining("100 including ENVID=");
        }
    }

    @Test
    void partialBuilderUpdatesRetainTheIdentifierAndClearingItRetainsOtherOptions() {
        final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank()
                .withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().fixingEnvelopeId("first").withDeliveryStatusNotificationNotifyOptions(FAILURE, DELAY).withDeliveryStatusNotificationReturnOption(HEADERS_ONLY).build());
        assertThat(builder.getSmtpDeliveryOptions().getDeliveryStatusNotification()).isEqualTo(
                DeliveryStatusNotification.builder().envelopeId("first").notifyOptions(FAILURE, DELAY).returnOption(HEADERS_ONLY).build());
        builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().withDeliveryStatusNotificationReturnOption(null).withDeliveryStatusNotificationNotifyOptions(new DeliveryStatusNotification.NotifyOption[0]).build());
        assertThat(builder.getSmtpDeliveryOptions().getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("first");
        builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().fixingEnvelopeId(null).build());
        assertThat(builder.getSmtpDeliveryOptions().getDeliveryStatusNotification()).isNull();
        builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().withDeliveryStatusNotification(HEADERS_ONLY, FAILURE).fixingEnvelopeId("second").fixingEnvelopeId(null).build());
        assertThat(builder.getSmtpDeliveryOptions().getDeliveryStatusNotification()).isEqualTo(DeliveryStatusNotification.of(HEADERS_ONLY, FAILURE));
        builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().fixingEnvelopeId("third").clearDeliveryStatusNotification().build());
        assertThat(builder.getSmtpDeliveryOptions().getDeliveryStatusNotification()).isNull();
    }

    @Test
    void completeDsnReplacementIntentionallyReplacesTheIdentifier() {
        final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank().withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().fixingEnvelopeId("old").build());
        builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().withDeliveryStatusNotification(HEADERS_ONLY, FAILURE).build());
        assertThat(builder.getSmtpDeliveryOptions().getDeliveryStatusNotification().getEnvelopeId()).isNull();
    }

    @Test
    void copyingAndSerializationRetainTheUnencodedIdentifier() throws Exception {
        final Email original = mail.emailBuilder().startingBlank().withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().fixingEnvelopeId("id+42").build()).buildEmail();
        final Email copy = mail.emailBuilder().copying(original).buildEmail();
        assertThat(copy.getDeliveryStatusNotification()).isEqualTo(original.getDeliveryStatusNotification());
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(original.getDeliveryStatusNotification());
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThat(input.readObject()).isEqualTo(original.getDeliveryStatusNotification());
        }
    }

    @Test
    void exactBuilderRetainsTheIdentifierInEitherConfigurationOrder() {
        final DeliveryStatusNotification options = DeliveryStatusNotification.builder().envelopeId("exact+42")
                .returnOption(HEADERS_ONLY).notifyOptions(FAILURE).build();
        assertThat(exactBuilder().withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withDeliveryStatusNotification(options).withDeliveryStatusNotificationNotifyOptions(FAILURE).withDeliveryStatusNotificationReturnOption(HEADERS_ONLY).build()).buildEmail().getDeliveryStatusNotification()).isEqualTo(options);
        assertThat(exactBuilder().withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().fixingEnvelopeId("exact+42").withDeliveryStatusNotificationReturnOption(HEADERS_ONLY).withDeliveryStatusNotificationNotifyOptions(FAILURE).build()).buildEmail().getDeliveryStatusNotification()).isEqualTo(options);
        assertThat(exactBuilder().withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().fixingEnvelopeId("first").fixingEnvelopeId("second").build())
                .buildEmail().getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("second");
    }

    @Test
    void governanceAppliesTheCompleteDsnValueAndHonorsSuppression() throws Exception {
        final Email defaults = mail.emailBuilder().startingBlank().withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().fixingEnvelopeId("default").build()).buildEmail();
        final Email overrides = mail.emailBuilder().startingBlank().withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().fixingEnvelopeId("override").build()).buildEmail();
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 25).withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).build())
                .withEmailDefaults(defaults).withEmailOverrides(overrides).buildMailer()) {
            final Email provided = mail.emailBuilder().startingBlank().withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().fixingEnvelopeId("provided").build()).buildEmail();
            assertThat(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(provided)
                    .getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("override");
            final Email ignoringOverride = mail.emailBuilder().copying(provided)
                    .withTemplateOptions(EmailTemplateOptions.builder().dontApplyOverrideValueFor(EmailProperty.DELIVERY_STATUS_NOTIFICATION).build()).buildEmail();
            assertThat(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(ignoringOverride)
                    .getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("provided");
        }
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 25).withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).build()).withEmailDefaults(defaults).buildMailer()) {
            assertThat(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(mail.emailBuilder().startingBlank().buildEmail())
                    .getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("default");
            final Email ignoringDefault = mail.emailBuilder().startingBlank()
                    .withTemplateOptions(EmailTemplateOptions.builder().dontApplyDefaultValueFor(EmailProperty.DELIVERY_STATUS_NOTIFICATION).build()).buildEmail();
            assertThat(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(ignoringDefault).getDeliveryStatusNotification()).isNull();
        }
        final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                ConfigLoader.Property.DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY.key(), "FAILURE")).load());
        final Email provided = configured.emailBuilder().startingBlank().withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().fixingEnvelopeId("provided").build())
                .buildEmailCompletedWithDefaultsAndOverrides();
        assertThat(provided.getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("provided");
    }

    @Test
    void exactBuilderCanResumeAutomaticIdentifiersWithoutLosingOtherOptions() {
        assertThat(exactBuilder().withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().fixingEnvelopeId("fixed").fixingEnvelopeId(null).build()).buildEmail().getDeliveryStatusNotification()).isNull();
        assertThat(exactBuilder().withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withDeliveryStatusNotification(DeliveryStatusNotification.builder().envelopeId("fixed").build()).fixingEnvelopeId(null).build()).buildEmail().getDeliveryStatusNotification()).isNull();
        assertThat(exactBuilder().withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withDeliveryStatusNotificationNotifyOptions(FAILURE).fixingEnvelopeId("fixed").fixingEnvelopeId(null).build()).buildEmail().getDeliveryStatusNotification()).isEqualTo(DeliveryStatusNotification.of(FAILURE));
    }

    private ExactEmailBuilder exactBuilder() {
        return mail.emailBuilder().startingFromExactEml(("From: sender@example.test\r\nTo: receiver@example.test\r\n\r\nbody\r\n")
                .getBytes(US_ASCII)).withEnvelopeRecipients("receiver@example.test");
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void customMailerReceivesFixedIdentifierButDoesNotReportAnUnverifiedEffectiveIdentifier(final boolean loggingOnly, final boolean fixed) throws Exception {
        final AtomicReference<Email> delivered = new AtomicReference<>();
        final CustomMailer customMailer = new CustomMailer() {
            @Override
            public void testConnection(final OperationalConfig config, final Session session) {
                throw new AssertionError("Sending must not probe the connection");
            }

            @Override
            public void sendMessage(final OperationalConfig config, final Session session, final Email email, final MimeMessage message) {
                delivered.set(email);
                try {
                    assertThat(message.getHeader("Original-Envelope-ID")).isNull();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
        };
        final Email email = mail.emailBuilder().startingBlank().from("sender@example.test")
                .withRecipients(new Recipient(null, "receiver@example.test", Message.RecipientType.TO, null))
                .withPlainText("Synthetic custom send").withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().fixingEnvelopeId(fixed ? "custom+42" : null).build()).buildEmail();
        try (Mailer mailer = mail.mailerBuilder().withCustomMailer(customMailer).withTransportModeLoggingOnly(loggingOnly).buildMailer()) {
            final MailSubmissionReceipt receipt = mailer.sync().sendMail(email);
            assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
            assertThat(receipt.getEnvelopeId()).isNull();
        }
        if (loggingOnly) {
            assertThat(delivered.get()).isNull();
        } else if (fixed) {
            assertThat(delivered.get().getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("custom+42");
        } else {
            assertThat(delivered.get().getDeliveryStatusNotification()).isNull();
        }
    }
}
