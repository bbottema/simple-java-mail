package org.simplejavamail.email.internal;

import jakarta.activation.DataSource;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.ContentEncodingConfig;
import org.simplejavamail.api.mailer.Mailer;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.ContentTransferEncoding.BASE_64;
import static org.simplejavamail.api.email.ContentTransferEncoding.QUOTED_PRINTABLE;

class CopiedSmimeEditTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @Test
    void importedSignedMsgCopiesAcceptBodyReplacementAndClearing() {
        final Email received = mail.converter().fromOutlookMsg(Path.of(determineResourceFolder("simple-java-mail"),
                "test/resources/test-messages/SMIME (signed and clear text).msg")).toEmail();
        assertThat(received.getInspection().getSmime().getSignatures()).isNotEmpty();
        final String original = received.getPlainText();

        final Email replaced = mail.emailBuilder().copying(received).withPlainText("Replacement").withHTMLText("<p>Replacement</p>").buildEmail();
        final Email cleared = mail.emailBuilder().copying(received).clearPlainText().clearHTMLText().buildEmail();

        assertThat(replaced.getPlainText()).isEqualTo("Replacement");
        assertThat(replaced.getHTMLText()).isEqualTo("<p>Replacement</p>");
        assertThat(cleared.getPlainText()).isNull();
        assertThat(cleared.getHTMLText()).isNull();
        assertThat(replaced.getInspection().getSmime().getSignatures()).containsExactlyElementsOf(received.getInspection().getSmime().getSignatures());
        assertThat(replaced.getInspection().getOriginalMessage().readAllBytes()).containsExactly(received.getInspection().getOriginalMessage().readAllBytes());
        assertThat(received.getPlainText()).isEqualTo(original);
    }

    @Test
    void untouchedSignedMsgCopyKeepsVisibleHeadersUntilExplicitlyCleared() {
        final Email received = mail.converter().fromOutlookMsg(Path.of(determineResourceFolder("simple-java-mail"),
                "test/resources/test-messages/SMIME (signed and clear text).msg")).toEmail();
        assertThat(received.getHeaders()).isNotEmpty();
        final Email untouched = mail.emailBuilder().copying(received).buildEmail();
        final Email cleared = mail.emailBuilder().copying(received).clearHeaders().buildEmail();
        assertThat(untouched.getHeaders()).isEqualTo(received.getHeaders());
        assertThat(cleared.getHeaders()).doesNotContainKeys("X-Route", "Received", "Content-Type");
    }

    @Test
    void typedMessageIdEditsRemainAuthoritativeOverRetainedSmimeHeader() throws Exception {
        final Email received = mail.converter().fromOutlookMsg(Path.of(determineResourceFolder("simple-java-mail"),
                "test/resources/test-messages/SMIME (signed and clear text).msg")).toEmail();
        final Email fixed = mail.emailBuilder().copying(received).fixingMessageId("<edited@example.org>").buildEmail();
        assertThat(fixed.getId()).isEqualTo("<edited@example.org>");
        assertThat(mail.converter().fromEmail(fixed).toMimeMessage().getMessageID()).isEqualTo("<edited@example.org>");

        final Email cleared = mail.emailBuilder().copying(received).clearId().buildEmail();
        assertThat(cleared.getId()).isNull();
        final String generated = mail.converter().fromEmail(cleared).toMimeMessage().getMessageID();
        assertThat(generated).isNotBlank().isNotEqualTo(received.getId());
        assertThat(received.getHeaders().get("Message-ID")).doesNotContain(generated);

        final Email headersCleared = mail.emailBuilder().copying(received).clearHeaders().buildEmail();
        assertThat(headersCleared.getId()).isEqualTo(received.getId());
        assertThat(mail.converter().fromEmail(headersCleared).toMimeMessage().getMessageID()).isEqualTo(received.getId());
    }

    @Test
    void copyBuilderStartsWithTheCompleteVisibleContent() {
        final Email original = selected();
        final EmailPopulatingBuilder copy = mail.emailBuilder().copying(original);
        assertThat(copy.getAttachments()).containsExactlyElementsOf(original.getAttachments());
        assertThat(copy.getEmbeddedImages()).containsExactlyElementsOf(original.getEmbeddedImages());
        assertThat(copy.getHeaders()).isEqualTo(original.getHeaders());
        assertThat(copy.getPlainText()).isEqualTo(original.getPlainText());
    }

    @Test
    void copiedBodiesCanBeAugmentedAndTheirEncodingsReplacedOrCleared() {
        final Email original = selected();
        final Email edited = mail.emailBuilder().copying(original)
                .prependPlainText("before ").appendPlainText(" after")
                .prependHTMLText("<header/>").appendHTMLText("<footer/>")
                .withContentEncodingConfig(ContentEncodingConfig.builder()
                        .withPlainTextContentTransferEncoding(QUOTED_PRINTABLE).build()).buildEmail();
        assertThat(edited.getPlainText()).isEqualTo("before inner plain after");
        assertThat(edited.getHTMLText()).isEqualTo("<header/><p>inner</p><footer/>");
        assertThat(edited.getPlainTextContentTransferEncoding()).isEqualTo(QUOTED_PRINTABLE);
        assertThat(edited.getHTMLTextContentTransferEncoding()).isNull();
        assertThat(original.getPlainTextContentTransferEncoding()).isEqualTo(BASE_64);
    }

    @Test
    void explicitHeaderReplacementAndClearingAreNotOverwrittenByInnerHistory() {
        final Email original = selected();
        final Email replaced = mail.emailBuilder().copying(original).withHeader("x-route", "edited", true).buildEmail();
        final Email appended = mail.emailBuilder().copying(original).withHeader("x-route", "later").buildEmail();
        final Email cleared = mail.emailBuilder().copying(original).clearHeaders().buildEmail();
        assertThat(replaced.getHeaders().get("X-Route")).containsExactly("edited");
        assertThat(appended.getHeaders().get("X-Route")).containsExactly("inner", "later");
        assertThat(cleared.getHeaders()).doesNotContainKeys("X-Route", "Received", "Content-Type");
        assertThat(original.getHeaders().get("X-Route")).containsExactly("inner");
    }

    @Test
    void explicitResourceClearsAndReplacementsDoNotResurrectInnerOccurrences() {
        final Email original = selected();
        final AttachmentResource replacement = resource("replacement");
        final InternalEmailPopulatingBuilder copy = (InternalEmailPopulatingBuilder) mail.emailBuilder().copying(original);
        copy.clearAttachments().withAttachments(List.of(replacement));
        copy.clearEmbeddedImages().withEmbeddedImages(List.of(replacement, replacement));
        final Email edited = copy.buildEmail();
        assertThat(edited.getAttachments()).containsExactly(replacement);
        assertThat(edited.getEmbeddedImages()).containsExactly(replacement, replacement);
        assertThat(original.getAttachments()).hasSize(3);
    }

    @Test
    void resourceAdditionsFollowAllCopiedOccurrences() {
        final Email original = selected();
        final AttachmentResource extra = resource("extra");
        final Email edited = mail.emailBuilder().copying(original).withAttachments(List.of(extra)).buildEmail();
        assertThat(edited.getAttachments()).containsExactly(original.getAttachments().get(0), original.getAttachments().get(1),
                original.getAttachments().get(2), extra);
    }

    @Test
    void unchangedAndRepeatedCopiesKeepOccurrenceIdentityWithoutReadingBorrowedContent() {
        final DataSource unreadable = new DataSource() {
            public InputStream getInputStream() throws IOException { throw new IOException("Do not read borrowed content"); }
            public OutputStream getOutputStream() throws IOException { throw new IOException("Read only"); }
            public String getContentType() { return "text/plain"; }
            public String getName() { return "borrowed"; }
        };
        final AttachmentResource borrowed = AttachmentResource.builder().withDataSource(unreadable).withName("borrowed").build();
        Email current = outer(inner(borrowed), borrowed).buildEmail();
        for (int i = 0; i < 3; i++) {
            current = mail.emailBuilder().copying(current).buildEmail();
            assertThat(current.getAttachments()).containsExactly(borrowed, borrowed, borrowed);
            assertThat(current.getEmbeddedImages()).containsExactly(borrowed, borrowed, borrowed);
            assertThat(current.getPlainText()).isEqualTo("inner plain");
        }
    }

    @Test
    void copyingAnEditedBuilderDoesNotRestoreItsHistoricalContent() {
        final EmailPopulatingBuilder first = mail.emailBuilder().copying(selected()).withPlainText("Edited").clearAttachments();
        final Email second = mail.emailBuilder().copying(first).appendPlainText(" again").buildEmail();
        assertThat(second.getPlainText()).isEqualTo("Edited again");
        assertThat(second.getAttachments()).isEmpty();
    }

    @Test
    void copyingMaterializedSelectionKeepsItsVisibleContent() {
        final Email original = selected();
        final Email copy = mail.emailBuilder().copying(original).buildEmail();
        assertThat(copy.getPlainText()).isEqualTo(original.getPlainText());
        assertThat(copy.getAttachments()).containsExactlyElementsOf(original.getAttachments());
        assertThat(copy.getEmbeddedImages()).containsExactlyElementsOf(original.getEmbeddedImages());
    }

    @Test
    void readingMaterializedSelectionAndLaterEditsRemainIndependent() {
        final AttachmentResource resource = resource("resource");
        final Email original = outer(inner(resource), resource).buildEmail();
        final EmailPopulatingBuilder copy = mail.emailBuilder().copying(original);
        assertThat(copy.getPlainText()).isEqualTo("inner plain");
        assertThat(copy.getAttachments()).hasSize(3);
        assertThat(copy.buildEmail().getPlainTextContentTransferEncoding()).isEqualTo(BASE_64);
        assertThat(copy.buildEmail().getHTMLTextContentTransferEncoding()).isEqualTo(BASE_64);
        final Email edited = copy.withPlainText("After merge").clearAttachments().clearHeaders().buildEmail();
        assertThat(edited.getPlainText()).isEqualTo("After merge");
        assertThat(edited.getAttachments()).isEmpty();
        assertThat(edited.getHeaders()).isEmpty();
        assertThat(original.getPlainText()).isEqualTo("inner plain");
    }

    @Test
    void explicitlyClearedSelectedEncodingDoesNotAcquireHistoricalEncodingOnCopy() {
        final Email original = selected();
        final Email selected = mail.emailBuilder().copying(original)
                .withContentEncodingConfig(ContentEncodingConfig.builder().build()).buildEmail();
        final Email copied = mail.emailBuilder().copying(selected).buildEmail();
        assertThat(copied.getPlainTextContentTransferEncoding()).isNull();
        assertThat(copied.getHTMLTextContentTransferEncoding()).isNull();
        assertThat(original.getPlainTextContentTransferEncoding()).isEqualTo(BASE_64);
        assertThat(copied.getInspection().getOriginalMessage()).isNotNull();
    }

    @Test
    void repeatedCopiesDoNotLoseHistoryOrResurrectResources() {
        final Email edited = mail.emailBuilder().copying(selected())
                .clearAttachments().clearPlainText().buildEmail();
        final Email next = mail.emailBuilder().copying(edited).buildEmail();
        assertThat(next.getAttachments()).isEmpty();
        assertThat(next.getPlainText()).isNull();
    }

    @Test
    void repeatedExplicitClearsDoNotResurrectCopiedContent() {
        final Email edited = mail.emailBuilder().copying(selected()).clearAttachments().clearPlainText()
                .clearAttachments().clearPlainText().buildEmail();
        assertThat(edited.getPlainText()).isNull();
        assertThat(edited.getAttachments()).isEmpty();
    }

    @Test
    void serializedEditedCopiesKeepEditsAndHistoryWhenCopiedAgain() throws Exception {
        final Email edited = mail.emailBuilder().copying(selected()).withPlainText("Persisted").clearAttachments().buildEmail();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(edited); }
        final Email restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { restored = (Email) input.readObject(); }
        final Email copiedAgain = mail.emailBuilder().copying(restored).buildEmail();
        assertThat(copiedAgain.getPlainText()).isEqualTo("Persisted");
        assertThat(copiedAgain.getAttachments()).isEmpty();
        assertThat(copiedAgain.getInspection().getOriginalMessage().readAllBytes()).containsExactly(edited.getInspection().getOriginalMessage().readAllBytes());
    }

    @Test
    void normalGovernanceStillOverridesTheEditedCopy() throws Exception {
        final Email edited = mail.emailBuilder().copying(selected()).withPlainText("Edited").buildEmail();
        try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true)
                .withEmailOverrides(mail.emailBuilder().startingBlank().withPlainText("Policy").buildEmail()).buildMailer()) {
            assertThat(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(edited).getPlainText()).isEqualTo("Policy");
        }
    }

    @Test
    void exactCopyStillStartsOrdinaryEditableComposition() {
        final byte[] eml = ("From: sender@example.org\r\nTo: receiver@example.org\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\nOriginal\r\n").getBytes(UTF_8);
        final Email exact = mail.emailBuilder().startingFromExactEml(eml).withEnvelopeRecipients("receiver@example.org").buildEmail();
        final Email copy = mail.emailBuilder().copying(exact).withPlainText("Edited").buildEmail();
        assertThat(copy.getPlainText()).isEqualTo("Edited");
        assertThat(((InternalEmail) copy).isExactEml()).isFalse();
        assertThat(mail.converter().fromEmail(exact).toEmlBytes()).containsExactly(eml);
    }

    /** Model-isolated fixture: selected content is already materialized; it has no retained inner Email or crypto verdict. */
    private Email selected() {
        final AttachmentResource resource = resource("resource");
        return outer(inner(resource), resource).buildEmail();
    }

    private Email inner(final AttachmentResource resource) {
        final InternalEmailPopulatingBuilder inner = (InternalEmailPopulatingBuilder) mail.emailBuilder().startingBlank();
        inner.withPlainText("inner plain").withHTMLText("<p>inner</p>").withHeader("x-route", "inner")
                .withAttachments(List.of(resource)).withEmbeddedImages(List.of(resource))
                .withContentEncodingConfig(ContentEncodingConfig.builder().withPlainTextContentTransferEncoding(BASE_64)
                        .withHTMLTextContentTransferEncoding(BASE_64).build());
        return inner.buildEmail();
    }

    private InternalEmailPopulatingBuilder outer(final Email inner, final AttachmentResource resource) {
        final InternalEmailPopulatingBuilder outer = (InternalEmailPopulatingBuilder) mail.emailBuilder().startingBlank();
        outer.withPlainText(inner.getPlainText()).withHTMLText(inner.getHTMLText()).withHeader("X-Route", "inner")
                .withAttachments(List.of(resource, resource, resource)).withEmbeddedImages(List.of(resource, resource, resource))
                .withContentEncodingConfig(ContentEncodingConfig.builder().withPlainTextContentTransferEncoding(BASE_64)
                        .withHTMLTextContentTransferEncoding(BASE_64).build());
        outer.retainSelectionInspection(org.simplejavamail.internal.email.ReceivedEmailInspection.builder()
                .attachments(java.util.Collections.nCopies(3, org.simplejavamail.internal.email.ValueInspectionImpl.unknown()))
                .embeddedImages(java.util.Collections.nCopies(3, org.simplejavamail.internal.email.ValueInspectionImpl.unknown()))
                .originalAttachments(List.of(resource, resource, resource))
                .originalMessage(AttachmentResource.builder().withName("source.eml").withData(
                        "Content-Type: text/plain\r\n\r\nHistorical body\r\n".getBytes(UTF_8), "message/rfc822").build()).build());
        return outer;
    }

    private static AttachmentResource resource(final String name) {
        return AttachmentResource.builder().withName(name).withData(name.getBytes(UTF_8), "text/plain").build();
    }
}
