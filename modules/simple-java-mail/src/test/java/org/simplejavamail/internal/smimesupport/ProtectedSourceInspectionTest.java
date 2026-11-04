package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.email.*;
import org.simplejavamail.api.internal.smimesupport.builder.SmimeParseResult;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Properties;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

class ProtectedSourceInspectionTest {
    private static Path resources;
    private static byte[] protectedEml;
    private static byte[] msg;

    @BeforeAll static void fixture() throws Exception {
        resources = Path.of(determineResourceFolder("simple-java-mail"), "test/resources");
        String report = "Message-ID: <report@example.org>\r\nFrom: postmaster@example.org\r\nTo: sender@example.org\r\n"
                + "Subject: Report\r\nMIME-Version: 1.0\r\nContent-Type: multipart/report; report-type=delivery-status; boundary=r\r\n\r\n"
                + "--r\r\nContent-Type: text/plain\r\n\r\nReadable feedback\r\n--r\r\nContent-Type: message/delivery-status\r\n\r\n"
                + "Reporting-MTA: dns; relay.example.org\r\n\r\nFinal-Recipient: rfc822; reader@example.org\r\nAction: unrecognized\r\nStatus: 5.1.1\r\n\r\n--r--\r\n";
        MimeMessage plain = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(report.getBytes(UTF_8)));
        protectedEml = ProtectedCalendarFixture.encryptedMessage(resources, plain);
        msg = ProtectedCalendarFixture.outlook(resources, protectedEml);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void retainedInnerFindingsAreDiscoverableAtTheRootWithTheirOwnOrigins(boolean merge) {
        EmailPopulatingBuilder builder = ProtectedCalendarFixture.MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg))
                .withSmimeDecryption(ProtectedCalendarFixture.keys(resources)).toEmailBuilder();
        if (!merge) builder.notMergingSingleSMIMESignedAttachment();
        Email email = builder.buildEmail();
        Email inner = email.getSmimeSignedEmail();
        assertThat(inner).isNotNull();
        assertThat(email.getSourceData().getFormat()).isEqualTo(EmailSourceData.Format.OUTLOOK_MSG);
        assertThat(email.getSourceData().getRepresentation()).isEqualTo(EmailSourceData.Representation.RECEIVED);
        assertThat(inner.getSourceData().getRepresentation()).isEqualTo(EmailSourceData.Representation.GENERATED);
        assertThat(email.getSourceData().getDerivedSources()).anySatisfy(source -> assertThat(source).isSameAs(inner.getSourceData()));
        assertThat(inner.getDeliveryStatusReport().getWarnings()).isNotEmpty();
        assertThat(email.getDeliveryStatusReport()).isNull();
        assertThat(inner.getProblems()).isNotEmpty();
        assertThat(email.getProblems()).anySatisfy(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.INTERPRETATION_WARNING);
            assertThat(problem.getLocation()).startsWith("source/derived[");
            assertThat(problem.getOrigin().getReference()).isEqualTo(inner.getProblems().get(0).getOrigin().getReference());
        });
        assertThat(email.getProblems()).noneMatch(problem -> SmimeParseResult.GENERATED_MESSAGE_ID.equals(problem.getOrigin().getMessageId()));
        int count = email.getProblems().size();
        for (int i = 0; i < 3; i++) {
            email = ProtectedCalendarFixture.MAIL.emailBuilder().copying(email).fixingMessageId("<copy" + i + "@example.org>").buildEmail();
            assertThat(email.getProblems()).hasSize(count);
        }
    }

    @Test void protectedEmlKeepsReceivedAndReadableSourceHeadersSeparate() {
        Email email = ProtectedCalendarFixture.MAIL.converter().fromEml(protectedEml)
                .withSmimeDecryption(ProtectedCalendarFixture.keys(resources)).toEmail();
        assertThat(email.getSourceData().getHeaders().stream().filter(header -> header.getName().equalsIgnoreCase("Content-Type")).findFirst().orElseThrow().getRawValue()).containsIgnoringCase("application/pkcs7-mime");
        assertThat(email.getSourceData().getDerivedSources()).isNotEmpty();
        assertThat(email.getSourceData().getRepresentation()).isEqualTo(EmailSourceData.Representation.RECEIVED);
        assertThat(email.getSourceData().getDerivedSources()).anySatisfy(source ->
                assertThat(source.getRepresentation()).isEqualTo(EmailSourceData.Representation.EXTRACTED));
        assertThat(email.getProblems()).isNotEmpty();
        assertThat(email.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.VALID);
        assertThat(email.getProblems()).noneMatch(problem -> problem.getCode() == EmailProblem.Code.SIGNATURE_INVALID);
    }
}
