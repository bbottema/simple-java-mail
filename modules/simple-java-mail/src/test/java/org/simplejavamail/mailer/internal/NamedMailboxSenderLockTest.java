package org.simplejavamail.mailer.internal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.mailer.MailRehearsal;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.mailer.MailInvalidAddressException;

import java.util.Map;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NamedMailboxSenderLockTest {

    private static final String ADDRESS = "accounts@company.com";
    private static final String NAMED_MAILBOX = "Accounts <" + ADDRESS + ">";
    private final SimpleJavaMail ordinary = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @ParameterizedTest
    @EnumSource(SenderField.class)
    void namedLockedDefaultsAreCompletedAndCanBeCompletedAgain(final SenderField field) {
        final SimpleJavaMail factory = factory(field, NAMED_MAILBOX);
        final Email completed = message(factory).buildEmailCompletedWithDefaultsAndOverrides();

        assertMailbox(field.recipient(completed), "Accounts", ADDRESS);
        final Email repeated = factory.emailBuilder().copying(completed).buildEmailCompletedWithDefaultsAndOverrides();
        assertMailbox(field.recipient(repeated), "Accounts", ADDRESS);
    }

    @ParameterizedTest
    @EnumSource(SenderField.class)
    void equivalentMailboxSyntaxAndDifferentNamesRetainTheSuppliedName(final SenderField field) {
        final SimpleJavaMail factory = factory(field, "  Accounts <" + ADDRESS + "> (Billing)  ");
        final Email completed = field.set(message(factory), "Caller name <" + ADDRESS + ">")
                .buildEmailCompletedWithDefaultsAndOverrides();

        assertMailbox(field.recipient(completed), "Caller name", ADDRESS);
    }

    @ParameterizedTest
    @EnumSource(SenderField.class)
    void suppressedAndReplacedDefaultsStillApplyOnlyTheLockedAddress(final SenderField field) throws Exception {
        final SimpleJavaMail factory = factory(field, NAMED_MAILBOX);
        final Email suppressed = message(factory)
                .withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().ignoringOverrides().build())
                .buildEmailCompletedWithDefaultsAndOverrides();
        assertMailbox(field.recipient(suppressed), null, ADDRESS);

        final Email emptyTemplate = ordinary.emailBuilder().startingBlank().buildEmail();
        try (Mailer mailer = factory.mailerBuilder().withTransportModeLoggingOnly(true)
                .withEmailDefaults(emptyTemplate).withEmailOverrides(emptyTemplate).buildMailer()) {
            final Email completed = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(message(ordinary).buildEmail());
            assertMailbox(field.recipient(completed), null, ADDRESS);
        }
    }

    @ParameterizedTest
    @EnumSource(SenderField.class)
    void bareAddressLocksKeepTheirExistingBehavior(final SenderField field) {
        final SimpleJavaMail factory = factory(field, ADDRESS);
        final Email completed = field.set(message(factory), "Caller name <" + ADDRESS + ">")
                .buildEmailCompletedWithDefaultsAndOverrides();
        assertMailbox(field.recipient(completed), "Caller name", ADDRESS);
    }

    @ParameterizedTest
    @EnumSource(SenderField.class)
    void aSeparateNameLockIsAppliedAndStillRejectsAConflictingName(final SenderField field) {
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(config(field, NAMED_MAILBOX, "Company"));
        final Email completed = message(factory).buildEmailCompletedWithDefaultsAndOverrides();
        assertMailbox(field.recipient(completed), "Company", ADDRESS);

        final Email missingName = field.set(message(factory), ADDRESS)
                .withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().build())
                .buildEmailCompletedWithDefaultsAndOverrides();
        assertMailbox(field.recipient(missingName), "Company", ADDRESS);

        assertThatThrownBy(() -> field.set(message(factory), "Other <" + ADDRESS + ">")
                .buildEmailCompletedWithDefaultsAndOverrides()).hasMessageContaining(field.lockedNameKey());
    }

    @ParameterizedTest
    @EnumSource(SenderField.class)
    void differentAddressesAndAddressCaseRemainConflicts(final SenderField field) {
        final SimpleJavaMail factory = factory(field, NAMED_MAILBOX);
        for (String otherAddress : new String[]{"other@company.com", "Accounts@company.com", "accounts@Company.com"}) {
            assertThatThrownBy(() -> field.set(message(factory), "Accounts <" + otherAddress + ">")
                    .buildEmailCompletedWithDefaultsAndOverrides()).hasMessageContaining(field.lockedAddressKey());
        }
    }

    @ParameterizedTest
    @EnumSource(SenderField.class)
    void malformedSourcesRemainAvailableToTheSendTimeValidator(final SenderField field) throws Exception {
        final String malformed = "broken <address";
        final SimpleJavaMail factory = factory(field, malformed);
        final Email completed = message(factory).buildEmailCompletedWithDefaultsAndOverrides();
        assertThat(field.recipient(completed).getAddress()).isEqualTo(malformed);
        try (Mailer mailer = factory.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            assertThatThrownBy(() -> mailer.validate(completed)).isInstanceOf(MailInvalidAddressException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(SenderField.class)
    void pluralSourcesStillCannotBecomeSingularSenderDefaults(final SenderField field) {
        for (String plural : new String[]{"first@company.com,second@company.com", "Team: accounts@company.com;"}) {
            final SimpleJavaMail factory = factory(field, plural);
            assertThatThrownBy(() -> message(factory).buildEmailCompletedWithDefaultsAndOverrides())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must contain one mailbox");
        }
    }

    @ParameterizedTest
    @EnumSource(SenderField.class)
    void exactMessagesKeepProtectedBytesWithCompatibleNamedAddressLocks(final SenderField field) throws Exception {
        final byte[] bytes = exactBytes("Different header name <" + ADDRESS + ">");
        final ExactEmailBuilder builder = ordinary.emailBuilder().startingFromExactEml(bytes)
                .withEnvelopeRecipients("recipient@example.org");
        if (field == SenderField.BOUNCE) {
            builder.withEnvelopeSender("Different envelope name <" + ADDRESS + ">");
        }
        final Email original = builder.buildEmail();
        try (Mailer mailer = factory(field, NAMED_MAILBOX).mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            final MailRehearsal rehearsal = mailer.rehearse(original);
            assertThat(rehearsal.getEmlBytes()).containsExactly(bytes);
            assertMailbox(field.recipient(rehearsal.getEffectiveEmail()),
                    field == SenderField.FROM ? "Different header name" : "Different envelope name", ADDRESS);
            assertThat(rehearsal.getEnvelopeSender()).isEqualTo(field == SenderField.BOUNCE ? ADDRESS : null);
            if (field == SenderField.BOUNCE) {
                final Email conflict = ordinary.emailBuilder().startingFromExactEml(bytes)
                        .withEnvelopeRecipients("recipient@example.org").withEnvelopeSender("other@company.com").buildEmail();
                assertThatThrownBy(() -> mailer.rehearse(conflict)).hasMessageContaining(field.lockedAddressKey());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(SenderField.class)
    void exactMessagesCanReceiveALockedBounceButCannotRewriteAConflictingFrom(final SenderField field) throws Exception {
        final byte[] bytes = exactBytes("Other <other@company.com>");
        final Email original = ordinary.emailBuilder().startingFromExactEml(bytes)
                .withEnvelopeRecipients("recipient@example.org").buildEmail();
        try (Mailer mailer = factory(field, NAMED_MAILBOX).mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            if (field == SenderField.FROM) {
                assertThatThrownBy(() -> mailer.rehearse(original)).hasMessageContaining(field.lockedAddressKey());
            } else {
                final MailRehearsal rehearsal = mailer.rehearse(original);
                assertThat(rehearsal.getEmlBytes()).containsExactly(bytes);
                assertThat(rehearsal.getEnvelopeSender()).isEqualTo(ADDRESS);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(SenderField.class)
    void exactMessagesStillEnforceSeparatelyLockedNames(final SenderField field) throws Exception {
        final byte[] bytes = exactBytes("Other <" + ADDRESS + ">");
        final ExactEmailBuilder builder = ordinary.emailBuilder().startingFromExactEml(bytes)
                .withEnvelopeRecipients("recipient@example.org");
        if (field == SenderField.BOUNCE) {
            builder.withEnvelopeSender("Other <" + ADDRESS + ">");
        }
        final Email original = builder.buildEmail();
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(config(field, NAMED_MAILBOX, "Company"));
        try (Mailer mailer = factory.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            assertThatThrownBy(() -> mailer.rehearse(original)).hasMessageContaining(field.lockedNameKey());
            final byte[] matchingBytes = exactBytes("Company <" + ADDRESS + ">");
            final ExactEmailBuilder matching = ordinary.emailBuilder().startingFromExactEml(matchingBytes)
                    .withEnvelopeRecipients("recipient@example.org");
            if (field == SenderField.BOUNCE) {
                matching.withEnvelopeSender("Company <" + ADDRESS + ">");
            }
            assertThat(mailer.rehearse(matching.buildEmail()).getEmlBytes()).containsExactly(matchingBytes);
        }
    }

    @ParameterizedTest
    @EnumSource(SenderField.class)
    void routeCompatibilityUsesTheInterpretedAddressWithoutLockingAnEmbeddedName(final SenderField field) {
        final SimpleJavaMailConfig first = config(field, NAMED_MAILBOX, null);
        final SimpleJavaMailConfig equivalent = config(field, "Other <" + ADDRESS + "> (Billing)", null);
        assertThatCode(() -> LockedEmailConfiguration.verifyCompatibleLocks(first, equivalent)).doesNotThrowAnyException();
        assertThatCode(() -> LockedEmailConfiguration.verifyCompatibleLocks(first, config(field, ADDRESS, null)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> LockedEmailConfiguration.verifyCompatibleLocks(first, config(field, "other@company.com", null)))
                .hasMessageContaining(field.lockedAddressKey());
        assertThatThrownBy(() -> LockedEmailConfiguration.verifyCompatibleLocks(
                config(field, NAMED_MAILBOX, "Company"), config(field, ADDRESS, "Other")))
                .hasMessageContaining(field.lockedNameKey());
    }

    private EmailPopulatingBuilder message(final SimpleJavaMail factory) {
        return factory.emailBuilder().startingBlank().to("recipient@example.org").withPlainText("body");
    }

    private SimpleJavaMail factory(final SenderField field, final String mailbox) {
        return SimpleJavaMail.withConfig(config(field, mailbox, null));
    }

    private SimpleJavaMailConfig config(final SenderField field, final String mailbox, final String name) {
        final Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put(field.lockedAddressKey(), mailbox);
        if (name != null) {
            values.put(field.lockedNameKey(), name);
        }
        // A normal From is needed for bounce-only validation and rehearsal controls.
        if (field == SenderField.BOUNCE) {
            values.put("simplejavamail.defaults.from.address", ADDRESS);
        }
        return ConfigLoader.builder().withMap(values).load();
    }

    private static byte[] exactBytes(final String from) {
        return ("From: " + from + "\r\nTo: recipient@example.org\r\nSubject: Exact\r\n"
                + "DKIM-Signature: v=1; b=unchanged\r\n\r\nbody\r\n").getBytes(US_ASCII);
    }

    private static void assertMailbox(final Recipient recipient, final String name, final String address) {
        assertThat(recipient).isNotNull();
        assertThat(recipient.getName()).isEqualTo(name);
        assertThat(recipient.getAddress()).isEqualTo(address);
    }

    enum SenderField {
        FROM("from") {
            @Override EmailPopulatingBuilder set(final EmailPopulatingBuilder builder, final String mailbox) { return builder.from(mailbox); }
            @Override Recipient recipient(final Email email) { return email.getFromRecipient(); }
        },
        BOUNCE("bounceto") {
            @Override EmailPopulatingBuilder set(final EmailPopulatingBuilder builder, final String mailbox) { return builder.withBounceTo(mailbox); }
            @Override Recipient recipient(final Email email) { return email.getBounceToRecipient(); }
        };

        private final String propertyTail;
        SenderField(final String propertyTail) { this.propertyTail = propertyTail; }
        String lockedAddressKey() { return "simplejavamail.locked.defaults." + propertyTail + ".address"; }
        String lockedNameKey() { return "simplejavamail.locked.defaults." + propertyTail + ".name"; }
        abstract EmailPopulatingBuilder set(EmailPopulatingBuilder builder, String mailbox);
        abstract Recipient recipient(Email email);
    }
}
