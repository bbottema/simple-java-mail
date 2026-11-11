package org.simplejavamail.email.internal;

import jakarta.activation.DataSource;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.ValueAvailability;
import org.simplejavamail.api.email.ValueInspection;
import org.simplejavamail.api.email.ValueOrigin;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.email.ReceivedEmailInspection;
import org.simplejavamail.internal.email.ValueInspectionImpl;
import org.simplejavamail.mailer.internal.EmailGovernanceImpl;
import testutil.ConfigLoaderTestHelper;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class GovernedSelectedInspectionTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @Test
    void unchangedFieldsRetainFactsWhileAnEqualValuedOverrideStillReplacesItsAssociation() {
        final Email received = MAIL.converter().fromEml("From: author@example.org\r\nTo: reader@example.org\r\nSubject: Known\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\nreceived body\r\n").toEmail();
        final Email unchanged = new EmailGovernanceImpl(null, null, null, null).produceEmailApplyingDefaultsAndOverrides(received);
        assertThat(unchanged.getInspection().getPlainText()).isSameAs(received.getInspection().getPlainText());
        assertThat(unchanged.getInspection().getToRecipients()).isSameAs(received.getInspection().getToRecipients());
        assertThat(unchanged.getSourceData()).isSameAs(received.getSourceData());
        final Email override = MAIL.emailBuilder().startingBlank().withPlainText(received.getPlainText()).buildEmail();
        final Email replaced = new EmailGovernanceImpl(null, null, override, null).produceEmailApplyingDefaultsAndOverrides(received);
        assertThat(replaced.getPlainText()).isEqualTo(received.getPlainText());
        assertThat(replaced.getInspection().getPlainText().getOrigin()).isEqualTo(ValueOrigin.CALLER_SUPPLIED);
        assertThat(replaced.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.UNKNOWN);
        assertThat(replaced.getInspection().getToRecipients()).isSameAs(received.getInspection().getToRecipients());
        assertThat(replaced.getInspection().getOriginalMessage()).isSameAs(received.getInspection().getOriginalMessage());
    }

    @Test
    void collectionResolutionPreservesDistinctSubmittedPositionsEvenForTheSameResourceInstance() {
        final DataSource borrowed = new DataSource() {
            @Override public InputStream getInputStream() { throw new AssertionError("Governance must not read selected content"); }
            @Override public OutputStream getOutputStream() { throw new AssertionError("Read only"); }
            @Override public String getContentType() { return "application/octet-stream"; }
            @Override public String getName() { return "same.bin"; }
        };
        final AttachmentResource same = AttachmentResource.builder().withName("same.bin").withDataSource(borrowed).build();
        final AttachmentResource originalA = AttachmentResource.builder().withName("first.p7m").withData(new byte[] {1}, "application/octet-stream").build();
        final AttachmentResource originalB = AttachmentResource.builder().withName("second.p7m").withData(new byte[] {2}, "application/octet-stream").build();
        final ValueInspection first = ValueInspectionImpl.builder().origin(ValueOrigin.RECEIVED).availability(ValueAvailability.PRESENT).build();
        final ValueInspection second = ValueInspectionImpl.builder().origin(ValueOrigin.EXTRACTED).availability(ValueAvailability.PRESENT).build();
        final InternalEmailPopulatingBuilder builder = (InternalEmailPopulatingBuilder) MAIL.emailBuilder().startingBlank();
        builder.withAttachments(List.of(same, same));
        // Model-isolated occurrence evidence; this fixture deliberately makes no signature/decryption claim.
        builder.retainSelectionInspection(ReceivedEmailInspection.builder().attachments(List.of(first, second))
                .originalAttachments(List.of(originalA, originalB)).build());
        final Email provided = builder.buildEmail();
        final Email defaults = MAIL.emailBuilder().startingBlank().withAttachment("extra.bin", new byte[] {3}, "application/octet-stream").buildEmail();
        final Email overrides = MAIL.emailBuilder().startingBlank().withAttachment(same).buildEmail();
        final Email selected = new EmailGovernanceImpl(null, defaults, overrides, null).produceEmailApplyingDefaultsAndOverrides(provided);
        assertThat(selected.getAttachments()).hasSize(3);
        assertThat(selected.getAttachments().get(0)).isSameAs(same);
        assertThat(selected.getAttachments().get(1)).isSameAs(same);
        assertThat(selected.getInspection().getAttachment(0)).isSameAs(first);
        assertThat(selected.getInspection().getAttachment(1)).isSameAs(second);
        assertThat(selected.getInspection().getOriginalAttachment(0)).isSameAs(originalA);
        assertThat(selected.getInspection().getOriginalAttachment(1)).isSameAs(originalB);
        assertThat(selected.getInspection().getOriginalAttachment(2)).isNull();
        assertThat(selected.getInspection().getAttachment(2).getOrigin()).isEqualTo(ValueOrigin.CALLER_SUPPLIED);
    }

    @Test
    void anUnrelatedLockDoesNotEraseUnchangedBodyOrRecipientEvidence() throws Exception {
        final Email received = MAIL.converter().fromEml("From: author@example.org\r\nTo: reader@example.org\r\nSubject: Known\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\nreceived body\r\n").toEmail();
        final SimpleJavaMail locked = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.locked.defaults.subject", "Known")).load());
        try (Mailer mailer = locked.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            final Email completed = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(received);
            assertThat(completed.getInspection().getPlainText()).isSameAs(received.getInspection().getPlainText());
            assertThat(completed.getInspection().getToRecipients()).isSameAs(received.getInspection().getToRecipients());
            assertThat(completed.getInspection().getSubject().getOrigin()).isEqualTo(ValueOrigin.CALLER_SUPPLIED);
        }
    }
}
