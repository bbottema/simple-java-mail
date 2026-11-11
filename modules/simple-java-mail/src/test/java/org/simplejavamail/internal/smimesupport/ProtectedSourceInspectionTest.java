package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceData;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.converter.EmailConverter;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

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
    void openedSameMessageFindingsKeepTheirOwnOriginsAtTheRoot(boolean fluent) {
        Email email = fluent ? ProtectedCalendarFixture.MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg))
                .withSmimeDecryption(ProtectedCalendarFixture.keys(resources)).toEmail()
                : EmailConverter.outlookMsgToEmailBuilder(new ByteArrayInputStream(msg), ProtectedCalendarFixture.keys(resources)).buildEmail();
        assertThat(email.getSourceData().getFormat()).isEqualTo(EmailSourceData.Format.OUTLOOK_MSG);
        assertThat(email.getSourceData().getRepresentation()).isEqualTo(EmailSourceData.Representation.RECEIVED);
        assertThat(email.getSourceData().getDerivedSources()).isNotEmpty();
        assertThat(email.getPlainText()).contains("Readable feedback");
        assertThat(email.getDeliveryStatusReport()).isNotNull();
        assertThat(email.getDeliveryStatusReport().getWarnings()).isNotEmpty();
        final List<EmailSourceData> observed = sources(email.getSourceData());
        assertThat(email.getProblems()).anySatisfy(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.INTERPRETATION_WARNING);
            assertThat(problem.getSources()).isNotEmpty();
            assertThat(observed).anyMatch(source -> source.getOrigin() != null
                    && source.getOrigin().getReference().equals(problem.getOrigin().getReference()));
        });
        assertThat(email.getProblems()).noneMatch(problem ->
                "<generated-for-smime-signed-attachment@simple-java-mail>".equals(problem.getOrigin().getMessageId()));
        int count = email.getProblems().size();
        for (int index = 0; index < 3; index++) {
            email = ProtectedCalendarFixture.MAIL.emailBuilder().copying(email).fixingMessageId("<copy" + index + "@example.org>").buildEmail();
            assertThat(email.getProblems()).hasSize(count);
            SmimeInspectionAssertions.assertOriginal(email, msg);
        }
    }

    @Test void protectedEmlKeepsReceivedAndReadableSourceHeadersSeparate() {
        Email email = ProtectedCalendarFixture.MAIL.converter().fromEml(protectedEml)
                .withSmimeDecryption(ProtectedCalendarFixture.keys(resources)).toEmail();
        assertThat(email.getSourceData().getHeaders().stream().filter(header -> header.getName().equalsIgnoreCase("Content-Type"))
                .findFirst().orElseThrow().getRawValue()).containsIgnoringCase("application/pkcs7-mime");
        assertThat(email.getSourceData().getRepresentation()).isEqualTo(EmailSourceData.Representation.RECEIVED);
        assertThat(sources(email.getSourceData())).anySatisfy(source ->
                assertThat(source.getRepresentation()).isEqualTo(EmailSourceData.Representation.EXTRACTED));
        assertThat(email.getProblems()).isNotEmpty();
        assertThat(email.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        assertThat(email.getProblems()).noneMatch(problem -> problem.getCode() == EmailProblem.Code.SIGNATURE_INVALID);
        SmimeInspectionAssertions.assertOriginal(email, protectedEml);
    }

    private static List<EmailSourceData> sources(EmailSourceData root) {
        List<EmailSourceData> all = new ArrayList<>();
        all.add(root);
        for (EmailSourceData child : root.getDerivedSources()) all.addAll(sources(child));
        return all;
    }
}
