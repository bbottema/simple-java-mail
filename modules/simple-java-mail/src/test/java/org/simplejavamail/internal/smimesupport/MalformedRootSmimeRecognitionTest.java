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
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.EncryptionStatus;
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

        assertUnknown(email, contentType, emlInput);
        assertUnknown(mail.emailBuilder().copying(email).buildEmail(), contentType, emlInput);
    }

    private static Stream<Arguments> malformedImports() {
        return Stream.of("not-a-content-type", "application/pkcs7-mime; =broken")
                .flatMap(type -> Stream.of(true, false)
                        .flatMap(eml -> Stream.of(true, false).map(eager -> Arguments.of(type, eml, eager))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-content-type", "application/pkcs7-mime; =broken"})
    void staticImportsKeepTheSameUnknownFacts(final String contentType) throws Exception {
        assertUnknown(EmailConverter.emlToEmail(source(contentType)), contentType, true);
        for (boolean eager : new boolean[] {true, false}) {
            assertUnknown(EmailConverter.mimeMessageToEmail(mime(source(contentType)), null, eager), contentType, false);
        }
    }

    private static void assertUnknown(final Email email, final String contentType, final boolean originalCaptured) {
        assertThat(email.getInspection().getSmime().isInspectionComplete()).isFalse();
        assertThat(email.getInspection().getSmime().getSignatures()).isEmpty();
        assertThat(email.getInspection().getSmime().getDecryptions()).isEmpty();
        if (originalCaptured) SmimeInspectionAssertions.assertOriginal(email, source(contentType).getBytes(UTF_8));
        else assertThat(email.getInspection().getOriginalMessage()).isNull();
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
        assertThat(email.getInspection().getAttachment(0).getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(email.getInspection().getAttachment(0).getEncryptionStatus()).isEqualTo(EncryptionStatus.UNKNOWN);
    }

    private static String source(final String contentType) {
        return "From: sender@example.org\r\nTo: recipient@example.org\r\n"
                + "Content-Type: " + contentType + "\r\n\r\nOrdinary content";
    }

    private static MimeMessage mime(final String eml) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml.getBytes(UTF_8)));
    }
}
