package org.simplejavamail.email.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationClearingTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @Test
    void clearingRestoresTheUnsetRequestAndAddressWithoutMutatingExistingEmail() {
        final EmailPopulatingBuilder builder = message().withDispositionNotificationTo("read@example.org")
                .withReturnReceiptTo("received@example.org");
        final Email before = builder.buildEmail();
        final Email cleared = builder.clearDispositionNotificationTo().clearReturnReceiptTo().buildEmail();

        assertUnset(cleared);
        assertUnset(mail.emailBuilder().copying(cleared).buildEmail());
        assertThat(cleared.getSubject()).isEqualTo("unchanged");
        assertThat(before.getUseDispositionNotificationTo()).isTrue();
        assertThat(before.getDispositionNotificationTo().getAddress()).isEqualTo("read@example.org");
        assertThat(before.getUseReturnReceiptTo()).isTrue();
    }

    @Test
    void clearedRequestsInheritEmailDefaultsAndRenderTheirHeaders() throws Exception {
        final Email defaults = mail.emailBuilder().startingBlank().withDispositionNotificationTo("default-read@example.org")
                .withReturnReceiptTo("default-return@example.org").buildEmail();
        try (Mailer mailer = mail.mailerBuilder().withSMTPServerHost("localhost").withEmailDefaults(defaults).buildMailer()) {
            final Email cleared = clearedMessage();
            final Email governed = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(cleared);
            assertThat(governed.getUseDispositionNotificationTo()).isTrue();
            assertThat(governed.getDispositionNotificationTo().getAddress()).isEqualTo("default-read@example.org");
            assertThat(governed.getUseReturnReceiptTo()).isTrue();
            assertThat(governed.getReturnReceiptTo().getAddress()).isEqualTo("default-return@example.org");
            final String eml = new String(mailer.rehearse(cleared).getEmlBytes(), StandardCharsets.UTF_8);
            assertThat(eml).contains("Disposition-Notification-To: default-read@example.org")
                    .contains("Return-Receipt-To: default-return@example.org");
        }
    }

    @Test
    void overridesStillWinAndDefaultSuppressionStillPreventsARequest() throws Exception {
        final Email defaults = mail.emailBuilder().startingBlank().withDispositionNotificationTo("default@example.org")
                .withReturnReceiptTo("default@example.org").buildEmail();
        final Email overrides = mail.emailBuilder().startingBlank().withDispositionNotificationTo("override@example.org")
                .withReturnReceiptTo("override@example.org").buildEmail();
        try (Mailer mailer = mail.mailerBuilder().withSMTPServerHost("localhost")
                .withEmailDefaults(defaults).withEmailOverrides(overrides).buildMailer()) {
            final Email governed = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(clearedMessage());
            assertThat(governed.getDispositionNotificationTo().getAddress()).isEqualTo("override@example.org");
            assertThat(governed.getReturnReceiptTo().getAddress()).isEqualTo("override@example.org");
        }
        try (Mailer mailer = mail.mailerBuilder().withSMTPServerHost("localhost").withEmailDefaults(defaults).buildMailer()) {
            final Email suppressed = mail.emailBuilder().copying(clearedMessage())
                    .withTemplateOptions(EmailTemplateOptions.builder()
                            .dontApplyDefaultValueFor(EmailProperty.USE_DISPOSITION_NOTIFICATION_TO, EmailProperty.USE_RETURN_RECEIPT_TO)
                            .build()).buildEmail();
            assertUnset(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(suppressed));
        }
    }

    @Test
    void addressPropertiesAloneDoNotReenableClearedRequestsButSupplyAutomaticDestinations() throws Exception {
        final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.defaults.from.address", "configured-from@example.org",
                "simplejavamail.defaults.replyto.address", "configured-reply@example.org")).load());
        try (Mailer mailer = configured.mailerBuilder().withSMTPServerHost("localhost").buildMailer()) {
            assertUnset(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(clearedMessage()));
            final Email automatic = configured.emailBuilder().startingBlank().withDispositionNotificationTo().withReturnReceiptTo().buildEmail();
            final Email governed = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(automatic);
            assertThat(governed.getDispositionNotificationTo().getAddress()).isEqualTo("configured-reply@example.org");
            assertThat(governed.getReturnReceiptTo().getAddress()).isEqualTo("configured-reply@example.org");
        }
    }

    private Email clearedMessage() {
        return message().withDispositionNotificationTo("old@example.org").withReturnReceiptTo("old@example.org")
                .clearDispositionNotificationTo().clearReturnReceiptTo().buildEmail();
    }

    private EmailPopulatingBuilder message() {
        return mail.emailBuilder().startingBlank().from("sender@example.org").to("recipient@example.org")
                .withSubject("unchanged").withPlainText("body");
    }

    private static void assertUnset(final Email email) {
        assertThat(email.getUseDispositionNotificationTo()).isNull();
        assertThat(email.getDispositionNotificationTo()).isNull();
        assertThat(email.getUseReturnReceiptTo()).isNull();
        assertThat(email.getReturnReceiptTo()).isNull();
    }
}
