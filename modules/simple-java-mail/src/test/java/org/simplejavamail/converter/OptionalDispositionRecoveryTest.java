package org.simplejavamail.converter;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.ParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.MailException;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;

import java.io.ByteArrayInputStream;
import java.io.InterruptedIOException;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptionalDispositionRecoveryTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @ParameterizedTest
    @MethodSource("knownAbsenceCases")
    void knownAbsentDispositionCanRecoverAGetterParseFailure(final boolean emptyArray, final boolean eager) throws Exception {
        final ParseException failure = new ParseException("optional disposition unavailable");
        final MimeBodyPart resource = new MimeBodyPart(new ByteArrayInputStream(
                "Content-Type: application/octet-stream\r\n\r\nReadable resource".getBytes(UTF_8))) {
            @Override public String[] getHeader(final String name) throws MessagingException {
                return name.equalsIgnoreCase("Content-Disposition") && emptyArray ? new String[0] : super.getHeader(name);
            }
            @Override public String getDisposition() throws MessagingException { throw failure; }
        };

        final Email email = MAIL.converter().fromMimeMessage(message(resource)).withFetchAttachmentData(eager).toEmail();

        assertThat(email.getPlainText()).isEqualTo("Readable parent");
        assertThat(email.getAttachments()).singleElement().satisfies(attachment ->
                assertThat(attachment.readAllBytes()).isEqualTo("Readable resource".getBytes(UTF_8)));
        assertThat(email.getProblems()).anySatisfy(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.FIELD_NOT_INTERPRETED);
            assertThat(problem.getLocation()).endsWith("/Content-Disposition");
            assertThat(problem.getSourceValue()).isNull();
            assertThat(problem.getMessage()).contains(failure.getMessage());
        });
    }

    @ParameterizedTest
    @MethodSource("unavailableBoundaryCases")
    void unavailableDispositionCannotPromoteAnAttachedMultipart(final String boundary, final boolean eager) throws Exception {
        final MessagingException failure = new MessagingException("disposition reader unavailable");
        final String declaration = boundary.equals("UNREADABLE") ? ""
                : boundary.equals("MULTIPLE") ? "Content-Disposition: attachment\r\nContent-Disposition: attachment\r\n"
                : boundary.equals("MALFORMED") ? "Content-Disposition: undecidable (\r\n" : "Content-Disposition: attachment\r\n";
        final String nested = "Content-Type: multipart/mixed; boundary=inner\r\n" + declaration
                + "\r\n--inner\r\nContent-Type: text/plain\r\n\r\nInner content must not become the parent body\r\n--inner--\r\n";
        final MimeBodyPart resource = new MimeBodyPart(new ByteArrayInputStream(nested.getBytes(UTF_8))) {
            @Override public String[] getHeader(final String name) throws MessagingException {
                if (name.equalsIgnoreCase("Content-Disposition")) {
                    if (boundary.equals("UNREADABLE")) throw failure;
                    if (boundary.equals("CAPTURED_ONLY")) return null;
                }
                return super.getHeader(name);
            }
            @Override public String getDisposition() throws MessagingException { throw failure; }
        };

        final Email email = MAIL.converter().fromMimeMessage(message(resource)).withFetchAttachmentData(eager).toEmail();

        assertThat(email.getPlainText()).isEqualTo("Readable parent");
        assertThat(email.getAttachments()).isEmpty();
        assertThat(email.getProblems()).isNotEmpty();
    }

    @Test
    void directDispositionHelperKeepsItsExceptionContract() throws Exception {
        final MessagingException failure = new MessagingException("direct metadata failure");
        final MimeBodyPart resource = new MimeBodyPart() {
            @Override public String getDisposition() throws MessagingException { throw failure; }
        };

        assertThatThrownBy(() -> MimeMessageParser.parseDisposition(resource)).isInstanceOf(MailException.class)
                .satisfies(thrown -> assertThat(thrown.getCause()).isSameAs(failure));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUNTIME", "ERROR", "CANCELLATION"})
    void unrelatedApplicationFailuresDoNotBecomeUnavailableMetadata(final String kind) throws Exception {
        final Throwable failure = kind.equals("ERROR") ? new AssertionError("application error")
                : kind.equals("CANCELLATION") ? new CancellationException("application canceled")
                : new IllegalStateException("application failure");
        final MimeBodyPart resource = new MimeBodyPart(new ByteArrayInputStream(
                "Content-Type: application/octet-stream\r\n\r\nReadable resource".getBytes(UTF_8))) {
            @Override public String getDisposition() {
                if (failure instanceof Error) throw (Error) failure;
                throw (RuntimeException) failure;
            }
        };

        assertThatThrownBy(() -> MAIL.converter().fromMimeMessage(message(resource)).toEmail()).isSameAs(failure);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptedDispositionReadRetainsTypedCancellationAndExistingFlag(final boolean providerInterrupts) throws Exception {
        final InterruptedIOException interruption = new InterruptedIOException("disposition interrupted");
        final MimeBodyPart resource = new MimeBodyPart(new ByteArrayInputStream(
                "Content-Type: application/octet-stream\r\n\r\nReadable resource".getBytes(UTF_8))) {
            @Override public String getDisposition() throws MessagingException {
                if (providerInterrupts) Thread.currentThread().interrupt();
                throw new MessagingException("metadata interrupted", interruption);
            }
        };
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        try {
            assertThatThrownBy(() -> MAIL.converter().fromMimeMessage(message(resource)).toEmail())
                    .isInstanceOf(CancellationException.class).hasRootCauseInstanceOf(InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(providerInterrupts);
        } finally {
            Thread.interrupted();
        }
    }

    private static MimeMessage message(final MimeBodyPart resource) throws MessagingException {
        final MimeBodyPart body = new MimeBodyPart();
        body.setText("Readable parent", "UTF-8");
        final MimeMultipart parts = new MimeMultipart("mixed");
        parts.addBodyPart(body);
        parts.addBodyPart(resource);
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setContent(parts);
        return message;
    }

    private static Stream<Arguments> knownAbsenceCases() {
        return Stream.of(false, true).flatMap(empty -> Stream.of(false, true).map(eager -> Arguments.of(empty, eager)));
    }

    private static Stream<Arguments> unavailableBoundaryCases() {
        return Stream.of("PRESENT", "CAPTURED_ONLY", "UNREADABLE", "MULTIPLE", "MALFORMED")
                .flatMap(boundary -> Stream.of(false, true).map(eager -> Arguments.of(boundary, eager)));
    }
}
