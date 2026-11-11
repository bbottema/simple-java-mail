package org.simplejavamail.email.internal;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.mailer.internal.EmailGovernanceImpl;
import testutil.ConfigLoaderTestHelper;
import org.simplejavamail.config.ConfigLoader;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticHeaderOutputTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final String SOURCE = "Return-Path: <bounce@example.org>\r\nReceived: from relay.example by inbox.example\r\n"
            + "Authentication-Results: relay.example; dkim=pass\r\nReceived-SPF: pass\r\nDKIM-Signature: obsolete\r\nARC-Seal: obsolete\r\n"
            + "Resent-Date: Mon, 09 Nov 2026 10:00:00 +0000\r\nResent-From: old-resender@example.org\r\nResent-To: prior@example.org\r\n"
            + "Resent-Message-ID: <old-resend@example.org>\r\nContent-MD5: obsolete\r\nContent-ID: <old-body@example.org>\r\n"
            + "Content-Disposition: attachment; filename=old.txt\r\nStatus: RO\r\nSender: prior-agent@example.org\r\n"
            + "Comments: old comment\r\nKeywords: old keywords\r\nErrors-To: old-errors@example.org\r\n"
            + "Content-Location: https://example.test/report%0Aarchive.txt\r\n"
            + "From: author@example.org\r\nTo: reader@example.org\r\nSubject: Original\r\nMessage-ID: <original@example.org>\r\n"
            + "Date: Tue, 10 Nov 2026 09:00:00 +0000\r\nX-Ticket-ID: 42\r\nx-ticket-id: 43\r\n"
            + "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nbody\r\n";

    @Test
    void representedHistoryStaysReadableWhileReconstructionUsesCurrentAuthorities() throws Exception {
        final Email received = MAIL.converter().fromEml(SOURCE).toEmail();
        assertThat(received.getHeaders().get("received")).containsExactly("from relay.example by inbox.example");
        assertThat(received.getHeaders().get("Content-Location")).containsExactly("https://example.test/report%0Aarchive.txt");
        assertThat(received.getHeaders().get("X-TICKET-ID")).containsExactly("42", "43");
        assertThat(received.getBounceToRecipient().getAddress()).isEqualTo("bounce@example.org");
        assertThatThrownBy(() -> received.getHeaders().get("Received").clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> received.getHeaders().clear()).isInstanceOf(UnsupportedOperationException.class);

        final Email edited = MAIL.emailBuilder().copying(received).withHeader("Subject", "Generic edit")
                .withSubject("Current subject").clearRecipients().to("current@example.org")
                .fixingMessageId("<current@example.org>").fixingSentDate(new Date(1234567890000L)).buildEmail();
        assertThat(edited.getHeaders().get("SUBJECT")).containsExactly("Current subject");
        final MimeMessage output = MAIL.converter().fromEmail(edited).toMimeMessage();
        assertThat(output.getHeader("Subject")).containsExactly("Current subject");
        assertThat(output.getHeader("Message-ID")).containsExactly("<current@example.org>");
        assertThat(output.getHeader("To")).containsExactly("current@example.org");
        assertThat(output.getHeader("From")).containsExactly("author@example.org");
        assertThat(output.getHeader("Date")).hasSize(1);
        assertThat(output.getHeader("Content-Type")).hasSize(1);
        assertThat(output.getHeader("X-Ticket-ID")).containsExactly("42", "43");
        for (String name : new String[] {"Return-Path", "Received", "Authentication-Results", "Received-SPF", "DKIM-Signature", "ARC-Seal",
                "Resent-Date", "Resent-From", "Resent-To", "Resent-Message-ID", "Content-MD5", "Content-ID", "Content-Disposition",
                "Content-Location", "Status", "Sender", "Comments", "Keywords", "Errors-To"}) {
            assertThat(output.getHeader(name)).as(name).isNull();
        }
        assertThat(edited.getBounceToRecipient().getAddress()).isEqualTo("bounce@example.org");
        try (Mailer mailer = MAIL.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            assertThat(mailer.rehearse(edited).getEnvelopeSender()).isEqualTo("bounce@example.org");
        }
        assertThat(received.getHeaders().get("Subject")).containsExactly("Original");
    }

    @ParameterizedTest
    @ValueSource(strings = {"MIME-Version", "Content-Type", "Content-Transfer-Encoding", "Content-Length", "Return-Path",
            "Received", "Authentication-Results", "DKIM-Signature", "ARC-Message-Signature", "Content-MD5", "Content-Disposition",
            "Content-ID", "Content-Description", "Content-Location", "Resent-Date", "Resent-From", "Resent-Sender", "Resent-To", "Resent-Cc",
            "Resent-Bcc", "Resent-Message-ID", "Status", "Errors-To"})
    void explicitlySupplyingAnOutputOwnedHeaderFailsClearly(final String name) {
        final Email email = MAIL.emailBuilder().startingBlank().from("author@example.org").to("reader@example.org")
                .withPlainText("body").withHeader(name, "Return-Path".equals(name) ? "bounce@example.org" : "explicit value").buildEmail();
        assertThatThrownBy(() -> MAIL.converter().fromEmail(email).toEmlBytes()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(name).hasMessageContaining("owned");
    }

    @Test
    void explicitAdditionalAuthoredHeadersRemainAvailableWithoutRevivingImportedClaims() throws Exception {
        final Email received = MAIL.converter().fromEml(SOURCE).toEmail();
        final Email edited = MAIL.emailBuilder().copying(received).withHeader("Sender", "current-agent@example.org", true)
                .withHeader("Comments", "Current comment", true).withHeader("Keywords", "current", true).buildEmail();
        final MimeMessage output = MAIL.converter().fromEmail(edited).toMimeMessage();
        assertThat(output.getHeader("Sender")).containsExactly("current-agent@example.org");
        assertThat(output.getHeader("Comments")).containsExactly("Current comment");
        assertThat(output.getHeader("Keywords")).containsExactly("current");
        assertThat(received.getHeaders().get("Sender")).containsExactly("prior-agent@example.org");
    }

    @Test
    void onlyTheSelectedHeaderSourceSuppliesExplicitCompositionIntent() throws Exception {
        final Email received = MAIL.emailBuilder().copying(MAIL.converter().fromEml(SOURCE).toEmail())
                .withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults(false).build()).buildEmail();
        final Email template = MAIL.emailBuilder().startingBlank().withHeader("Received", "template current claim")
                .withHeader("Comments", "Template comment").buildEmail();
        final MimeMessage defaultLoses = MAIL.converter().fromEmail(received)
                .withEmailGovernance(new EmailGovernanceImpl(null, template, null, null)).toMimeMessage();
        assertThat(defaultLoses.getHeader("Received")).isNull();
        assertThat(defaultLoses.getHeader("Comments")).isNull();
        assertThatThrownBy(() -> MAIL.converter().fromEmail(received)
                .withEmailGovernance(new EmailGovernanceImpl(null, null, template, null)).toEmlBytes())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Received");
        final Email suppressed = MAIL.emailBuilder().copying(received).withTemplateOptions(EmailTemplateOptions.builder()
                .dontApplyOverrideValueFor(EmailProperty.HEADERS).build()).buildEmail();
        final MimeMessage ignoredOverride = MAIL.converter().fromEmail(suppressed)
                .withEmailGovernance(new EmailGovernanceImpl(null, null, template, null)).toMimeMessage();
        assertThat(ignoredOverride.getHeader("Received")).isNull();
        assertThat(ignoredOverride.getHeader("Comments")).isNull();
    }

    @Test
    void additionalHeaderResolutionCannotRestoreSuppressedTypedTemplateValues() throws Exception {
        final Email parsed = MAIL.converter().fromEml("From: author@example.org\r\nDate: not-a-date\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\nbody\r\n").toEmail();
        final Email provided = MAIL.emailBuilder().copying(parsed).withTemplateOptions(EmailTemplateOptions.builder()
                .dontApplyOverrideValueFor(EmailProperty.SUBJECT, EmailProperty.TO_RECIPIENTS).build()).buildEmail();
        final Email overrides = MAIL.emailBuilder().startingBlank().withSubject("Suppressed subject").to("suppressed@example.org")
                .withHeader("X-Policy", "selected custom header").buildEmail();
        final Email governed = new EmailGovernanceImpl(null, null, overrides, null).produceEmailApplyingDefaultsAndOverrides(provided);
        assertThat(governed.getSubject()).isNull();
        assertThat(governed.getToRecipients()).isEmpty();
        assertThat(governed.getHeaders()).doesNotContainKeys("Subject", "To");
        assertThat(governed.getHeaders().get("X-Policy")).containsExactly("selected custom header");
        assertThat(governed.getHeaders().get("Date")).containsExactly("not-a-date");
        final MimeMessage output = MAIL.converter().fromEmail(governed).toMimeMessage();
        assertThat(output.getHeader("Subject")).isNull();
        assertThat(output.getHeader("To")).isNull();
        assertThat(output.getHeader("X-Policy")).containsExactly("selected custom header");
        assertThat(output.getHeader("Date")).doesNotContain("not-a-date");
    }

    @Test
    void aParsedTemplateCanSupplyCustomValuesWithoutDonatingItsReceivedHistory() {
        final Email template = MAIL.converter().fromEml(SOURCE.replace("X-Ticket-ID: 42",
                "References: <old-template-ancestor@example.org>\r\nX-Ticket-ID: 42")).toEmail();
        final Email provided = MAIL.emailBuilder().startingBlank().from("writer@example.org").to("recipient@example.org")
                .withPlainText("Current body").buildEmail();
        final Email governed = new EmailGovernanceImpl(null, template, null, null).produceEmailApplyingDefaultsAndOverrides(provided);
        assertThat(governed.getHeaders()).doesNotContainKeys("Received", "Return-Path", "Authentication-Results", "Resent-To",
                "References", "Content-Type", "Content-Disposition");
        assertThat(governed.getHeaders().get("X-Ticket-ID")).containsExactly("42", "43");
        assertThat(governed.getBounceToRecipient().getAddress()).isEqualTo("bounce@example.org");
        assertThat(governed.getSourceData().getOrigin()).isNull();
    }

    @Test
    void canonicalAndGenericAuthoredValuesRespectTheSameLockedChoice() throws Exception {
        final SimpleJavaMail locked = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(java.util.Map.of(
                "simplejavamail.locked.defaults.subject", "Company mail")).load());
        final Email typed = MAIL.emailBuilder().startingBlank().from("author@example.org").to("reader@example.org")
                .withSubject("Company mail").withPlainText("body").buildEmail();
        final Email generic = MAIL.emailBuilder().copying(typed).withHeader("Subject", "Company mail", true).buildEmail();
        final Email conflicting = MAIL.emailBuilder().copying(typed).withHeader("Subject", "Other", true).buildEmail();
        try (Mailer mailer = locked.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            assertThat(mailer.rehearse(typed).getEffectiveEmail().getSubject()).isEqualTo("Company mail");
            assertThat(mailer.rehearse(generic).getEffectiveEmail().getSubject()).isEqualTo("Company mail");
            assertThatThrownBy(() -> mailer.rehearse(conflicting)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("locked.defaults.subject");
        }
    }

    @Test
    void standaloneCustomNamesDoNotOverrideMimeResourceParameters() throws Exception {
        final Email email = MAIL.emailBuilder().startingBlank().from("author@example.org").to("reader@example.org")
                .withPlainText("body").withHeader("name", "application metadata").withHeader("filename", "tracking only")
                .withHeader("size", "supplied metadata").withAttachment("actual.bin", new byte[] {1, 2}, "application/octet-stream").buildEmail();
        final MimeMessage output = MAIL.converter().fromEmail(email).toMimeMessage();
        assertThat(output.getHeader("name")).containsExactly("application metadata");
        assertThat(output.getHeader("filename")).containsExactly("tracking only");
        assertThat(output.getHeader("size")).containsExactly("supplied metadata");
        final Email read = MAIL.converter().fromEml(MAIL.converter().fromMimeMessage(output).toEmlBytes()).toEmail();
        assertThat(read.getAttachments()).singleElement().satisfies(resource -> assertThat(resource.getName()).isEqualTo("actual.bin"));
    }

    @Test
    void notificationClearAndGenericReplacementUseTheCurrentTypedChoice() throws Exception {
        final Email received = MAIL.converter().fromEml(SOURCE.replace("X-Ticket-ID: 42", "Disposition-Notification-To: old-read@example.org\r\n"
                + "Return-Receipt-To: old-receipt@example.org\r\nX-Ticket-ID: 42")).toEmail();
        final Email cleared = MAIL.emailBuilder().copying(received).clearDispositionNotificationTo().clearReturnReceiptTo().buildEmail();
        assertThat(cleared.getHeaders()).doesNotContainKeys("Disposition-Notification-To", "Return-Receipt-To");
        final MimeMessage withoutRequests = MAIL.converter().fromEmail(cleared).toMimeMessage();
        assertThat(withoutRequests.getHeader("Disposition-Notification-To")).isNull();
        assertThat(withoutRequests.getHeader("Return-Receipt-To")).isNull();
        final Email replaced = MAIL.emailBuilder().copying(received).withHeader("Disposition-Notification-To", "current-read@example.org", true)
                .withHeader("Return-Receipt-To", "current-receipt@example.org", true).buildEmail();
        assertThat(replaced.getDispositionNotificationToRecipients()).extracting(Recipient::getAddress).containsExactly("current-read@example.org");
        assertThat(replaced.getReturnReceiptToRecipients()).extracting(Recipient::getAddress).containsExactly("current-receipt@example.org");
        final MimeMessage current = MAIL.converter().fromEmail(replaced).toMimeMessage();
        assertThat(current.getHeader("Disposition-Notification-To")).containsExactly("current-read@example.org");
        assertThat(current.getHeader("Return-Receipt-To")).containsExactly("current-receipt@example.org");
        assertThat(received.getHeaders().get("Disposition-Notification-To")).containsExactly("old-read@example.org");
    }

    @Test
    void receiptGroupsSurviveAnUnchangedCopyAndCompletionWhileTypedOutputEmitsOnce() throws Exception {
        final Email received = MAIL.converter().fromEml("From: author@example.org\r\nTo: reader@example.org\r\n"
                + "Disposition-Notification-To: first-read@example.org\r\nDisposition-Notification-To: second-read@example.org\r\n"
                + "Return-Receipt-To: first-receipt@example.org\r\nReturn-Receipt-To: second-receipt@example.org\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\nbody\r\n").toEmail();
        final Email copied = MAIL.emailBuilder().copying(received).withHeader("Comments", "Explicit current comment").buildEmail();
        final Email completed = new EmailGovernanceImpl(null, null, null, null).produceEmailApplyingDefaultsAndOverrides(copied);
        for (Email current : new Email[] {received, copied, completed}) {
            assertThat(current.getHeaders().get("Disposition-Notification-To")).containsExactly("first-read@example.org", "second-read@example.org");
            assertThat(current.getHeaders().get("Return-Receipt-To")).containsExactly("first-receipt@example.org", "second-receipt@example.org");
        }
        final MimeMessage output = MAIL.converter().fromEmail(completed).toMimeMessage();
        assertThat(output.getHeader("Disposition-Notification-To")).containsExactly("first-read@example.org, second-read@example.org");
        assertThat(output.getHeader("Return-Receipt-To")).containsExactly("first-receipt@example.org, second-receipt@example.org");
        assertThat(output.getHeader("Comments")).containsExactly("Explicit current comment");

        final Email equalOverride = MAIL.emailBuilder().startingBlank().withDispositionNotificationTo(received.getDispositionNotificationToRecipients()).buildEmail();
        final Email replaced = new EmailGovernanceImpl(null, null, equalOverride, null).produceEmailApplyingDefaultsAndOverrides(received);
        assertThat(replaced.getHeaders().get("Disposition-Notification-To")).containsExactly("first-read@example.org, second-read@example.org");
        assertThat(replaced.getHeaders().get("Return-Receipt-To")).containsExactly("first-receipt@example.org", "second-receipt@example.org");
        final Email cleared = MAIL.emailBuilder().copying(completed).clearReturnReceiptTo().buildEmail();
        assertThat(cleared.getHeaders()).doesNotContainKey("Return-Receipt-To");
        assertThat(cleared.getHeaders().get("Disposition-Notification-To")).containsExactly("first-read@example.org", "second-read@example.org");

        final Email incompatible = MAIL.emailBuilder().copying(received).withHeader("Received", "explicit current claim").buildEmail();
        assertThatThrownBy(() -> MAIL.converter().fromEmail(incompatible).toEmlBytes())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Received");
    }

    @Test
    void uninterpretableReceivedNotificationRemainsReadableWithoutBecomingANewOutputRequest() throws Exception {
        final String raw = "=?x-never-a-real-charset?Q?undecoded?=";
        final Email received = MAIL.converter().fromEml(SOURCE.replace("X-Ticket-ID: 42", "Disposition-Notification-To: " + raw
                + "\r\nReturn-Receipt-To: " + raw + "\r\nX-Ticket-ID: 42")).toEmail();
        assertThat(received.getHeaders().get("Disposition-Notification-To")).containsExactly(raw);
        assertThat(received.getHeaders().get("Return-Receipt-To")).containsExactly(raw);
        final MimeMessage output = MAIL.converter().fromEmail(received).toMimeMessage();
        assertThat(output.getHeader("Disposition-Notification-To")).isNull();
        assertThat(output.getHeader("Return-Receipt-To")).isNull();
    }

    @Test
    void genericAuthoredEditsAndReplyAllUseTheSameTypedDestinationsWithoutDuplicates() {
        final Email email = MAIL.emailBuilder().startingBlank().from("author@example.org").to("first@example.org")
                .withHeader("To", "replacement@example.org", true).withHeader("Reply-To", "reply@example.org")
                .withHeader("Cc", "copy@example.org").withHeader("Subject", "Generic subject").withPlainText("body").buildEmail();
        assertThat(email.getRecipients()).extracting(Recipient::getAddress).containsExactly("replacement@example.org", "copy@example.org");
        final Email reply = MAIL.emailBuilder().replyingToAll(email).buildEmail();
        assertThat(reply.getRecipients()).extracting(Recipient::getAddress)
                .contains("replacement@example.org", "copy@example.org", "reply@example.org").doesNotHaveDuplicates();
    }

    @Test
    void aMissingParentIdDoesNotTurnAnAdapterGeneratedIdIntoReplyAncestry() {
        final Email parent = MAIL.converter().fromEml("From: author@example.org\r\nTo: reader@example.org\r\n"
                + "Subject: Without id\r\nContent-Type: text/plain\r\n\r\nbody\r\n").toEmail();
        assertThat(parent.getId()).isNull();
        final Email reply = MAIL.emailBuilder().replyingTo(parent).buildEmail();
        assertThat(reply.getHeaders()).doesNotContainKeys("In-Reply-To", "References");
    }

    @Test
    void aPresentEmptyParentIdDoesNotBecomeAGeneratedAdapterIdentity() {
        final Email parent = MAIL.converter().fromEml("From: author@example.org\r\nTo: reader@example.org\r\n"
                + "Message-ID: \r\nContent-Type: text/plain\r\n\r\nbody\r\n").toEmail();
        final Email reply = MAIL.emailBuilder().replyingTo(parent).buildEmail();
        assertThat(reply.getHeaders()).doesNotContainKeys("In-Reply-To", "References");
    }

    @Test
    void incompatibleExplicitDateAndFromChoicesLeaveTheirPreviousValuesIntact() {
        final org.simplejavamail.api.email.EmailPopulatingBuilder builder = MAIL.emailBuilder().startingBlank()
                .from("author@example.org").fixingSentDate(new Date(1234567890000L));
        for (String invalid : new String[] {null, "", "not-a-date", "31 Feb 2026 12:00:00 +0000"}) {
            assertThatThrownBy(() -> builder.withHeader("Date", invalid, true)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Date");
            assertThat(builder.getSentDate()).isEqualTo(new Date(1234567890000L));
        }
        assertThatThrownBy(() -> builder.withHeader("From", "one@example.org, two@example.org", true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(builder.getFromRecipient().getAddress()).isEqualTo("author@example.org");
    }

    @Test
    void nullAndEmptyAdditionalHeadersRemainDistinctStoredChoicesAndPresentEmptyOutput() throws Exception {
        final Email email = MAIL.emailBuilder().startingBlank().from("author@example.org").to("reader@example.org")
                .withHeader("X-Null", null).withHeader("X-Empty", "").withHeader("Subject", null).withPlainText("body").buildEmail();
        assertThat(email.getHeaders().get("X-Null")).containsExactly((String) null);
        assertThat(email.getHeaders().get("X-Empty")).containsExactly("");
        assertThat(email.getSubject()).isEmpty();
        final MimeMessage output = MAIL.converter().fromEmail(email).toMimeMessage();
        assertThat(output.getHeader("X-Null")).containsExactly("");
        assertThat(output.getHeader("X-Empty")).containsExactly("");
        assertThat(output.getHeader("Subject")).containsExactly("");
    }

    @Test
    void replyAncestryUsesTheSingleParentFallbackWithoutInventingALinearMultipleParentChain() {
        final Email single = MAIL.converter().fromEml(SOURCE.replace("X-Ticket-ID: 42", "In-Reply-To: <earlier@example.org>\r\nX-Ticket-ID: 42")).toEmail();
        final Email singleReply = MAIL.emailBuilder().replyingTo(single).buildEmail();
        assertThat(singleReply.getHeaders().get("In-Reply-To")).containsExactly("<original@example.org>");
        assertThat(singleReply.getHeaders().get("References")).containsExactly("<earlier@example.org> <original@example.org>");
        final Email multiple = MAIL.converter().fromEml(SOURCE.replace("X-Ticket-ID: 42", "In-Reply-To: <one@example.org> <two@example.org>\r\nX-Ticket-ID: 42")).toEmail();
        final Email multipleReply = MAIL.emailBuilder().replyingTo(multiple).buildEmail();
        assertThat(multipleReply.getHeaders().get("References")).containsExactly("<original@example.org>");
    }

    @Test
    void repeatedInReplyToOccurrencesDoNotBecomeALinearAncestryChain() {
        final Email parent = MAIL.converter().fromEml(SOURCE.replace("X-Ticket-ID: 42",
                "In-Reply-To: <one@example.org>\r\nIn-Reply-To: <two@example.org>\r\nX-Ticket-ID: 42")).toEmail();
        final Email reply = MAIL.emailBuilder().replyingTo(parent).buildEmail();
        assertThat(reply.getHeaders().get("References")).containsExactly("<original@example.org>");
    }

    @Test
    void aSingleFallbackIdentifierCanSupplyAncestryEvenWhenTheParentIdIsAbsent() {
        final Email parent = MAIL.converter().fromEml(SOURCE.replace("Message-ID: <original@example.org>\r\n",
                "In-Reply-To: <earlier@example.org>\r\n")).toEmail();
        final Email reply = MAIL.emailBuilder().replyingTo(parent).buildEmail();
        assertThat(reply.getHeaders()).doesNotContainKey("In-Reply-To");
        assertThat(reply.getHeaders().get("References")).containsExactly("<earlier@example.org>");
    }
}
