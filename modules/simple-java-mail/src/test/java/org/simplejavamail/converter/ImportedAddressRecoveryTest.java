package org.simplejavamail.converter;

import com.sanctionco.jmail.EmailValidator;
import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceHeader;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ValidationConfig;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.mailer.MailInvalidAddressException;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImportedAddressRecoveryTest {

    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final String FROM = "Sender <sender@example.org>";
    private static final String TO = "Reader <reader@example.org>, Reader <reader@example.org>";
    private static final String REPLY = "Support <support@example.org>";
    private static final String BODY = "Subject: Readable mail\r\nX-Context: retained\r\nMIME-Version: 1.0\r\n"
            + "Content-Type: multipart/mixed; boundary=parts\r\n\r\n"
            + "--parts\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nReadable body\r\n"
            + "--parts\r\nContent-Type: application/octet-stream\r\n"
            + "Content-Disposition: attachment; filename=payload.bin\r\n\r\nAttachment bytes\r\n--parts--\r\n";

    @ParameterizedTest
    @ValueSource(strings = {"broken <address", "\"unterminated", "Valid <valid@example.org>, broken <address"})
    void malformedFromRetainsTheWholeFieldAndReadableNeighbors(final String source) throws Exception {
        for (final ImportRoute route : ImportRoute.values()) {
            final String original = message(source, REPLY);
            final Email email = convert(route, original);
            assertThat(email.getFromRecipient().getAddress()).as("%s", route).isEqualTo(source);
            assertThat(email.getFromRecipient().getName()).isNull();
            assertThat(email.getReplyToRecipients()).extracting(Recipient::getAddress).containsExactly("support@example.org");
            assertReadableContent(email);
            assertExactSource(route, email, original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"broken <address", "\"unterminated", "Valid <valid@example.org>, broken <address"})
    void explicitMalformedReplyToRetainsItsWholeFieldInsteadOfTheFromAddress(final String source) throws Exception {
        for (final ImportRoute route : ImportRoute.values()) {
            final String original = message(FROM, source);
            final Email email = convert(route, original);
            assertThat(email.getReplyToRecipients()).as("%s", route).extracting(Recipient::getAddress).containsExactly(source);
            assertThat(email.getReplyToRecipients().get(0).getName()).isNull();
            assertThat(email.getFromRecipient().getAddress()).isEqualTo("sender@example.org");
            assertReadableContent(email);
            assertExactSource(route, email, original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"From", "Reply-To"})
    void grammarFailureRetainsAllRepeatedHeaderOccurrences(final String field) throws Exception {
        final String source = message(FROM, REPLY).replace(field + ": " + (field.equals("From") ? FROM : REPLY) + "\r\n",
                field + ": First <first@example.org>\r\n" + field + ": broken <address\r\n");
        for (final ImportRoute route : ImportRoute.values()) {
            final Email imported = convert(route, source);
            assertThat(address(imported, field)).as("%s", route).isEqualTo("First <first@example.org>,broken <address");
            assertReadableContent(imported);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"<absent>", "", "(comment only)"})
    void missingOrEmptyReplyToInheritsTheMalformedFromSource(final String reply) throws Exception {
        final String source = message("broken <address", reply.equals("<absent>") ? null : reply);
        for (final ImportRoute route : ImportRoute.values()) {
            final Email imported = convert(route, source);
            assertThat(imported.getFromRecipient().getAddress()).isEqualTo("broken <address");
            assertThat(imported.getReplyToRecipients()).extracting(Recipient::getAddress).containsExactly("broken <address");
            assertReadableContent(imported);
        }
    }

    @Test
    void missingFromRetainsTheMalformedSenderRatherThanInventingAnAddress() throws Exception {
        final String source = "Sender: broken <address\r\n" + message(null, null);
        for (final ImportRoute route : ImportRoute.values()) {
            final Email imported = convert(route, source);
            assertThat(imported.getFromRecipient().getAddress()).isEqualTo("broken <address");
            assertThat(imported.getReplyToRecipients()).extracting(Recipient::getAddress).containsExactly("broken <address");
            assertReadableContent(imported);
        }
    }

    @ParameterizedTest
    @EnumSource(ImportRoute.class)
    void selectedMalformedSenderHasOneSourceLinkedProblem(final ImportRoute route) throws Exception {
        final String malformed = "broken <address";
        final String source = "Message-ID: <received@example.org>\r\nSender: " + malformed + "\r\n" + message(null, null);
        final Email imported = convert(route, source);
        assertThat(imported.getFromRecipient().getAddress()).isEqualTo(malformed);
        assertThat(imported.getReplyToRecipients()).extracting(Recipient::getAddress).containsExactly(malformed);
        assertReadableContent(imported);
        assertExactSource(route, imported, source);
        assertSenderProblem(imported, malformed);

        final Email copy = MAIL.emailBuilder().copying(imported).fixingMessageId("<copy@example.org>").buildEmail();
        assertThat(copy.getSourceData()).isSameAs(imported.getSourceData());
        assertThat(copy.getProblems()).containsExactlyElementsOf(imported.getProblems());
        assertSenderProblem(roundTrip(copy), malformed);
    }

    @Test
    void unusedMalformedSenderDoesNotAcquireAProblem() throws Exception {
        final Email imported = MAIL.converter().fromEml("Sender: broken <address\r\n" + message(FROM, REPLY)).toEmail();
        assertThat(imported.getFromRecipient().getAddress()).isEqualTo("sender@example.org");
        assertThat(imported.getReplyToRecipients()).extracting(Recipient::getAddress).containsExactly("support@example.org");
        assertThat(imported.getProblems()).isEmpty();
        assertReadableContent(imported);
    }

    @Test
    void senderRecoveryReferencesEveryUsedOccurrenceWithoutInspectingExplicitReplyTo() {
        final String joined = "First <first@example.org>,broken <address";
        final Email imported = MAIL.converter().fromEml("Sender: First <first@example.org>\r\nsender: broken <address\r\n"
                + message(null, REPLY)).toEmail();
        final List<EmailSourceHeader> senders = new ArrayList<>();
        for (final EmailSourceHeader header : imported.getSourceData().getHeaders()) {
            if (header.getName().equalsIgnoreCase("Sender")) senders.add(header);
        }
        assertThat(imported.getFromRecipient().getAddress()).isEqualTo(joined);
        assertThat(imported.getReplyToRecipients()).extracting(Recipient::getAddress).containsExactly("support@example.org");
        assertThat(senders).hasSize(2);
        assertThat(imported.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.FIELD_NOT_INTERPRETED);
            assertThat(problem.getLocation()).isEqualTo("Sender");
            assertThat(problem.getSourceValue()).isEqualTo(joined);
            assertThat(problem.getSources()).containsExactlyElementsOf(senders);
        });
        assertReadableContent(imported);
    }

    @Test
    void successfulProviderSenderInterpretationDoesNotAcquireAProblem() throws Exception {
        final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(("Sender: broken <address\r\n" + message(null, null)).getBytes(UTF_8))) {
            @Override public Address[] getFrom() throws MessagingException {
                return new Address[] {new jakarta.mail.internet.InternetAddress("native@example.org")};
            }
        };
        final Email imported = MAIL.converter().fromMimeMessage(source).toEmail();
        assertThat(imported.getFromRecipient().getAddress()).isEqualTo("native@example.org");
        assertThat(imported.getReplyToRecipients()).extracting(Recipient::getAddress).containsExactly("native@example.org");
        assertThat(imported.getProblems()).isEmpty();
        assertReadableContent(imported);
    }

    private static void assertSenderProblem(final Email email, final String malformed) {
        final EmailSourceHeader sender = email.getSourceData().getHeaders().stream()
                .filter(header -> header.getName().equalsIgnoreCase("Sender")).findFirst().orElseThrow(AssertionError::new);
        assertThat(email.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getOperation()).isEqualTo(EmailProblem.Operation.IMPORT);
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.FIELD_NOT_INTERPRETED);
            assertThat(problem.getLocation()).isEqualTo("Sender");
            assertThat(problem.getSourceValue()).isEqualTo(malformed);
            assertThat(problem.getOrigin()).isSameAs(email.getSourceData().getOrigin());
            assertThat(problem.getOrigin().getMessageId()).isEqualTo("<received@example.org>");
            assertThat(problem.getSources()).containsExactly(sender);
        });
    }

    @ParameterizedTest
    @EnumSource(ImportRoute.class)
    void validNamesListsAndExistingNonStrictRecipientInterpretationRemainUnchanged(final ImportRoute route) throws Exception {
        final String source = message("First <first@example.org>, Second <second@example.org>",
                "\"Doe, Jane\" <jane@example.org>, Other <other@example.org>, Other <other@example.org>")
                .replace("Cc: Audit <audit@example.org>", "Cc: \"Doe, Jane\" <jane@example.org>;")
                .replace("Bcc: Archive <archive@example.org>", "Bcc: broken <address");
        final Email imported = convert(route, source);
        assertThat(imported.getFromRecipient().getAddress()).isEqualTo("first@example.org");
        assertThat(imported.getFromRecipient().getName()).isEqualTo("First");
        assertThat(imported.getReplyToRecipients()).extracting(Recipient::getAddress)
                .containsExactly("jane@example.org", "other@example.org", "other@example.org");
        assertThat(imported.getReplyToRecipients()).extracting(Recipient::getName).containsExactly("Doe, Jane", "Other", "Other");
        assertThat(imported.getToRecipients()).extracting(Recipient::getAddress).containsExactly("reader@example.org", "reader@example.org");
        assertThat(imported.getToRecipients()).extracting(Recipient::getName).containsExactly("Reader", "Reader");
        assertThat(imported.getCcRecipients()).extracting(Recipient::getAddress).containsExactly("jane@example.org");
        assertThat(imported.getCcRecipients().get(0).getName()).isEqualTo("Doe, Jane");
        assertThat(imported.getBccRecipients()).extracting(Recipient::getAddress).containsExactly("broken <address");
        assertThat(imported.getPlainText()).isEqualTo("Readable body");
    }

    @Test
    void suppliedNonStrictSessionRetainsItsSuccessfulFromAndReplyToInterpretation() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.mime.address.strict", "false");
        final String original = message("broken <address", "another <reply");
        for (final boolean fluent : new boolean[] {false, true}) {
            final MimeMessage source = new MimeMessage(Session.getInstance(properties), new ByteArrayInputStream(original.getBytes(UTF_8)));
            final Email imported = fluent ? MAIL.converter().fromMimeMessage(source).toEmail() : EmailConverter.mimeMessageToEmail(source);
            assertThat(imported.getFromRecipient().getAddress()).isEqualTo("broken <address");
            assertThat(imported.getReplyToRecipients()).extracting(Recipient::getAddress).containsExactly("another <reply");
            assertReadableContent(imported);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"<absent>", "", "(comment only)"})
    void validFromFallbackKeepsEveryReplyMailboxAndMissingFieldsKeepTheirMeaning(final String reply) throws Exception {
        for (final ImportRoute route : ImportRoute.values()) {
            final Email imported = convert(route, message("First <first@example.org>, Second <second@example.org>",
                    reply.equals("<absent>") ? null : reply));
            assertThat(imported.getFromRecipient().getAddress()).isEqualTo("first@example.org");
            assertThat(imported.getReplyToRecipients()).extracting(Recipient::getAddress)
                    .containsExactly("first@example.org", "second@example.org");
            final Email missing = convert(route, "Sender: Sender <sender@example.org>\r\n" + message("", null));
            assertThat(missing.getFromRecipient()).isNull();
            assertThat(missing.getReplyToRecipients()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"From", "Reply-To"})
    void copiedAndSerializedMalformedAddressesRemainVisibleToOutgoingValidation(final String field) throws Exception {
        final Email imported = MAIL.converter().fromEml(message(field.equals("From") ? "broken <address" : FROM,
                field.equals("Reply-To") ? "broken <address" : REPLY)).toEmail();
        try (Mailer mailer = MAIL.mailerBuilder().withTransportModeLoggingOnly(true).withProperty("mail.host", "localhost").buildMailer()) {
            for (final Email email : Arrays.asList(imported, MAIL.emailBuilder().copying(imported).buildEmail(), roundTrip(imported))) {
                assertThat(address(email, field)).isEqualTo("broken <address");
                assertThatThrownBy(() -> mailer.validate(email, false)).isInstanceOf(MailInvalidAddressException.class)
                        .hasMessageContaining("broken <address");
                assertThatThrownBy(() -> mailer.rehearse(email)).isInstanceOf(MailInvalidAddressException.class)
                        .hasMessageContaining("broken <address");
            }
        }
    }

    @Test
    void warningsOnlyStillPassesTheRawMalformedAddressToTheConfiguredValidator() throws Exception {
        final Email imported = MAIL.converter().fromEml(message(FROM, "broken <address")).toEmail();
        final List<String> inspected = new ArrayList<>();
        final EmailValidator validator = mock(EmailValidator.class);
        when(validator.isValid(anyString())).thenAnswer(invocation -> {
            final String supplied = invocation.getArgument(0);
            inspected.add(supplied);
            return !supplied.equals("broken <address");
        });
        try (Mailer warning = MAIL.mailerBuilder().withTransportModeLoggingOnly(true).withProperty("mail.host", "localhost")
                .withValidationConfig(ValidationConfig.builder().withEmailValidator(validator).withClientValidationWarningsOnly(true).build())
                .buildMailer()) {
            warning.validate(imported, false);
            assertThat(inspected).contains("broken <address");
        }
    }

    @Test
    void exactInspectionKeepsAuthoritativeBytesAndStrictEnvelopeValidation() throws Exception {
        final byte[] original = message("broken <address", "Reply <reply@example.org>").getBytes(UTF_8);
        final Email exact = MAIL.emailBuilder().startingFromExactEml(original).withEnvelopeRecipients("envelope@example.org").buildEmail();
        assertThat(exact.getFromRecipient().getAddress()).isEqualTo("broken <address");
        assertThat(MAIL.converter().fromEmail(exact).toEmlBytes()).containsExactly(original);
        assertThatThrownBy(() -> MAIL.emailBuilder().startingFromExactEml(original).withEnvelopeRecipients("broken <address"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("envelopeRecipient");
    }

    @Test
    void callerOwnedEmlStreamRemainsOpenAfterAddressRecovery() {
        final TrackingInput source = new TrackingInput(message(FROM, "broken <address").getBytes(UTF_8));
        assertThat(MAIL.converter().fromEml(source).toEmail().getReplyToRecipients().get(0).getAddress()).isEqualTo("broken <address");
        assertThat(source.closed).isFalse();
    }

    @Test
    void addressRecoveryDoesNotReadLazyBorrowedAttachmentContent() throws Exception {
        final AtomicInteger reads = new AtomicInteger();
        final IOException failure = new IOException("borrowed attachment unavailable");
        final MimeBodyPart attachment = new MimeBodyPart();
        attachment.setDisposition(Part.ATTACHMENT);
        attachment.setFileName("payload.bin");
        attachment.setDataHandler(new DataHandler(new DataSource() {
            @Override public InputStream getInputStream() throws IOException { reads.incrementAndGet(); throw failure; }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "application/octet-stream"; }
            @Override public String getName() { return "payload.bin"; }
        }));
        final MimeBodyPart body = new MimeBodyPart();
        body.setText("Readable body", "UTF-8");
        final MimeMultipart parts = new MimeMultipart();
        parts.addBodyPart(body);
        parts.addBodyPart(attachment);
        final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()));
        source.setHeader("From", FROM);
        source.setHeader("To", TO);
        source.setHeader("Reply-To", "broken <address");
        source.setContent(parts);
        try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class)) {
            final Email imported = MAIL.converter().fromMimeMessage(source).withFetchAttachmentData(false).toEmail();
            assertThat(imported.getReplyToRecipients()).extracting(Recipient::getAddress).containsExactly("broken <address");
            assertThat(imported.getPlainText()).isEqualTo("Readable body");
            assertThat(reads).hasValue(0);
            assertThatThrownBy(() -> imported.getAttachments().get(0).readAllBytes()).hasRootCause(failure);
            assertThat(reads).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"From", "Reply-To"})
    void checkedGetterAddressExceptionsRetainTheWholeFieldWithItsProblem(final String field) throws Exception {
        final AddressException failure = new AddressException("application supplied getter failure");
        final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(message(FROM, REPLY).getBytes(UTF_8))) {
            @Override public Address[] getFrom() throws MessagingException {
                if (field.equals("From")) throw failure;
                return super.getFrom();
            }
            @Override public Address[] getReplyTo() throws MessagingException {
                if (field.equals("Reply-To")) throw failure;
                return super.getReplyTo();
            }
        };
        try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class)) {
            final Email imported = MAIL.converter().fromMimeMessage(source).toEmail();
            assertThat(address(imported, field)).isEqualTo(field.equals("From") ? FROM : REPLY);
            assertReadableContent(imported);
            assertCheckedFieldProblem(imported, field, failure);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"From", "Reply-To"})
    void checkedAddressGetterFailuresPreserveNeighborsAndReportTheirSource(final String field) throws Exception {
        final MessagingException failure = new MessagingException("source address field unavailable");
        final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(message(FROM, REPLY).getBytes(UTF_8))) {
            @Override public Address[] getFrom() throws MessagingException {
                if (field.equals("From")) throw failure;
                return super.getFrom();
            }
            @Override public Address[] getReplyTo() throws MessagingException {
                if (field.equals("Reply-To")) throw failure;
                return super.getReplyTo();
            }
        };
        try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class)) {
            final Email imported = MAIL.converter().fromMimeMessage(source).toEmail();
            assertThat(address(imported, field)).isEqualTo(field.equals("From") ? FROM : REPLY);
            assertReadableContent(imported);
            assertCheckedFieldProblem(imported, field, failure);
        }
    }

    @Test
    void namingCodecAndRequiredContentIssuesRemainInspectableAlongsideAddressRecovery() throws Exception {
        final String encodedName = "=?X-NO-SUCH-CHARSET?Q?payload.bin?=";
        final String undecodableName = message(FROM, "broken <address").replace("filename=payload.bin", "filename=\"" + encodedName + "\"");
        final Email named = MAIL.converter().fromEml(undecodableName).toEmail();
        assertThat(named.getReplyToRecipients()).extracting(Recipient::getAddress).containsExactly("broken <address");
        assertThat(named.getPlainText()).isEqualTo("Readable body");
        assertThat(named.getAttachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.getName()).isEqualTo(encodedName);
            assertThat(attachment.readAllBytes()).isEqualTo("Attachment bytes".getBytes(UTF_8));
        });
        assertThat(named.getProblems()).anySatisfy(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.VALUE_NOT_DECODED);
            assertThat(problem.getSources()).anySatisfy(element -> {
                assertThat(element).isInstanceOf(EmailSourceHeader.class);
                assertThat(((EmailSourceHeader) element).getName()).isEqualTo("Content-Disposition");
                assertThat(((EmailSourceHeader) element).getRawValue()).contains(encodedName);
            });
        });

        final IOException failure = new IOException("required message content unavailable");
        final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(message(FROM, "broken <address").getBytes(UTF_8))) {
            @Override public Object getContent() throws IOException { throw failure; }
        };
        try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class)) {
            final Email imported = MAIL.converter().fromMimeMessage(source).toEmail();
            assertThat(imported.getSubject()).isEqualTo("Readable mail");
            assertThat(imported.getReplyToRecipients()).extracting(Recipient::getAddress).containsExactly("broken <address");
            assertThat(imported.getPlainText()).isNull();
            assertThat(imported.getAttachments()).isEmpty();
            assertThat(imported.getProblems()).anySatisfy(problem -> {
                assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONTENT_READ_FAILED);
                assertThat(problem.getMessage()).contains(failure.getMessage());
                assertThat(problem.getSources()).anySatisfy(element -> {
                    assertThat(element).isInstanceOf(EmailSourcePart.class);
                    final EmailSourcePart part = (EmailSourcePart) element;
                    assertThat(part.getContent()).isNotNull();
                    assertThat(part.getContent().isFullyCaptured()).isTrue();
                    assertThat(new String(part.getContent().readAllBytes(), UTF_8)).contains("Readable body", "Attachment bytes");
                });
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"From", "Reply-To"})
    void applicationRuntimeFailuresAreNotReclassifiedByTheirCheckedCauses(final String field) throws Exception {
        for (Exception cause : Arrays.asList(new AddressException("application grammar failure"),
                new MessagingException("application reader failure"), new UnsupportedEncodingException("application codec"))) {
            final IllegalStateException failure = new IllegalStateException("application address getter failure", cause);
            final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(message(FROM, REPLY).getBytes(UTF_8))) {
                @Override public Address[] getFrom() throws MessagingException {
                    if (field.equals("From")) throw failure;
                    return super.getFrom();
                }
                @Override public Address[] getReplyTo() throws MessagingException {
                    if (field.equals("Reply-To")) throw failure;
                    return super.getReplyTo();
                }
            };
            try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class)) {
                assertThatThrownBy(() -> MAIL.converter().fromMimeMessage(source).toEmail()).isSameAs(failure);
            }
        }
    }

    private static void assertCheckedFieldProblem(final Email email, final String field, final MessagingException failure) {
        final EmailSourceHeader captured = email.getSourceData().getHeaders().stream()
                .filter(header -> header.getName().equalsIgnoreCase(field)).findFirst().orElseThrow(AssertionError::new);
        assertThat(captured.getRawValue()).isEqualTo(field.equals("From") ? FROM : REPLY);
        assertThat(email.getProblems()).anySatisfy(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONTENT_READ_FAILED);
            assertThat(problem.getOperation()).isEqualTo(EmailProblem.Operation.IMPORT);
            assertThat(problem.getLocation()).isEqualTo(field);
            assertThat(problem.getMessage()).contains(failure.getMessage());
            assertThat(problem.getSources()).contains(captured);
        });
    }

    private static String message(final String from, final String replyTo) {
        return (from == null ? "" : "From: " + from + "\r\n") + "To: " + TO + "\r\n"
                + "Cc: Audit <audit@example.org>\r\nBcc: Archive <archive@example.org>\r\n"
                + (replyTo == null ? "" : "Reply-To: " + replyTo + "\r\n") + BODY;
    }

    private static void assertReadableContent(final Email email) {
        assertThat(email.getSubject()).isEqualTo("Readable mail");
        assertThat(email.getPlainText()).isEqualTo("Readable body");
        assertThat(email.getToRecipients()).extracting(Recipient::getAddress).containsExactly("reader@example.org", "reader@example.org");
        assertThat(email.getCcRecipients()).extracting(Recipient::getAddress).containsExactly("audit@example.org");
        assertThat(email.getBccRecipients()).extracting(Recipient::getAddress).containsExactly("archive@example.org");
        assertThat(email.getHeaders().get("X-Context")).containsExactly("retained");
        assertThat(email.getAttachments()).extracting("name").containsExactly("payload.bin");
        assertThat(email.getAttachments().get(0).readAllBytes()).isEqualTo("Attachment bytes".getBytes(UTF_8));
    }

    private static String address(final Email email, final String field) {
        return field.equals("From") ? email.getFromRecipient().getAddress() : email.getReplyToRecipients().get(0).getAddress();
    }

    private static void assertExactSource(final ImportRoute route, final Email email, final String original) {
        if (route == ImportRoute.EXACT_EML) {
            assertThat(MAIL.converter().fromEmail(email).toEmlBytes()).containsExactly(original.getBytes(UTF_8));
        }
    }

    private static Email convert(final ImportRoute route, final String eml) throws Exception {
        switch (route) {
            case STATIC_EML: return EmailConverter.emlToEmail(eml);
            case FLUENT_EML: return MAIL.converter().fromEml(eml).toEmail();
            case EXACT_EML: return MAIL.emailBuilder().startingFromExactEml(eml.getBytes(UTF_8)).withEnvelopeRecipients("envelope@example.org").buildEmail();
            default:
                final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml.getBytes(UTF_8)));
                return route == ImportRoute.STATIC_MIME ? EmailConverter.mimeMessageToEmail(source)
                        : MAIL.converter().fromMimeMessage(source).toEmail();
        }
    }

    private static Email roundTrip(final Email email) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(email); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { return (Email) input.readObject(); }
    }

    private enum ImportRoute { STATIC_EML, FLUENT_EML, STATIC_MIME, FLUENT_MIME, EXACT_EML }

    private static final class TrackingInput extends ByteArrayInputStream {
        private boolean closed;
        private TrackingInput(final byte[] bytes) { super(bytes); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
