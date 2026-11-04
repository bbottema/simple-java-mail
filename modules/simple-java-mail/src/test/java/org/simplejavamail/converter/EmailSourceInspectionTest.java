package org.simplejavamail.converter;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.*;
import org.simplejavamail.api.mailer.Mailer;
import testutil.ConfigLoaderTestHelper;

import java.io.*;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

class EmailSourceInspectionTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final String BAD = "=?X-NO-SUCH-CHARSET?Q?old_note?=";
    private static final String HEADERS = "Message-ID: <original@example.org>\r\nFrom: sender@example.org\r\nTo: reader@example.org\r\n";
    enum Route { STATIC_EML, FLUENT_EML, STATIC_MIME, FLUENT_MIME, EXACT }

    @ParameterizedTest @EnumSource(Route.class)
    void capturesEveryOccurrenceWithoutReplayingStructuralHeaders(Route route) throws Exception {
        Email email = convert(route, message("Subject: readable\r\nReceived: first\r\nComments: note\r\nreceived: second\r\nKeywords: one\r\n"));
        EmailSourceData source = email.getSourceData();
        assertThat(headerValues(source, "RECEIVED")).containsExactly("first", "second");
        assertThat(source.getFormat()).isEqualTo(EmailSourceData.Format.MIME);
        assertThat(source.getRepresentation()).isEqualTo(EmailSourceData.Representation.RECEIVED);
        assertThat(source.getOrigin().getMessageId()).isEqualTo("<original@example.org>");
        assertThat(source.getHeaders()).extracting(EmailSourceHeader::getName)
                .containsSubsequence("Received", "Comments", "received", "Keywords");
        assertThat(headerValues(source, "Comments")).containsExactly("note");
        assertThat(headerValues(source, "No-Such-Field")).isEmpty();
        assertThat(email.getHeaders().keySet()).noneMatch(name -> name.equalsIgnoreCase("Received") || name.equalsIgnoreCase("Comments"));
        assertThat(email.getPlainText()).isEqualTo("Readable body\r\n");
        if (route != Route.EXACT) {
            MimeMessage rendered = MAIL.converter().fromEmail(email).toMimeMessage();
            assertThat(rendered.getHeader("Received")).isNull();
            assertThat(rendered.getHeader("Comments")).isNull();
        }
    }

    @ParameterizedTest @EnumSource(Route.class)
    void anUndecodableDiscardedFieldDoesNotGateInspection(Route route) throws Exception {
        Email email = convert(route, message("Subject: readable\r\nComments: " + BAD + "\r\n"));
        assertThat(email.getPlainText()).isEqualTo("Readable body\r\n");
        assertThat(headerValues(email.getSourceData(), "Comments")).containsExactly(BAD);
        assertThat(email.getProblems()).isEmpty();
    }

    @ParameterizedTest @EnumSource(Route.class)
    void retainsCompleteUndecodableSubjectAndItsOrigin(Route route) throws Exception {
        String subject = "=?UTF-8?Q?Known?= " + BAD;
        Email email = convert(route, message("Subject: " + subject + "\r\n"));
        assertThat(email.getSubject()).isEqualTo(subject);
        assertThat(email.getPlainText()).isEqualTo("Readable body\r\n");
        assertThat(headerValues(email.getSourceData(), "Subject")).containsExactly(subject);
        assertThat(email.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.VALUE_NOT_DECODED);
            assertThat(problem.getOperation()).isEqualTo(EmailProblem.Operation.IMPORT);
            assertThat(problem.getLocation()).isEqualTo("Subject");
            assertThat(problem.getSourceValue()).isEqualTo(subject);
            assertThat(problem.getOrigin().getMessageId()).isEqualTo("<original@example.org>");
            assertThat(problem.getOrigin().getReference()).isNotNull();
            assertThat(problem.getSources()).singleElement().isSameAs(firstHeader(email.getSourceData(), "Subject"));
            assertThat(problem.getOrigin()).isSameAs(email.getSourceData().getOrigin());
        });
    }

    @Test void repeatedCopiesAndSerializationKeepTheFirstOriginDespiteNewIds() throws Exception {
        Email original = convert(Route.FLUENT_EML, message("Subject: " + BAD + "\r\n"));
        UUID reference = original.getProblems().get(0).getOrigin().getReference();
        Email current = original;
        for (int i = 0; i < 3; i++) {
            current = roundTrip(MAIL.emailBuilder().copying(current).withSubject("corrected")
                    .fixingMessageId("<copy" + i + "@example.org>").buildEmail());
            assertThat(current.getProblems()).singleElement().satisfies(problem -> {
                assertThat(problem.getOrigin().getMessageId()).isEqualTo("<original@example.org>");
                assertThat(problem.getOrigin().getReference()).isEqualTo(reference);
                assertThat(problem.getOperation()).isEqualTo(EmailProblem.Operation.IMPORT);
            });
            assertThat(headerValues(current.getSourceData(), "Subject")).containsExactly(BAD);
            assertThat(current.getProblems().get(0).getSources()).singleElement()
                    .isSameAs(firstHeader(current.getSourceData(), "Subject"));
            assertThat(current.getSubject()).isEqualTo("corrected");
        }
    }

    @Test void missingAndRepeatedIdsDoNotInventOrConflateAnOrigin() throws Exception {
        String missing = message("Subject: " + BAD + "\r\n").replace("Message-ID: <original@example.org>\r\n", "");
        Email first = convert(Route.FLUENT_EML, missing);
        Email second = convert(Route.FLUENT_EML, missing);
        assertThat(first.getProblems().get(0).getOrigin().getMessageId()).isNull();
        assertThat(first.getProblems().get(0).getOrigin().getReference())
                .isNotEqualTo(second.getProblems().get(0).getOrigin().getReference());
        Email copy = MAIL.emailBuilder().copying(first).fixingMessageId("<later@example.org>").buildEmail();
        assertThat(copy.getProblems().get(0).getOrigin().getMessageId()).isNull();
        Email duplicate = convert(Route.FLUENT_EML, message("Message-ID: <second@example.org>\r\nSubject: " + BAD + "\r\n"));
        assertThat(headerValues(duplicate.getSourceData(), "Message-ID"))
                .containsExactly("<original@example.org>", "<second@example.org>");
        assertThat(duplicate.getProblems().get(0).getOrigin().getMessageId()).isEqualTo("<original@example.org>");
    }

    @Test void sourceMetadataIsDetachedAndReadOnly() throws Exception {
        MimeMessage input = mime(message("Subject: readable\r\nX-Empty: \r\nComments: initial\r\n"));
        Email email = MAIL.converter().fromMimeMessage(input).toEmail();
        input.setHeader("Comments", "later");
        assertThat(headerValues(email.getSourceData(), "Comments")).containsExactly("initial");
        assertThat(headerValues(email.getSourceData(), "X-Empty")).containsExactly("");
        assertThatThrownBy(() -> email.getSourceData().getHeaders().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> email.getSourceData().getParts().add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> email.getProblems().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void completionStartsItsOwnHistoryAndLeavesInputAvailable() throws Exception {
        Email input = convert(Route.FLUENT_EML, message("Subject: " + BAD + "\r\n"));
        try (Mailer mailer = MAIL.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            Email completed = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(input);
            assertThat(completed.getSourceData().getHeaders()).isEmpty();
            assertThat(completed.getProblems()).isEmpty();
            assertThat(input.getProblems()).hasSize(1);
            assertThat(input.getSourceData().getHeaders()).isNotEmpty();
        }
    }

    @Test void freshCompositionHasEmptyInspection() {
        Email email = MAIL.emailBuilder().startingBlank().buildEmail();
        assertThat(email.getSourceData().getHeaders()).isEmpty();
        assertThat(email.getProblems()).isEmpty();
        assertThat(email.getSourceData().getOrigin()).isNull();
        assertThat(email.getSourceData().getFormat()).isNull();
        assertThat(email.getSourceData().getRepresentation()).isNull();
        assertThat(email.getSourceData().getProperties()).isEmpty();
        assertThat(email.getSourceData().getParts()).isEmpty();
        assertThat(email.getSourceData().getDerivedSources()).isEmpty();
    }

    @Test void failedDateAndMultipleDecodingFindingsKeepTheirAvailableSource() throws Exception {
        Email email = convert(Route.FLUENT_EML, message("Date: Fri, 31 Feb 2026 10:00:00 +0000\r\nSubject: " + BAD + "\r\nX-Legacy: " + BAD + "\r\n"));
        assertThat(email.getSentDate()).isNull();
        assertThat(headerValues(email.getSourceData(), "Date")).containsExactly("Fri, 31 Feb 2026 10:00:00 +0000");
        assertThat(email.getProblems()).hasSize(3);
        assertThat(email.getProblems()).extracting(problem -> problem.getOrigin().getReference())
                .containsOnly(email.getProblems().get(0).getOrigin().getReference());
        assertThat(email.getProblems()).extracting(EmailProblem::getCode)
                .contains(EmailProblem.Code.FIELD_NOT_INTERPRETED, EmailProblem.Code.VALUE_NOT_DECODED);
        Email sameId = convert(Route.FLUENT_EML, message("Subject: " + BAD + "\r\n"));
        assertThat(sameId.getProblems().get(0).getOrigin().getReference())
                .isNotEqualTo(email.getProblems().get(0).getOrigin().getReference());
    }

    @Test void aProviderSuppliedNullValueRemainsAReceivedOccurrence() throws Exception {
        MimeMessage input = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(message("Subject: readable\r\n").getBytes(UTF_8))) {
            @Override public Enumeration<jakarta.mail.Header> getAllHeaders() throws jakarta.mail.MessagingException {
                List<jakarta.mail.Header> headers = Collections.list(super.getAllHeaders());
                headers.add(new jakarta.mail.Header("X-Null", null));
                return Collections.enumeration(headers);
            }
        };
        Email email = MAIL.converter().fromMimeMessage(input).toEmail();
        assertThat(headerValues(email.getSourceData(), "X-Null")).hasSize(1).containsNull();
    }

    @Test void sourceAccessAndCopyingDoNotConsumeBorrowedContent() throws Exception {
        Email imported = convert(Route.FLUENT_EML, message("Subject: " + BAD + "\r\n"));
        jakarta.activation.DataSource unreadable = new jakarta.activation.DataSource() {
            public InputStream getInputStream() { throw new AssertionError("unexpected content read"); }
            public OutputStream getOutputStream() { throw new AssertionError("unexpected content write"); }
            public String getName() { return "borrowed"; }
            public String getContentType() { return "application/octet-stream"; }
        };
        Email email = MAIL.emailBuilder().copying(imported)
                .withAttachments(List.of(AttachmentResource.builder().withName("borrowed").withDataSource(unreadable).build())).buildEmail();
        for (int i = 0; i < 3; i++) {
            assertThat(headerValues(email.getSourceData(), "Subject")).containsExactly(BAD);
            assertThat(email.getProblems()).hasSize(1);
            email = MAIL.emailBuilder().copying(email).buildEmail();
        }
    }

    @Test void exactInspectionKeepsAuthoritativeBytesDespiteRecoveredHeaders() throws Exception {
        String eml = message("Subject: " + BAD + "\r\nComments: " + BAD + "\r\n");
        Email exact = convert(Route.EXACT, eml);
        assertThat(MAIL.converter().fromEmail(exact).toEmlBytes()).containsExactly(eml.getBytes(UTF_8));
        assertThat(exact.getProblems()).hasSize(1);
    }

    @Test void oldSnapshotsWithoutCapturedFieldsDoNotInventSourceHistory() throws Exception {
        Email old = MAIL.emailBuilder().startingBlank().withSubject("current").withHeader("X-Current", "edited").buildEmail();
        for (String name : List.of("sourceData", "problems")) {
            java.lang.reflect.Field field = Email.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(old, null);
        }
        Email restored = roundTrip(old);
        assertThat(restored.getSourceData().getHeaders()).isEmpty();
        assertThat(restored.getSourceData().getOrigin()).isNull();
        assertThat(restored.getSourceData().getFormat()).isNull();
        assertThat(restored.getSourceData().getProperties()).isEmpty();
        assertThat(restored.getSourceData().getParts()).isEmpty();
        assertThat(restored.getSourceData().getDerivedSources()).isEmpty();
        assertThat(restored.getProblems()).isEmpty();
        assertThat(restored.getHeaders()).containsKey("X-Current");
    }

    @Test void rawHeaderSpellingFoldingAndLegalPunctuationRemainAvailable() throws Exception {
        String folded = "first\r\n\tcontinued";
        Email email = convert(Route.FLUENT_EML, message("Subject: readable\r\nx-Odd(Name): " + folded + "\r\n"));
        assertThat(headerValues(email.getSourceData(), "X-ODD(NAME)")).containsExactly(folded);
        assertThat(email.getSourceData().getHeaders()).extracting(EmailSourceHeader::getName).contains("x-Odd(Name)");
        assertThat(email.getProblems()).isEmpty();
    }

    @Test void anApplicationFailureWithAnEncodingCauseIsNotAParserRecovery() throws Exception {
        IllegalStateException failure = new IllegalStateException("application failure", new UnsupportedEncodingException("application codec"));
        MimeMessage input = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(message("Subject: " + BAD + "\r\n").getBytes(UTF_8))) {
            @Override public String getSubject() { throw failure; }
        };
        assertThatThrownBy(() -> MAIL.converter().fromMimeMessage(input).toEmail()).isSameAs(failure);
    }

    @Test void aProviderThatSuccessfullyInterpretsItsOwnSubjectIsNotOverwritten() throws Exception {
        MimeMessage input = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(message("Subject: " + BAD + "\r\n").getBytes(UTF_8))) {
            @Override public String getSubject() { return "Decoded by the source provider"; }
        };
        Email email = MAIL.converter().fromMimeMessage(input).toEmail();
        assertThat(email.getSubject()).isEqualTo("Decoded by the source provider");
        assertThat(headerValues(email.getSourceData(), "Subject")).containsExactly(BAD);
        assertThat(email.getProblems()).isEmpty();
    }

    // Test-only navigation over the generic diagnostic view; no named-header lookup is part of the source API.
    private static List<String> headerValues(EmailSourceData source, String name) {
        List<String> values = new ArrayList<>();
        for (EmailSourceHeader header : source.getHeaders()) {
            if (header.getName().equalsIgnoreCase(name)) values.add(header.getRawValue());
        }
        return values;
    }

    private static EmailSourceHeader firstHeader(EmailSourceData source, String name) {
        for (EmailSourceHeader header : source.getHeaders()) {
            if (header.getName().equalsIgnoreCase(name)) return header;
        }
        throw new AssertionError("Missing source header " + name);
    }

    private static String message(String fields) {
        return HEADERS + fields + "Content-Type: text/plain; charset=UTF-8\r\n\r\nReadable body\r\n";
    }
    private static MimeMessage mime(String eml) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml.getBytes(UTF_8)));
    }
    private static Email convert(Route route, String eml) throws Exception {
        switch (route) {
            case STATIC_EML: return EmailConverter.emlToEmail(eml);
            case STATIC_MIME: return EmailConverter.mimeMessageToEmail(mime(eml));
            case FLUENT_MIME: return MAIL.converter().fromMimeMessage(mime(eml)).toEmail();
            case EXACT: return MAIL.emailBuilder().startingFromExactEml(eml.getBytes(UTF_8)).withEnvelopeRecipients("reader@example.org").buildEmail();
            default: return MAIL.converter().fromEml(eml).toEmail();
        }
    }
    private static Email roundTrip(Email email) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(email); }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { return (Email) in.readObject(); }
    }
}
