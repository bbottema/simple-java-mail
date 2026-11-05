package org.simplejavamail.email.internal;

import jakarta.activation.DataSource;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.EmailProperty.MAIL_TO_FORWARD;

class ClearForwardedEmailTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @Test
    void thePublicBuilderOffersANamedClearForTheForwardedMessage() {
        assertThat(EmailPopulatingBuilder.class.getMethods())
                .filteredOn(method -> method.getName().equals("clearEmailToForward"))
                .singleElement().satisfies(method -> {
                    assertThat(method.getParameterCount()).isZero();
                    assertThat(method.getReturnType()).isEqualTo(EmailPopulatingBuilder.class);
                });
    }

    @Test
    void copyingKeepsTheForwardUntilAnExplicitClearWithoutChangingBuiltValues() throws Exception {
        final MimeMessage original = source("Original");
        final Email forwarded = forward(original).buildEmail();
        final EmailPopulatingBuilder draft = mail.emailBuilder().copying(forwarded);
        final Email beforeClear = draft.buildEmail();

        assertThat(beforeClear.getEmailToForward()).isSameAs(original);
        assertThat(draft.clearEmailToForward()).isSameAs(draft);
        assertThat(draft.clearEmailToForward()).isSameAs(draft);
        assertThat(draft.getEmailToForward()).isNull();
        final Email cleared = draft.buildEmail();

        assertThat(cleared.getEmailToForward()).isNull();
        assertThat(mail.emailBuilder().copying(cleared).buildEmail().getEmailToForward()).isNull();
        assertThat(beforeClear.getEmailToForward()).isSameAs(original);
        assertThat(forwarded.getEmailToForward()).isSameAs(original);
        assertThat(original.getSubject()).isEqualTo("Original");
    }

    @Test
    void clearingOnlyTheForwardPreservesOtherChoicesAndDoesNotReadBorrowedContent() throws Exception {
        final CountingMessage original = new CountingMessage();
        original.setSubject("Original");
        original.setText("Original body");
        final DataSource file = new ByteArrayDataSource(new byte[] {1, 2}, "application/octet-stream");
        final DataSource image = new ByteArrayDataSource(new byte[] {3, 4}, "image/png");
        final DkimConfig dkim = DkimConfig.builder().dkimPrivateKeyData("opaque")
                .dkimSigningDomain("example.org").dkimSelector("draft").build();
        final EmailTemplateOptions options = EmailTemplateOptions.builder()
                .dontApplyDefaultValueFor(EmailProperty.SUBJECT).build();
        final Email forwarded = forward(original).withSubject("Keep this subject")
                .withPlainText("Keep plain").withHTMLText("<p>Keep HTML</p>")
                .withCalendarText(CalendarMethod.REQUEST, "BEGIN:VCALENDAR\r\nEND:VCALENDAR")
                .withAttachment("file.bin", file).withEmbeddedImage("image", image)
                .withHeader("X-Draft", "keep")
                .withOverrideReceivers(new Recipient("Test", "test@example.org", Message.RecipientType.TO, null))
                .withTemplateOptions(options)
                .fixingSentDate(new Date(12345)).signWithDomainKey(dkim).buildEmail();

        final EmailPopulatingBuilder draft = mail.emailBuilder().copying(forwarded);
        draft.clearEmailToForward();
        final Email cleared = draft.buildEmail();

        assertThat(cleared.getSubject()).isEqualTo(forwarded.getSubject());
        assertThat(cleared.getPlainText()).isEqualTo(forwarded.getPlainText());
        assertThat(cleared.getHTMLText()).isEqualTo(forwarded.getHTMLText());
        assertThat(cleared.getCalendarText()).isEqualTo(forwarded.getCalendarText());
        assertThat(cleared.getCalendarMethod()).isEqualTo(forwarded.getCalendarMethod());
        assertThat(cleared.getAttachments()).containsExactlyElementsOf(forwarded.getAttachments());
        assertThat(cleared.getEmbeddedImages()).containsExactlyElementsOf(forwarded.getEmbeddedImages());
        assertThat(cleared.getHeaders()).isEqualTo(forwarded.getHeaders());
        assertThat(cleared.getRecipients()).containsExactlyElementsOf(forwarded.getRecipients());
        assertThat(cleared.getOverrideReceivers()).containsExactlyElementsOf(forwarded.getOverrideReceivers());
        assertThat(cleared.getDkimConfig()).isSameAs(dkim);
        assertThat(cleared.getSentDate()).isEqualTo(forwarded.getSentDate());
        assertThat(draft.getTemplateOptions()).isEqualTo(options);
        assertThat(original.contentReads.get()).isZero();
        assertThat(original.serializations.get()).isZero();
    }

    @Test
    void renderingOmitsTheClearedForwardButKeepsAnOrdinaryNestedMessageAttachment() throws Exception {
        final Email forwarded = forward(source("Forwarded original"))
                .withAttachment("ordinary.eml", new ByteArrayDataSource(messageBytes("Ordinary attachment"), "message/rfc822"))
                .buildEmail();
        final Email cleared = mail.emailBuilder().copying(forwarded).clearEmailToForward().buildEmail();

        assertThat(nestedSubjects(render(forwarded))).containsExactlyInAnyOrder("Forwarded original", "Ordinary attachment");
        assertThat(nestedSubjects(render(cleared))).containsExactly("Ordinary attachment");
        assertThat(cleared.getAttachments()).hasSize(1);
    }

    @Test
    void clearingTheOnlyForwardLeavesTheOrdinaryBodyWithoutAnEmptyForwardContainer() throws Exception {
        final Email cleared = forward(source("Original")).clearEmailToForward().buildEmail();
        final MimeMessage rendered = render(cleared);

        assertThat(rendered.isMimeType("text/plain")).isTrue();
        assertThat(rendered.getContent().toString()).isEqualTo("Cover note");
        assertThat(nestedSubjects(rendered)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"false,false,override", "false,true,default", "true,false,override", "true,true,none"})
    void clearAndTemplateExclusionsRemainIndependent(final boolean suppressDefault, final boolean suppressOverride,
            final String expected) throws Exception {
        final MimeMessage supplied = source("Supplied");
        final MimeMessage defaultSource = source("Default");
        final MimeMessage overrideSource = source("Override");
        final Email forwarded = forward(supplied).buildEmail();
        final EmailPopulatingBuilder draft = mail.emailBuilder().copying(forwarded).clearEmailToForward();
        final EmailTemplateOptions.Builder options = draft.getTemplateOptions().toBuilder();
        if (suppressDefault) options.dontApplyDefaultValueFor(MAIL_TO_FORWARD);
        if (suppressOverride) options.dontApplyOverrideValueFor(MAIL_TO_FORWARD);
        draft.withTemplateOptions(options.build());

        try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true)
                .withEmailDefaults(forward(defaultSource).buildEmail())
                .withEmailOverrides(forward(overrideSource).buildEmail()).buildMailer()) {
            final Email prepared = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(draft.buildEmail());
            if (expected.equals("none")) assertThat(prepared.getEmailToForward()).isNull();
            else assertThat(prepared.getEmailToForward()).isSameAs(expected.equals("override") ? overrideSource : defaultSource);
        }
        assertThat(forwarded.getEmailToForward()).isSameAs(supplied);
    }

    @Test
    void aClearedForwardingTemplateKeepsItsOtherDefaults() throws Exception {
        final Email template = forward(source("Original")).withSubject("Template subject")
                .clearEmailToForward().buildEmail();
        final Email provided = mail.emailBuilder().startingBlank().buildEmail();
        try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true).withEmailDefaults(template).buildMailer()) {
            final Email prepared = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(provided);
            assertThat(prepared.getEmailToForward()).isNull();
            assertThat(prepared.getSubject()).isEqualTo("Template subject");
            assertThat(prepared.getPlainText()).isEqualTo("Cover note");
        }
    }

    @Test
    void clearingRetainsCapturedInputHistoryAndProblems() throws Exception {
        final Email received = mail.converter().fromEml("From: sender@example.org\r\nTo: reader@example.org\r\n"
                + "Date: not-a-date\r\nSubject: Received\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nBody").toEmail();
        assertThat(received.getProblems()).isNotEmpty();
        final EmailPopulatingBuilder seeded = mail.emailBuilder().copying(received);
        ((InternalEmailPopulatingBuilder) seeded).withForward(source("Original"));
        final Email before = seeded.buildEmail();

        final Email cleared = mail.emailBuilder().copying(before).clearEmailToForward().buildEmail();

        assertThat(cleared.getEmailToForward()).isNull();
        assertThat(cleared.getSourceData().getOrigin().getReference()).isEqualTo(before.getSourceData().getOrigin().getReference());
        assertThat(cleared.getSourceData().getHeaders()).containsExactlyElementsOf(before.getSourceData().getHeaders());
        assertThat(cleared.getProblems()).containsExactlyElementsOf(before.getProblems());
    }

    @Test
    void serializingAClearedDraftDoesNotReadItsFormerForwardedMessage() throws Exception {
        final CountingMessage original = new CountingMessage();
        original.setSubject("Original");
        original.setText("Original body");
        final Email cleared = forward(original).clearEmailToForward().buildEmail();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(cleared); }
        final Email restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (Email) input.readObject();
        }

        assertThat(restored.getEmailToForward()).isNull();
        assertThat(restored.getPlainText()).isEqualTo("Cover note");
        assertThat(original.contentReads.get()).isZero();
        assertThat(original.serializations.get()).isZero();
    }

    @Test
    void clearingDoesNotBypassAnUnrelatedMandatorySubject() throws Exception {
        final SimpleJavaMail locked = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(
                Map.of("simplejavamail.locked.defaults.subject", "Company mail")).load());
        final Email conflicting = forward(source("Original")).withSubject("Other subject").clearEmailToForward().buildEmail();
        final Email compliant = mail.emailBuilder().copying(conflicting).withSubject("Company mail").buildEmail();
        try (Mailer mailer = locked.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            assertThatThrownBy(() -> mailer.rehearse(conflicting)).hasMessageContaining("locked.defaults.subject");
            assertThat(mailer.rehearse(compliant).getEffectiveEmail().getSubject()).isEqualTo("Company mail");
        }
    }

    private EmailPopulatingBuilder forward(final MimeMessage original) {
        return mail.emailBuilder().forwarding(original).from("sender@example.org").to("recipient@example.org")
                .withPlainText("Cover note").fixingMessageId("<clear-forward@example.org>");
    }

    private MimeMessage render(final Email email) throws Exception {
        final MimeMessage prepared = mail.converter().fromEmail(email).toMimeMessage();
        final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        prepared.writeTo(wire);
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(wire.toByteArray()));
    }

    private static MimeMessage source(final String subject) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(messageBytes(subject)));
    }

    private static byte[] messageBytes(final String subject) {
        return ("From: original@example.org\r\nTo: recipient@example.org\r\nMessage-ID: <original@example.org>\r\n"
                + "Subject: " + subject + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nOriginal body\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> nestedSubjects(final Part part) throws Exception {
        final List<String> subjects = new ArrayList<>();
        if (part.isMimeType("message/rfc822")) {
            subjects.add(((MimeMessage) part.getContent()).getSubject());
        } else if (part.isMimeType("multipart/*")) {
            final Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                final BodyPart child = multipart.getBodyPart(i);
                subjects.addAll(nestedSubjects(child));
            }
        }
        return subjects;
    }

    private static final class CountingMessage extends MimeMessage {
        final AtomicInteger contentReads = new AtomicInteger();
        final AtomicInteger serializations = new AtomicInteger();
        CountingMessage() { super(Session.getInstance(new Properties())); }
        @Override public Object getContent() throws IOException, MessagingException {
            contentReads.incrementAndGet(); return super.getContent();
        }
        @Override public InputStream getInputStream() throws IOException, MessagingException {
            contentReads.incrementAndGet(); return super.getInputStream();
        }
        @Override public void writeTo(final OutputStream output) throws IOException, MessagingException {
            serializations.incrementAndGet(); super.writeTo(output);
        }
    }
}
