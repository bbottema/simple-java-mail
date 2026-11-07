package org.simplejavamail.converter;

import jakarta.mail.Address;
import jakarta.mail.Header;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.config.ConfigLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class AbsentFieldRecoveryTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    private static final String CONTENT = "Subject: Readable mail\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nReadable body";

    @ParameterizedTest
    @MethodSource("missingAddressCases")
    void unavailableAddressWithoutCapturedTextKeepsReadableNeighbors(final String field, final boolean captureFails,
            final boolean staticImport, final boolean grammarFailure) throws Exception {
        final MessagingException failure = grammarFailure ? new AddressException("address interpretation unavailable")
                : new MessagingException("address reader unavailable");
        final MimeMessage source = addressInput(CONTENT, field, captureFails, failure);

        final Email email = staticImport ? EmailConverter.mimeMessageToEmail(source) : MAIL.converter().fromMimeMessage(source).toEmail();

        assertThat(email.getSubject()).isEqualTo("Readable mail");
        assertThat(email.getPlainText()).isEqualTo("Readable body");
        assertThat(email.getFromRecipient()).isNull();
        assertThat(email.getReplyToRecipients()).isEmpty();
        assertThat(email.getRecipients()).isEmpty();
        assertThat(email.getProblems()).anySatisfy(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONTENT_READ_FAILED);
            assertThat(problem.getOperation()).isEqualTo(EmailProblem.Operation.IMPORT);
            assertThat(problem.getLocation()).isEqualTo(field);
            assertThat(problem.getMessage()).contains(failure.getMessage());
            assertThat(problem.getSources()).isEmpty();
            assertThat(problem.getOrigin().getReference()).isEqualTo(email.getSourceData().getOrigin().getReference());
        });
        if (captureFails) {
            assertThat(email.getProblems()).anySatisfy(problem -> {
                assertThat(problem.getLocation()).isEqualTo("source/headers");
                assertThat(problem.getMessage()).contains("source headers unavailable");
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"From", "Reply-To", "To", "Cc", "Bcc"})
    void uncapturedHeaderTextDoesNotBecomeAnInventedAddress(final String field) throws Exception {
        final MessagingException failure = new MessagingException("address reader unavailable");
        final MimeMessage source = addressInput(field + ": Supplied <reader@example.org>\r\n" + CONTENT, field, true, failure);
        final Email email = MAIL.converter().fromMimeMessage(source).toEmail();

        assertThat(addresses(email, field)).isEmpty();
        assertThat(email.getPlainText()).isEqualTo("Readable body");
        assertThat(email.getProblems()).anySatisfy(problem -> {
            assertThat(problem.getLocation()).isEqualTo(field);
            assertThat(problem.getMessage()).contains(failure.getMessage());
            assertThat(problem.getSources()).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"From", "Reply-To", "To", "Cc", "Bcc"})
    void unavailableAddressDoesNotDiscardOtherReadableAddresses(final String field) throws Exception {
        final StringBuilder content = new StringBuilder();
        for (final String neighbor : List.of("From", "Reply-To", "To", "Cc", "Bcc")) {
            if (!neighbor.equals(field)) content.append(neighbor).append(": Healthy <healthy@example.org>\r\n");
        }
        content.append(CONTENT);
        final Email email = MAIL.converter().fromMimeMessage(addressInput(content.toString(), field, false,
                new MessagingException("selected address unavailable"))).toEmail();

        assertThat(addresses(email, field)).isEmpty();
        for (final String neighbor : List.of("From", "Reply-To", "To", "Cc", "Bcc")) {
            if (!neighbor.equals(field)) assertThat(addresses(email, neighbor)).containsExactly("healthy@example.org");
        }
        assertThat(email.getPlainText()).isEqualTo("Readable body");
    }

    @ParameterizedTest
    @ValueSource(strings = {"From", "Reply-To", "To", "Cc", "Bcc"})
    void actualCapturedTextRemainsTheFallback(final String field) throws Exception {
        final String raw = "broken <address";
        final MessagingException failure = new MessagingException("address reader unavailable");
        final Email email = MAIL.converter().fromMimeMessage(addressInput(field + ": " + raw + "\r\n" + CONTENT,
                field, false, failure)).toEmail();

        assertThat(addresses(email, field)).containsExactly(raw);
        assertThat(email.getPlainText()).isEqualTo("Readable body");
        assertThat(email.getProblems()).anySatisfy(problem -> {
            assertThat(problem.getLocation()).isEqualTo(field);
            assertThat(problem.getMessage()).contains(failure.getMessage());
            assertThat(problem.getSources()).anySatisfy(header -> assertThat(header)
                    .isInstanceOf(org.simplejavamail.api.email.EmailSourceHeader.class));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Subject", "Message-ID", "Date"})
    void optionalScalarFailuresAlreadyLeaveUnavailableValuesSafe(final String field) throws Exception {
        final MessagingException failure = new MessagingException("optional scalar unavailable");
        final String content = field.equals("Subject") ? CONTENT.replace("Subject: Readable mail\r\n", "") : CONTENT;
        final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(content.getBytes(UTF_8))) {
            @Override public String getSubject() throws MessagingException {
                if (field.equals("Subject")) throw failure;
                return super.getSubject();
            }
            @Override public String getMessageID() throws MessagingException {
                if (field.equals("Message-ID")) throw failure;
                return super.getMessageID();
            }
            @Override public String getHeader(final String name, final String delimiter) throws MessagingException {
                if (field.equals("Date") && name.equals("Date")) throw failure;
                return super.getHeader(name, delimiter);
            }
        };
        final Email email = MAIL.converter().fromMimeMessage(source).toEmail();

        assertThat(email.getPlainText()).isEqualTo("Readable body");
        assertThat(email.getSubject()).isEqualTo(field.equals("Subject") ? "" : "Readable mail");
        assertThat(email.getId()).isNull();
        assertThat(email.getSentDate()).isNull();
        assertThat(email.getProblems()).anySatisfy(problem -> {
            assertThat(problem.getLocation()).isEqualTo(field);
            assertThat(problem.getMessage()).contains(failure.getMessage());
            assertThat(problem.getSources()).isEmpty();
        });
    }

    @ParameterizedTest
    @MethodSource("metadataCases")
    void optionalResourceMetadataFailuresAlreadyPreserveTheResource(final String field, final boolean eager) throws Exception {
        final MessagingException failure = new MessagingException("optional metadata unavailable");
        final MimeBodyPart resource = new MimeBodyPart(new ByteArrayInputStream(
                "Content-Type: application/octet-stream\r\n\r\nAttachment bytes".getBytes(UTF_8))) {
            @Override public String getContentID() throws MessagingException {
                if (field.equals("Content-ID")) throw failure;
                return super.getContentID();
            }
            @Override public String getFileName() throws MessagingException {
                if (field.equals("filename")) throw failure;
                return super.getFileName();
            }
            @Override public String getDisposition() throws MessagingException {
                if (field.equals("Content-Disposition")) throw failure;
                return super.getDisposition();
            }
            @Override public String getHeader(final String name, final String delimiter) throws MessagingException {
                if (field.equals("Content-Description") && name.equals(field)) throw failure;
                return super.getHeader(name, delimiter);
            }
        };
        final MimeBodyPart body = new MimeBodyPart();
        body.setText("Readable body", "UTF-8");
        final MimeMultipart parts = new MimeMultipart("mixed");
        parts.addBodyPart(body);
        parts.addBodyPart(resource);
        final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()));
        source.setContent(parts);

        final Email email = MAIL.converter().fromMimeMessage(source).withFetchAttachmentData(eager).toEmail();

        assertThat(email.getPlainText()).isEqualTo("Readable body");
        assertThat(email.getAttachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.getName()).isEqualTo("UnknownAttachment");
            assertThat(attachment.getContentId()).isNull();
            assertThat(attachment.getDescription()).isNull();
            assertThat(attachment.readAllBytes()).isEqualTo("Attachment bytes".getBytes(UTF_8));
        });
        assertThat(email.getProblems()).anySatisfy(problem -> assertThat(problem.getMessage()).contains(failure.getMessage()));
    }

    @Test
    void copiesAndSerializationPreserveUnavailableFactsAndProblemsWithoutReinspection() throws Exception {
        final AtomicInteger reads = new AtomicInteger();
        final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(CONTENT.getBytes(UTF_8))) {
            @Override public Address[] getFrom() throws MessagingException {
                reads.incrementAndGet();
                throw new MessagingException("sender unavailable");
            }
        };
        final Email imported = MAIL.converter().fromMimeMessage(source).toEmail();
        final int selectedReads = reads.get();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(imported); }
        final Email restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { restored = (Email) input.readObject(); }
        for (final Email email : List.of(MAIL.emailBuilder().copying(imported).buildEmail(), restored)) {
            assertThat(email.getFromRecipient()).isNull();
            assertThat(email.getReplyToRecipients()).isEmpty();
            assertThat(email.getPlainText()).isEqualTo("Readable body");
            assertThat(email.getProblems()).hasSameSizeAs(imported.getProblems());
            for (int index = 0; index < email.getProblems().size(); index++) {
                final EmailProblem actual = email.getProblems().get(index);
                final EmailProblem original = imported.getProblems().get(index);
                assertThat(actual.getOperation()).isEqualTo(original.getOperation());
                assertThat(actual.getCode()).isEqualTo(original.getCode());
                assertThat(actual.getLocation()).isEqualTo(original.getLocation());
                assertThat(actual.getMessage()).isEqualTo(original.getMessage());
                assertThat(actual.getOrigin().getReference()).isEqualTo(original.getOrigin().getReference());
                assertThat(actual.getSources()).isEmpty();
            }
            assertThat(email.getSourceData().getOrigin().getReference()).isEqualTo(imported.getSourceData().getOrigin().getReference());
        }
        assertThat(reads).hasValue(selectedReads);
    }

    private static MimeMessage addressInput(final String content, final String field, final boolean captureFails,
            final MessagingException failure) throws MessagingException {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(content.getBytes(UTF_8))) {
            @Override public Address[] getFrom() throws MessagingException {
                if (field.equals("From")) throw failure;
                return super.getFrom();
            }

            @Override public Address[] getReplyTo() throws MessagingException {
                if (field.equals("Reply-To")) throw failure;
                return super.getReplyTo();
            }

            @Override public String getHeader(final String name, final String delimiter) throws MessagingException {
                if (field.equals(name) && (field.equals("To") || field.equals("Cc") || field.equals("Bcc"))) throw failure;
                return super.getHeader(name, delimiter);
            }

            @Override public Enumeration<Header> getAllHeaders() throws MessagingException {
                if (captureFails) throw new MessagingException("source headers unavailable");
                return super.getAllHeaders();
            }
        };
    }

    private static Stream<Arguments> missingAddressCases() {
        return Stream.of("From", "Reply-To", "To", "Cc", "Bcc").flatMap(field -> Stream.of(false, true).flatMap(captureFails ->
                Stream.of(false, true).flatMap(staticImport -> Stream.of(false, true).map(grammarFailure ->
                        Arguments.of(field, captureFails, staticImport, grammarFailure)))));
    }

    private static Stream<Arguments> metadataCases() {
        return Stream.of("Content-ID", "filename", "Content-Disposition", "Content-Description")
                .flatMap(field -> Stream.of(false, true).map(eager -> Arguments.of(field, eager)));
    }

    private static List<String> addresses(final Email email, final String field) {
        switch (field) {
            case "From": return email.getFromRecipient() == null ? Collections.emptyList()
                    : Collections.singletonList(email.getFromRecipient().getAddress());
            case "Reply-To": return email.getReplyToRecipients().stream().map(Recipient::getAddress).collect(java.util.stream.Collectors.toList());
            case "To": return email.getToRecipients().stream().map(Recipient::getAddress).collect(java.util.stream.Collectors.toList());
            case "Cc": return email.getCcRecipients().stream().map(Recipient::getAddress).collect(java.util.stream.Collectors.toList());
            case "Bcc": return email.getBccRecipients().stream().map(Recipient::getAddress).collect(java.util.stream.Collectors.toList());
            default: throw new AssertionError(field);
        }
    }
}
