package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.converter.EmailConverter;

import java.io.ByteArrayInputStream;
import java.util.Properties;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class MalformedRootSmimeRecognitionTest {

    @ParameterizedTest
    @MethodSource("malformedImports")
    void configuredImportsDoNotInventProtectionFromAMalformedRoot(final String contentType,
            final boolean emlInput, final boolean fetchAttachmentData) throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.fromDefaults();
        final String source = source(contentType);
        final Email email = emlInput
                ? mail.converter().fromEml(source).withFetchAttachmentData(fetchAttachmentData).toEmail()
                : mail.converter().fromMimeMessage(mime(source)).withFetchAttachmentData(fetchAttachmentData).toEmail();

        assertUnknown(email, contentType);
        assertUnknown(mail.emailBuilder().copying(email).buildEmail(), contentType);
    }

    private static Stream<Arguments> malformedImports() {
        return Stream.of("not-a-content-type", "application/pkcs7-mime; =broken")
                .flatMap(type -> Stream.of(true, false)
                        .flatMap(eml -> Stream.of(true, false).map(eager -> Arguments.of(type, eml, eager))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-content-type", "application/pkcs7-mime; =broken"})
    void staticImportsKeepTheSameUnknownFacts(final String contentType) throws Exception {
        assertUnknown(EmailConverter.emlToEmail(source(contentType)), contentType);
        for (boolean eager : new boolean[] {true, false}) {
            assertUnknown(EmailConverter.mimeMessageToEmail(mime(source(contentType)), null, eager), contentType);
        }
    }

    private static void assertUnknown(final Email email, final String contentType) {
        final OriginalSmimeDetails details = email.getOriginalSmimeDetails();
        assertThat(details.getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.UNKNOWN);
        assertThat(details.getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.UNKNOWN);
        assertThat(details.getDecryptionStatus()).isEqualTo(OriginalSmimeDetails.DecryptionStatus.UNKNOWN);
        assertThat(details.getSmimeSignatureValid()).isNull();
        assertThat(details.getFailureReason()).isNull();
        assertThat(details.getOriginalProtectedMessage()).isNull();
        assertThat(email.getProblems()).anySatisfy(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.FIELD_NOT_INTERPRETED);
            assertThat(problem.getLocation()).contains("Content-Type");
            assertThat(problem.getSourceValue()).isEqualTo(contentType);
            assertThat(problem.getSources()).isNotEmpty();
        }).noneMatch(problem -> problem.getCode() == EmailProblem.Code.PROTECTION_FAILED
                || problem.getCode() == EmailProblem.Code.SIGNATURE_INVALID);
        assertThat(email.getSourceData().getHeaders()).anySatisfy(header -> {
            assertThat(header.getName()).isEqualToIgnoringCase("Content-Type");
            assertThat(header.getRawValue()).isEqualTo(contentType);
        });
        assertThat(email.getAttachments()).singleElement().satisfies(resource ->
                assertThat(resource.readAllData(UTF_8)).isEqualTo("Ordinary content"));
    }

    private static String source(final String contentType) {
        return "From: sender@example.org\r\nTo: recipient@example.org\r\n"
                + "Content-Type: " + contentType + "\r\n\r\nOrdinary content";
    }

    private static MimeMessage mime(final String eml) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml.getBytes(UTF_8)));
    }
}
