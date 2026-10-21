package org.simplejavamail.mailer.internal;

import jakarta.mail.Message.RecipientType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import testutil.ConfigLoaderTestHelper;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.mailer.internal.LockedEmailConfigurationTest.locked;

class RepeatableCompletionNameLockTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @CsvSource({"to,false", "cc,false", "bcc,false", "replyto,false", "to,true", "cc,true", "bcc,true", "replyto,true"})
    void lockedNamesDoNotMakeTheSameTemplateRecipientAppearNewOnEveryPass(final String field, final boolean override) {
        final Email template = withRecipients(field, List.of(recipient(null, field))).buildEmail();
        final EmailGovernanceImpl governance = governance(field, override ? null : template, override ? template : null);
        final Email once = governance.produceEmailApplyingDefaultsAndOverrides(null);
        final Email twice = governance.produceEmailApplyingDefaultsAndOverrides(once);
        final Email threeTimes = governance.produceEmailApplyingDefaultsAndOverrides(twice);

        assertThat(recipients(once, field)).containsExactly(recipient("Company", field));
        assertThat(recipients(twice, field)).containsExactlyElementsOf(recipients(once, field));
        assertThat(recipients(threeTimes, field)).containsExactlyElementsOf(recipients(once, field));
        assertThat(recipients(template, field).get(0).getName()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"to", "cc", "bcc"})
    void comparisonsUseTheLockedNameButPreserveSuppliedOccurrencesCertificatesAndNotificationPreferences(final String field) {
        final Recipient supplied = recipient("Company", field);
        final Recipient unnamed = recipient(null, field);
        final X509Certificate certificate = mock(X509Certificate.class);
        final Recipient certified = new Recipient(null, unnamed.getAddress(), unnamed.getType(), certificate);
        final Recipient notified = new Recipient(null, unnamed.getAddress(), unnamed.getType(), null, List.of(FAILURE));
        final Email input = withRecipients(field, List.of(supplied, supplied)).buildEmail();
        final Email defaults = withRecipients(field, List.of(unnamed, certified, notified)).buildEmail();
        final EmailGovernanceImpl governance = governance(field, defaults, null);

        final Email completed = governance.produceEmailApplyingDefaultsAndOverrides(input);
        final Email repeated = governance.produceEmailApplyingDefaultsAndOverrides(completed);

        assertThat(recipients(completed, field)).containsExactly(supplied, supplied,
                new Recipient("Company", unnamed.getAddress(), unnamed.getType(), certificate),
                new Recipient("Company", unnamed.getAddress(), unnamed.getType(), null, List.of(FAILURE)));
        assertThat(recipients(repeated, field)).containsExactlyElementsOf(recipients(completed, field));
        assertThat(recipients(input, field)).containsExactly(supplied, supplied);
    }

    @Test
    void replyToSuppliedDuplicatesSurviveNameCompletion() {
        final Recipient supplied = recipient("Company", "replyto");
        final Email input = withRecipients("replyto", List.of(supplied, supplied)).buildEmail();
        final Email defaults = withRecipients("replyto", List.of(recipient(null, "replyto"))).buildEmail();
        final EmailGovernanceImpl governance = governance("replyto", defaults, null);

        final Email completed = governance.produceEmailApplyingDefaultsAndOverrides(input);

        assertThat(completed.getReplyToRecipients()).containsExactly(supplied, supplied);
        assertThat(governance.produceEmailApplyingDefaultsAndOverrides(completed).getReplyToRecipients()).containsExactly(supplied, supplied);
    }

    @ParameterizedTest
    @ValueSource(strings = {"to", "cc", "bcc", "replyto"})
    void unnamedAndExplicitlyLockedTemplateValuesContributeOnlyOneEffectiveRecipient(final String field) {
        final Email defaults = withRecipients(field, List.of(recipient(null, field), recipient("Company", field))).buildEmail();
        final Email overrides = withRecipients(field, List.of(recipient(null, field), recipient("Company", field))).buildEmail();

        final Email completed = governance(field, defaults, overrides).produceEmailApplyingDefaultsAndOverrides(null);

        assertThat(recipients(completed, field)).containsExactly(recipient("Company", field));
    }

    @ParameterizedTest
    @CsvSource({"to,false", "cc,false", "bcc,false", "replyto,false", "to,true", "cc,true", "bcc,true", "replyto,true"})
    void explicitTemplateConflictsStillFailWhenOrdinaryTemplatesAreSuppressed(final String field, final boolean override) {
        final Email conflictingTemplate = withRecipients(field, List.of(recipient("Other", field))).buildEmail();
        final Email supplied = withRecipients(field, List.of(recipient("Company", field)))
                .withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().ignoringOverrides().build()).buildEmail();
        final EmailGovernanceImpl governance = governance(field, override ? null : conflictingTemplate, override ? conflictingTemplate : null);

        assertThatThrownBy(() -> governance.produceEmailApplyingDefaultsAndOverrides(supplied))
                .hasMessageContaining("locked.defaults." + field + ".name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"to", "cc", "bcc", "replyto"})
    void explicitSubmittedNameConflictsStillFail(final String field) {
        final Email template = withRecipients(field, List.of(recipient(null, field))).buildEmail();
        final Email supplied = withRecipients(field, List.of(recipient("Other", field))).buildEmail();

        assertThatThrownBy(() -> governance(field, template, null).produceEmailApplyingDefaultsAndOverrides(supplied))
                .hasMessageContaining("locked.defaults." + field + ".name");
    }

    @Test
    void visibleRecipientNameLocksDoNotChangeEnvelopeOverrideIdentity() {
        final Recipient unnamed = recipient(null, "to");
        final Recipient named = recipient("Company", "to");
        final Email template = mail.emailBuilder().startingBlank().withOverrideReceivers(unnamed, named).buildEmail();
        final EmailGovernanceImpl governance = governance("to", template, null);

        final Email completed = governance.produceEmailApplyingDefaultsAndOverrides(null);

        assertThat(completed.getOverrideReceivers()).containsExactly(unnamed, named);
        assertThat(governance.produceEmailApplyingDefaultsAndOverrides(completed).getOverrideReceivers()).containsExactly(unnamed, named);
    }

    @Test
    void traversedNameLocksAlsoParticipateInSelectedMembersRecipientComparison() throws Exception {
        final SimpleJavaMail lockedMail = locked(Map.of("defaults.to.name", "Company", "defaults.replyto.name", "Company"));
        final Email selectedTemplate = withRecipients("to", List.of(recipient(null, "to")))
                .withReplyTo("reply@example.org").buildEmail();
        try (MailerImpl initiator = (MailerImpl) lockedMail.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer();
                MailerImpl selected = (MailerImpl) mail.mailerBuilder().withTransportModeLoggingOnly(true)
                        .withEmailDefaults(selectedTemplate).buildMailer()) {
            final Email input = mail.emailBuilder().startingBlank().from("sender@example.org").withPlainText("body").buildEmail();
            final Email once = initiator.prepareSelectedEmail(input, selected, List.of(initiator));
            final Email twice = initiator.prepareSelectedEmail(once, selected, List.of(initiator));

            assertThat(once.getToRecipients()).containsExactly(recipient("Company", "to"));
            assertThat(twice.getToRecipients()).containsExactlyElementsOf(once.getToRecipients());
            assertThat(twice.getReplyToRecipients()).containsExactlyElementsOf(once.getReplyToRecipients());
        }
    }

    private EmailPopulatingBuilder withRecipients(final String field, final List<Recipient> recipients) {
        final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank();
        return field.equals("replyto") ? builder.withReplyTo(recipients) : builder.withRecipients(recipients);
    }

    private static List<Recipient> recipients(final Email email, final String field) {
        return field.equals("replyto") ? email.getReplyToRecipients() : email.getRecipients();
    }

    private static Recipient recipient(final String name, final String field) {
        return new Recipient(name, "a@example.org", field.equals("replyto") ? null
                : field.equals("to") ? RecipientType.TO : field.equals("cc") ? RecipientType.CC : RecipientType.BCC, null);
    }

    private static EmailGovernanceImpl governance(final String field, final Email defaults, final Email overrides) {
        final SimpleJavaMail factory = locked(Map.of("defaults." + field + ".name", "Company"));
        return new EmailGovernanceImpl(factory.getConfig(), factory.emailBuilder(), null, defaults, overrides, null);
    }
}
