package org.simplejavamail.mailer;

import com.sanctionco.jmail.JMail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.api.mailer.MailRehearsal;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.ValidationConfig;
import org.simplejavamail.config.ConfigLoader;
import testutil.ConfigLoaderTestHelper;
import testutil.retry.RetryProviderTransport;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static jakarta.mail.Message.RecipientType.BCC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OverrideRecipientValidationTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuredValidatorRejectsTypedOverridesInBothPreparationModes(final boolean full) throws Exception {
        final Email email = message().withOverrideReceivers(recipient("outside@example.org")).buildEmail();
        try (Mailer mailer = offline().withValidationConfig(internalOnly(false, new ArrayList<>())).buildMailer()) {
            assertThatThrownBy(() -> mailer.validate(email, full)).isInstanceOf(MailInvalidAddressException.class)
                    .hasMessageContaining("delivery override").hasMessageContaining("outside@example.org");
            assertThatThrownBy(() -> mailer.rehearse(email, full)).isInstanceOf(MailInvalidAddressException.class);
        }
    }

    @Test
    void rejectingAnOverrideStopsBeforeTheProviderSubmissionBoundary() throws Exception {
        final RetryProviderTransport.State state = acceptedProvider();
        final Email email = message().withOverrideReceivers(recipient("outside@example.org")).buildEmail();
        try (Mailer mailer = provider(state).withValidationConfig(internalOnly(false, new ArrayList<>())).buildMailer()) {
            assertThatThrownBy(() -> mailer.sync().sendMail(email)).isInstanceOf(MailInvalidAddressException.class);
            assertThat(state.invocations).hasValue(0);
        }
    }

    @Test
    void warningModeStillInvokesTheConfiguredOverrideValidatorAndPermitsSubmission() throws Exception {
        final RetryProviderTransport.State state = acceptedProvider();
        final List<String> inspected = new ArrayList<>();
        final Email email = message().withOverrideReceivers(recipient("outside@example.org")).buildEmail();
        try (Mailer mailer = provider(state).withValidationConfig(internalOnly(true, inspected)).buildMailer()) {
            assertThat(mailer.rehearse(email).getEnvelopeRecipients()).containsExactly("outside@example.org");
            mailer.sync().sendMail(email);
            assertThat(inspected).contains("outside@example.org");
            assertThat(state.invocations).hasValue(1);
        }
    }

    @Test
    void visibleAddressesRemainValidatedWhenASeparateDeliveryOverrideExists() throws Exception {
        final Email email = message().clearRecipients().to("outside@example.org")
                .withOverrideReceivers(recipient("delivery@internal.test")).buildEmail();
        try (Mailer mailer = offline().withValidationConfig(internalOnly(false, new ArrayList<>())).buildMailer()) {
            assertThatThrownBy(() -> mailer.validate(email)).isInstanceOf(MailInvalidAddressException.class)
                    .hasMessageContaining("Invalid TO address");
        }
    }

    @Test
    void encodedWordAddressProtectionAlsoAppliesWhenTheConfigurableValidatorIsCleared() throws Exception {
        final Email email = message().withOverrideReceivers(recipient("=?UTF-8?B?ZGVsaXZlcnk=?=@internal.test")).buildEmail();
        try (Mailer mailer = offline().withValidationConfig(ValidationConfig.builder().clearEmailValidator().build()).buildMailer()) {
            assertThatThrownBy(() -> mailer.validate(email)).isInstanceOf(MailInvalidAddressException.class)
                    .hasMessageContaining("delivery override");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void overrideNameAndAddressStillReceiveInjectionScanningWithoutAConfiguredValidator(final boolean inName) throws Exception {
        final String injection = "\r\nX-Injected: yes";
        final Recipient override = new Recipient(inName ? "Display" + injection : null,
                inName ? "delivery@internal.test" : "delivery@internal.test" + injection, null, null);
        final Email email = message().withOverrideReceivers(override).buildEmail();
        try (Mailer mailer = offline().withValidationConfig(ValidationConfig.builder().clearEmailValidator().build()).buildMailer()) {
            assertThatThrownBy(() -> mailer.validate(email, false)).isInstanceOf(MailSuspiciousCRLFValueException.class)
                    .hasMessageContaining("overrideReceiver");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void copiedOverrideTemplatesAreValidatedAfterTheirGovernanceSelection(final boolean overrides) throws Exception {
        final Email template = mail.emailBuilder().startingBlank().withOverrideReceivers(recipient("outside@example.org")).buildEmail();
        final Email original = message().buildEmail();
        final Email copy = mail.emailBuilder().copying(original).withSubject("copy").buildEmail();
        final MailerRegularBuilder<?> builder = offline().withValidationConfig(internalOnly(false, new ArrayList<>()));
        if (overrides) { builder.withEmailOverrides(template); } else { builder.withEmailDefaults(template); }
        try (Mailer mailer = builder.buildMailer()) {
            assertThatThrownBy(() -> mailer.rehearse(copy)).isInstanceOf(MailInvalidAddressException.class)
                    .hasMessageContaining("outside@example.org");
        }
        assertThat(original.getOverrideReceivers()).isEmpty();
        assertThat(copy.getOverrideReceivers()).isEmpty();
    }

    @Test
    void validCopiedOverridesRetainOccurrenceOrderAndMetadataWithoutBeingReparsed() throws Exception {
        final Recipient first = new Recipient("First", "delivery-first@internal.test", BCC, null, Set.of(NotifyOption.FAILURE));
        final Recipient second = recipient("delivery-second@internal.test");
        final Email original = message().withOverrideReceivers(first, first, second).buildEmail();
        final Email copy = mail.emailBuilder().copying(original).withSubject("copy").buildEmail();
        final List<String> inspected = new ArrayList<>();
        try (Mailer mailer = offline().withValidationConfig(internalOnly(false, inspected)).buildMailer()) {
            final MailRehearsal rehearsal = mailer.rehearse(copy);
            assertThat(rehearsal.getEnvelopeRecipients()).containsExactly(first.getAddress(), first.getAddress(), second.getAddress());
            assertThat(rehearsal.getEffectiveEmail().getOverrideReceivers()).containsExactly(first, first, second);
            assertThat(inspected).filteredOn(address -> address.startsWith("delivery-"))
                    .containsExactly(first.getAddress(), first.getAddress(), second.getAddress());
        }
        assertThat(original.getOverrideReceivers()).containsExactly(first, first, second);
    }

    @ParameterizedTest
    @ValueSource(strings = {"To", "Cc", "Bcc"})
    void rawRecipientHeadersKeepTheirExpertEscapeFromMailboxValidation(final String header) throws Exception {
        final List<String> inspected = new ArrayList<>();
        final Email email = message().withHeader(header, "outside@example.org").buildEmail();
        try (Mailer mailer = offline().withValidationConfig(internalOnly(false, inspected)).buildMailer()) {
            assertThat(mailer.rehearse(email).getEnvelopeRecipients()).containsExactly("visible@internal.test", "outside@example.org");
            assertThat(inspected).doesNotContain("outside@example.org");
        }
    }

    @Test
    void rawRecipientHeadersStillReceiveGeneralInjectionScanning() throws Exception {
        final Email email = message().withHeader("Cc", "outside@example.org\r\nX-Injected: yes").buildEmail();
        try (Mailer mailer = offline().withValidationConfig(internalOnly(false, new ArrayList<>())).buildMailer()) {
            assertThatThrownBy(() -> mailer.rehearse(email)).isInstanceOf(MailSuspiciousCRLFValueException.class);
        }
    }

    @Test
    void rawRecipientHeadersStillCannotBypassMandatoryConfigurationLocks() throws Exception {
        final SimpleJavaMail locked = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap("archive policy", Map.of(
                "simplejavamail.locked.defaults.bcc.address", "archive@internal.test")).load());
        try (Mailer mailer = locked.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            assertThatThrownBy(() -> mailer.rehearse(message().withHeader("Bcc", "outside@example.org").buildEmail()))
                    .hasMessageContaining("locked.defaults.bcc.address");
        }
    }

    @Test
    void exactEmailKeepsItsStrictEnvelopeAndBypassesTheComposedAddressRule() throws Exception {
        final byte[] eml = ("From: sender@internal.test\r\nTo: visible@internal.test\r\n"
                + "Message-ID: <exact-override@internal.test>\r\nSubject: exact\r\n\r\nbody\r\n").getBytes(StandardCharsets.US_ASCII);
        final Email exact = mail.emailBuilder().startingFromExactEml(eml).withEnvelopeRecipients("outside@example.org").buildEmail();
        final List<String> inspected = new ArrayList<>();
        try (Mailer mailer = offline().withValidationConfig(internalOnly(false, inspected)).buildMailer()) {
            final MailRehearsal rehearsal = mailer.rehearse(exact);
            assertThat(rehearsal.getEmlBytes()).containsExactly(eml);
            assertThat(rehearsal.getEnvelopeRecipients()).containsExactly("outside@example.org");
            assertThat(inspected).isEmpty();
        }
    }

    private EmailPopulatingBuilder message() {
        return mail.emailBuilder().startingBlank().from("sender@internal.test").to("visible@internal.test").withPlainText("body");
    }

    private MailerRegularBuilder<?> offline() {
        return mail.mailerBuilder().withTransportModeLoggingOnly(true).withProperty("mail.host", "localhost");
    }

    private MailerFromSessionBuilder<?> provider(final RetryProviderTransport.State state) throws Exception {
        return mail.mailerBuilder(RetryProviderTransport.session(state))
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1).build());
    }

    private static ValidationConfig internalOnly(final boolean warningsOnly, final List<String> inspected) {
        return ValidationConfig.builder().withClientValidationWarningsOnly(warningsOnly).withEmailValidator(JMail.validator().withRule(address -> {
            inspected.add(address.normalized());
            return address.domain().equals("internal.test");
        })).build();
    }

    private static Recipient recipient(final String address) { return new Recipient(null, address, null, null); }

    private static RetryProviderTransport.State acceptedProvider() {
        final RetryProviderTransport.State state = new RetryProviderTransport.State();
        state.temporaryRefusals = 0;
        return state;
    }
}
