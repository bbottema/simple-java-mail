package org.simplejavamail.email.internal;

import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.internal.clisupport.CliEmailRecipientBuilder;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.mailer.internal.EmailGovernanceImpl;
import testutil.ConfigLoaderTestHelper;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.api.email.EmailProperty.BODY_TEXT;
import static org.simplejavamail.api.email.EmailProperty.SUBJECT;

class EmailErgonomicsTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @Test
    void independentHelpersAccumulateDefaultAndOverrideSuppressions() {
        final EmailPopulatingBuilder builder = blank()
                .withTemplateOptions(EmailTemplateOptions.builder().dontApplyDefaultValueFor(SUBJECT).dontApplyDefaultValueFor(BODY_TEXT).dontApplyOverrideValueFor(SUBJECT).dontApplyOverrideValueFor(BODY_TEXT).build());
        final Email original = builder.buildEmail();
        final EmailPopulatingBuilder copy = mail.emailBuilder().copying(original);
        final Email copied = copy.withTemplateOptions(copy.getTemplateOptions().toBuilder()
                .dontApplyDefaultValueFor().dontApplyOverrideValueFor().build()).buildEmail();

        assertThat(copied.getPropertiesNotToApplyDefaultValueFor()).containsExactlyInAnyOrder(SUBJECT, BODY_TEXT);
        assertThat(copied.getPropertiesNotToApplyOverrideValueFor()).containsExactlyInAnyOrder(SUBJECT, BODY_TEXT);
        assertThat(govern(copied).getSubject()).isNull();
        assertThat(govern(copied).getPlainText()).isNull();
        assertThat(original.getPropertiesNotToApplyDefaultValueFor()).containsExactlyInAnyOrder(SUBJECT, BODY_TEXT);
    }

    @Test
    void allowingOnePropertyLeavesOtherSuppressionsAndSnapshotsAlone() {
        final EmailPopulatingBuilder builder = blank()
                .withTemplateOptions(EmailTemplateOptions.builder().dontApplyDefaultValueFor(SUBJECT, BODY_TEXT).dontApplyOverrideValueFor(SUBJECT, BODY_TEXT).build());
        final Email original = builder.buildEmail();

        builder.withTemplateOptions(builder.getTemplateOptions().toBuilder().allowDefaultValueFor(SUBJECT).allowDefaultValueFor(SUBJECT).allowDefaultValueFor().build());
        assertThat(govern(builder.buildEmail()).getSubject()).isEqualTo("default");
        assertThat(govern(builder.buildEmail()).getPlainText()).isNull();
        builder.withTemplateOptions(builder.getTemplateOptions().toBuilder().allowOverrideValueFor(SUBJECT).allowOverrideValueFor(SUBJECT).allowOverrideValueFor().build());
        assertThat(govern(builder.buildEmail()).getSubject()).isEqualTo("override");
        assertThat(govern(builder.buildEmail()).getPlainText()).isNull();
        assertThat(govern(original).getSubject()).isNull();
        assertThat(original.getPropertiesNotToApplyOverrideValueFor()).containsExactlyInAnyOrder(SUBJECT, BODY_TEXT);
    }

    @Test
    void clearingSuppressionsDoesNotResetGlobalIgnoreFlags() {
        final EmailPopulatingBuilder builder = blank().withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().ignoringOverrides().dontApplyDefaultValueFor(SUBJECT).dontApplyOverrideValueFor(BODY_TEXT).clearDefaultValueSuppressions().clearOverrideValueSuppressions().build());

        assertThat(builder.getTemplateOptions().getPropertiesNotToApplyDefaultValueFor()).isEmpty();
        assertThat(builder.getTemplateOptions().getPropertiesNotToApplyOverrideValueFor()).isEmpty();
        assertThat(govern(builder.buildEmail()).getSubject()).isNull();
        assertThat(govern(builder.buildEmail()).getPlainText()).isNull();
        builder.withTemplateOptions(builder.getTemplateOptions().toBuilder().ignoringDefaults(false).ignoringOverrides(false).build());
        assertThat(govern(builder.buildEmail()).getSubject()).isEqualTo("override");
        assertThat(govern(builder.buildEmail()).getPlainText()).isEqualTo("override body");
    }

    @Test
    void editingLocalSuppressionsLeavesConfiguredLocksIntact() throws Exception {
        final SimpleJavaMail locked = SimpleJavaMail.withConfig(ConfigLoader.builder()
                .withMap(singletonMap("simplejavamail.locked.defaults.subject", "locked subject")).load());
        final Email email = blank().withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().ignoringOverrides().dontApplyDefaultValueFor(SUBJECT).dontApplyOverrideValueFor(SUBJECT).allowDefaultValueFor(SUBJECT).allowOverrideValueFor(SUBJECT).clearDefaultValueSuppressions().clearOverrideValueSuppressions().build()).buildEmail();

        try (Mailer mailer = locked.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            assertThat(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(email).getSubject()).isEqualTo("locked subject");
        }
    }

    @Test
    void emailRecipientShortcutsParseOneMailboxAndPreserveOccurrences() {
        final String rawAddress = "Alice <alice@example.org>";
        final String unsplit = "one@example.org;two@example.org";
        final Email email = blank().to(rawAddress).cc("Audit", "audit@example.org")
                .bcc("archive@example.org").to(rawAddress).to("Team", unsplit)
                .cc("cc@example.org").bcc("Archive", "archive@example.org").buildEmail();

        assertThat(email.getRecipients()).containsExactly(
                new Recipient("Alice", "alice@example.org", TO, null),
                new Recipient("Audit", "audit@example.org", CC, null),
                new Recipient(null, "archive@example.org", BCC, null),
                new Recipient("Alice", "alice@example.org", TO, null),
                new Recipient("Team", unsplit, TO, null),
                new Recipient(null, "cc@example.org", CC, null),
                new Recipient("Archive", "archive@example.org", BCC, null));
    }

    @Test
    void cliRecipientOptionsStillParseAddressListsIndependentlyOfJavaShortcuts() {
        final EmailPopulatingBuilder builder = blank();
        final CliEmailRecipientBuilder cli = (CliEmailRecipientBuilder) builder;
        cli.toAddresses(null, "Alice <alice@example.org>;bob@example.org")
                .ccAddresses("Audit", "audit@example.org,review@example.org")
                .bccAddresses(null, "archive@example.org;backup@example.org");

        assertThat(builder.buildEmail().getRecipients()).containsExactly(
                new Recipient("Alice", "alice@example.org", TO, null),
                new Recipient(null, "bob@example.org", TO, null),
                new Recipient("Audit", "audit@example.org", CC, null),
                new Recipient("Audit", "review@example.org", CC, null),
                new Recipient(null, "archive@example.org", BCC, null),
                new Recipient(null, "backup@example.org", BCC, null));
    }

    @Test
    void recipientShortcutsRejectMissingInputWithoutAddingRecipients() {
        final EmailPopulatingBuilder builder = blank();
        assertThatThrownBy(() -> builder.to("")).isInstanceOf(IllegalArgumentException.class).hasMessage("address is required");
        assertThatThrownBy(() -> builder.cc("Name", "")).isInstanceOf(IllegalArgumentException.class).hasMessage("address is required");
        assertThatThrownBy(() -> builder.bcc("Name", "")).isInstanceOf(IllegalArgumentException.class).hasMessage("address is required");
        assertThat(builder.getRecipients()).isEmpty();
    }

    @Test
    void smimeComponentsCanBeClearedIndependentlyAndInheritedAgain() {
        final SmimeSigningConfig signing = mock(SmimeSigningConfig.class);
        final SmimeEncryptionConfig encryption = mock(SmimeEncryptionConfig.class);
        final Email original = blank().signWithSmime(signing).encryptWithSmime(encryption).buildEmail();
        final EmailPopulatingBuilder builder = mail.emailBuilder().copying(original);

        final Email encryptionOnly = builder.clearSmimeSigning().buildEmail();
        assertThat(encryptionOnly.getSmimeSigningConfig()).isNull();
        assertThat(encryptionOnly.getSmimeEncryptionConfig()).isSameAs(encryption);
        final Email signingOnly = builder.signWithSmime(signing).clearSmimeEncryption().buildEmail();
        assertThat(signingOnly.getSmimeSigningConfig()).isSameAs(signing);
        assertThat(signingOnly.getSmimeEncryptionConfig()).isNull();
        final Email inherited = new EmailGovernanceImpl(null, original, null, null)
                .produceEmailApplyingDefaultsAndOverrides(builder.clearSmime().buildEmail());
        assertThat(inherited.getSmimeSigningConfig()).isSameAs(signing);
        assertThat(inherited.getSmimeEncryptionConfig()).isSameAs(encryption);
        assertThat(original.getSmimeEncryptionConfig()).isSameAs(encryption);
    }

    @Test
    void openPgpComponentsCanBeClearedIndependentlyAndInheritedAgain() {
        final OpenPgpSigningConfig signing = OpenPgpSigningConfig.builder().secretKeyRing(new byte[]{1}).build();
        final OpenPgpEncryptionConfig encryption = OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(new byte[]{2}).build();
        final Email original = blank().signWithOpenPgp(signing).encryptWithOpenPgp(encryption).buildEmail();
        final EmailPopulatingBuilder builder = mail.emailBuilder().copying(original);

        final Email encryptionOnly = builder.clearOpenPgpSigning().buildEmail();
        assertThat(encryptionOnly.getOpenPgpSigningConfig()).isNull();
        assertThat(encryptionOnly.getOpenPgpEncryptionConfig()).isSameAs(encryption);
        final Email signingOnly = builder.signWithOpenPgp(signing).clearOpenPgpEncryption().buildEmail();
        assertThat(signingOnly.getOpenPgpSigningConfig()).isSameAs(signing);
        assertThat(signingOnly.getOpenPgpEncryptionConfig()).isNull();
        final Email inherited = new EmailGovernanceImpl(null, original, null, null)
                .produceEmailApplyingDefaultsAndOverrides(builder.clearOpenPgp().buildEmail());
        assertThat(inherited.getOpenPgpSigningConfig()).isSameAs(signing);
        assertThat(inherited.getOpenPgpEncryptionConfig()).isSameAs(encryption);
        assertThat(original.getOpenPgpEncryptionConfig()).isSameAs(encryption);
    }

    private EmailPopulatingBuilder blank() {
        return mail.emailBuilder().startingBlank();
    }

    private Email govern(final Email email) {
        return new EmailGovernanceImpl(null,
                blank().withSubject("default").withPlainText("default body").buildEmail(),
                blank().withSubject("override").withPlainText("override body").buildEmail(), null)
                .produceEmailApplyingDefaultsAndOverrides(email);
    }
}
