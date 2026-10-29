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
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
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
    void unrelatedGetterAddressExceptionsAreNotRecoveredFromValidSourceHeaders(final String field) throws Exception {
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
            assertThatThrownBy(() -> MAIL.converter().fromMimeMessage(source).toEmail())
                    .isInstanceOf(MailException.class).hasRootCause(failure);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"From", "Reply-To"})
    void genericAddressGetterFailuresKeepTheirExistingFailureBoundary(final String field) throws Exception {
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
            assertThatThrownBy(() -> MAIL.converter().fromMimeMessage(source).toEmail())
                    .isInstanceOf(MailException.class).hasRootCause(failure);
        }
    }

    @Test
    void requiredContentAndCodecFailuresAreNotHiddenByAddressRecovery() throws Exception {
        final String undecodableName = message(FROM, "broken <address").replace("filename=payload.bin", "filename=\"=?X-NO-SUCH-CHARSET?Q?payload.bin?=\"");
        assertThatThrownBy(() -> MAIL.converter().fromEml(undecodableName).toEmail())
                .isInstanceOf(MailException.class).hasRootCauseInstanceOf(UnsupportedEncodingException.class);
        final IOException failure = new IOException("required message content unavailable");
        final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(message(FROM, "broken <address").getBytes(UTF_8))) {
            @Override public Object getContent() throws IOException { throw failure; }
        };
        try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class)) {
            assertThatThrownBy(() -> MAIL.converter().fromMimeMessage(source).toEmail())
                    .isInstanceOf(MailException.class).hasRootCause(failure);
        }
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
