package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.config.ContentEncodingConfig;
import org.simplejavamail.converter.EmailConverter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.simplejavamail.internal.smimesupport.SmimeInspectionAssertions.assertOriginal;

/** Calendar method, text and encoding follow one selected received representation. */
class RetainedCalendarMergeTest {
    private static Path resources;
    private static byte[] msg;
    private static byte[] eml;

    @BeforeAll
    static void prepareRealSignedEncryptedMsg() throws Exception {
        resources = Path.of(determineResourceFolder("simple-java-mail"), "test/resources");
        eml = ProtectedCalendarFixture.encryptedEml(resources);
        msg = ProtectedCalendarFixture.outlook(resources, eml);
    }

    @ParameterizedTest(name = "native protected calendar, fluent={0}")
    @ValueSource(booleans = {true, false})
    void nativeMsgImportSelectsTheProtectedCalendarAsOneUnit(boolean fluent) {
        Email result = read(fluent).buildEmail();
        assertCalendarProjection(result);
        assertProtectedCalendar(result);
        assertOriginal(result, msg);
    }

    @ParameterizedTest(name = "present empty calendar, protected={0}")
    @ValueSource(booleans = {false, true})
    void presentEmptyCalendarDoesNotBecomeAnAbsentAlternative(boolean protectedInput) throws Exception {
        byte[] raw = ("From: sender@example.org\r\nContent-Type: text/calendar; method=REQUEST; charset=utf-8\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        Email result;
        if (protectedInput) {
            MimeMessage clear = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw));
            result = ProtectedCalendarFixture.MAIL.converter().fromEml(ProtectedCalendarFixture.encryptedMessage(resources, clear))
                    .withSmimeDecryption(ProtectedCalendarFixture.keys(resources)).toEmail();
        } else {
            result = ProtectedCalendarFixture.MAIL.converter().fromEml(raw).toEmail();
            assertThat(result.getCalendarTextContentTransferEncoding()).isNull();
        }
        assertThat(result.getCalendarText()).isEmpty();
        assertThat(result.getCalendarMethod()).isEqualTo(CalendarMethod.REQUEST);
        assertThat(result.getInspection().getCalendar().getAvailability()).isEqualTo(org.simplejavamail.api.email.ValueAvailability.PRESENT);
    }

    @ParameterizedTest(name = "ordinary calendar export, copied={0}")
    @ValueSource(booleans = {false, true})
    void ordinaryExportIncludesOneSelectedCalendarBody(boolean copied) throws Exception {
        Email received = read(true).buildEmail();
        if (copied) received = ProtectedCalendarFixture.MAIL.emailBuilder().copying(received).buildEmail();
        MimeMessage exported = ProtectedCalendarFixture.MAIL.converter().fromEmail(received).toMimeMessage();
        assertThat(calendarBodyParts(exported)).isEqualTo(1);
        assertCalendarProjection(ProtectedCalendarFixture.MAIL.converter().fromMimeMessage(exported).toEmail());
    }

    @Test
    void copiesAndSerializationRetainTheSelectedCalendarAndItsEvidenceWithoutReprocessing() throws Exception {
        Email current = roundTrip(read(true).buildEmail());
        for (int index = 0; index < 3; index++) {
            assertCalendarProjection(current);
            assertProtectedCalendar(current);
            assertOriginal(current, msg);
            current = ProtectedCalendarFixture.MAIL.emailBuilder().copying(current).buildEmail();
        }
    }

    @Test
    void equivalentEncryptedEmlExposesTheSameCalendarAndRetainsActualEmlInput() {
        Email received = ProtectedCalendarFixture.MAIL.converter().fromEml(eml)
                .withSmimeDecryption(ProtectedCalendarFixture.keys(resources)).toEmail();
        assertCalendarProjection(received);
        assertProtectedCalendar(received);
        assertOriginal(received, eml);
    }

    @Test
    void completeRetainedNativeOriginalCanBeReimportedWithoutReconstructingTheSignedContent() {
        Email received = read(true).buildEmail();
        byte[] original = received.getInspection().getOriginalMessage().readAllBytes();
        assertThat(original).containsExactly(msg);
        Email again = ProtectedCalendarFixture.MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(original))
                .withSmimeDecryption(ProtectedCalendarFixture.keys(resources)).toEmail();
        assertCalendarProjection(again);
        assertProtectedCalendar(again);
    }

    @Test
    void editsAndClearsOnACopyReplaceCurrentCalendarEvidenceWithoutResurrectingHistory() {
        Email received = read(true).buildEmail();
        EmailPopulatingBuilder copy = ProtectedCalendarFixture.MAIL.emailBuilder().copying(received);
        Email edited = copy.withCalendarText(CalendarMethod.CANCEL, "caller calendar")
                .withContentEncodingConfig(ContentEncodingConfig.builder()
                        .withCalendarTextContentTransferEncoding(ContentTransferEncoding.QUOTED_PRINTABLE).build()).buildEmail();
        assertThat(edited.getCalendarText()).isEqualTo("caller calendar");
        assertThat(edited.getCalendarMethod()).isEqualTo(CalendarMethod.CANCEL);
        assertThat(edited.getCalendarTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.QUOTED_PRINTABLE);
        assertThat(edited.getInspection().getCalendar().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertOriginal(edited, msg);
        Email cleared = copy.clearCalendarText().withContentEncodingConfig(ContentEncodingConfig.builder().build()).buildEmail();
        assertThat(cleared.getCalendarText()).isNull();
        assertThat(cleared.getCalendarMethod()).isNull();
        assertThat(cleared.getCalendarTextContentTransferEncoding()).isNull();
        assertOriginal(cleared, msg);
        assertCalendarProjection(received);
        assertProtectedCalendar(received);
    }

    private static int calendarBodyParts(Part part) throws Exception {
        if (part.isMimeType("text/calendar")) return 1;
        int count = 0;
        if (part.isMimeType("multipart/*")) {
            Multipart content = (Multipart) part.getContent();
            for (int index = 0; index < content.getCount(); index++) count += calendarBodyParts(content.getBodyPart(index));
        }
        return count;
    }

    private static Email roundTrip(Email email) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(email); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { return (Email) input.readObject(); }
    }

    private static EmailPopulatingBuilder read(boolean fluent) {
        return fluent ? ProtectedCalendarFixture.MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg))
                .withSmimeDecryption(ProtectedCalendarFixture.keys(resources)).toEmailBuilder()
                : EmailConverter.outlookMsgToEmailBuilder(new ByteArrayInputStream(msg), ProtectedCalendarFixture.keys(resources)).getEmailBuilder();
    }

    private static void assertProtectedCalendar(Email email) {
        assertThat(email.getInspection().getCalendar().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        assertThat(email.getInspection().getCalendar().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
    }

    private static void assertCalendarProjection(Email email) {
        assertAll("calendar text, method and encoding follow one selected representation",
                () -> assertThat(email.getCalendarText()).isEqualTo(ProtectedCalendarFixture.CALENDAR),
                () -> assertThat(email.getCalendarMethod()).isEqualTo(CalendarMethod.REQUEST),
                () -> assertThat(email.getCalendarTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64));
    }
}
