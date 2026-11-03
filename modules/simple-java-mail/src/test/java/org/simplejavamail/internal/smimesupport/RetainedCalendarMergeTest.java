package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.ContentEncodingConfig;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.internal.InternalEmailPopulatingBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Path;
import java.util.Properties;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.simplejavamail.api.email.OriginalSmimeDetails.DecryptionStatus.DECRYPTED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.SIGNED_ENCRYPTED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.VALID;

/** Calendar content follows the retained inner view when legacy S/MIME content merging is selected. */
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

    @ParameterizedTest(name = "default imported merge, fluent={0}")
    @ValueSource(booleans = {true, false})
    void defaultNativeMsgImportProjectsTheInnerCalendar(boolean fluent) throws Exception {
        Email result = read(fluent).buildEmail();
        assertRealProtectedInner(result);
        assertCalendarProjection(result);
    }

    @ParameterizedTest(name = "explicit copied merge reset, fluent={0}")
    @ValueSource(booleans = {true, false})
    void copiedSeparateViewProjectsCalendarWhenMergeIsExplicitlyRestored(boolean fluent) throws Exception {
        Email separate = read(fluent).notMergingSingleSMIMESignedAttachment().buildEmail();
        assertRealProtectedInner(separate);
        assertThat(separate.getCalendarText()).isNull();
        Email result = ProtectedCalendarFixture.MAIL.emailBuilder().copying(separate)
                .clearSMIMESignedAttachmentMergingBehavior().buildEmail();
        assertCalendarProjection(result);
    }

    @ParameterizedTest(name = "separate native view, fluent={0}")
    @ValueSource(booleans = {true, false})
    void explicitSeparateViewRetainsCalendarInTheInnerViewOnly(boolean fluent) throws Exception {
        Email separate = read(fluent).notMergingSingleSMIMESignedAttachment().buildEmail();
        assertRealProtectedInner(separate);
        assertThat(separate.getCalendarText()).isNull();
        assertThat(separate.getCalendarMethod()).isNull();
        assertThat(separate.getSmimeSignedEmail().getCalendarText()).isEqualTo(ProtectedCalendarFixture.CALENDAR);
    }

    @ParameterizedTest(name = "empty calendar and unset inner encoding, copied reset={0}")
    @ValueSource(booleans = {false, true})
    void anEmptyInnerCalendarIsPresentAndItsOmittedEncodingClearsTheOuterChoice(boolean copied) {
        Email result = mergeCalendar("", null, copied);
        assertAll("empty calendar is present; omitted encoding is a real selection",
                () -> assertThat(result.getCalendarText()).isEmpty(),
                () -> assertThat(result.getCalendarMethod()).isEqualTo(CalendarMethod.REQUEST),
                () -> assertThat(result.getCalendarTextContentTransferEncoding()).isNull());
    }

    @ParameterizedTest(name = "conflicting calendar values, copied reset={0}")
    @ValueSource(booleans = {false, true})
    void anInnerCalendarReplacesTheOuterCalendarAndSpecificEncodingTogether(boolean copied) {
        assertCalendarProjection(mergeCalendar(ProtectedCalendarFixture.CALENDAR, ContentTransferEncoding.BASE_64, copied));
    }

    @ParameterizedTest(name = "ordinary exported calendar, copied reset={0}")
    @ValueSource(booleans = {false, true})
    void ordinaryExportIncludesTheSelectedCalendarBody(boolean copied) throws Exception {
        MimeMessage exported = ProtectedCalendarFixture.MAIL.converter().fromEmail(merged(copied)).toMimeMessage();
        assertThat(calendarBodyParts(exported)).isEqualTo(1);
        assertCalendarProjection(ProtectedCalendarFixture.MAIL.converter().fromMimeMessage(exported).toEmail());
    }

    @ParameterizedTest(name = "copied and serialized selected calendar, copied reset={0}")
    @ValueSource(booleans = {false, true})
    void copiesAndSerializationRetainTheSelectedCalendarWithoutRemerging(boolean copied) throws Exception {
        Email current = roundTrip(merged(copied));
        for (int i = 0; i < 3; i++) {
            assertCalendarProjection(current);
            assertThat(current.getSmimeSignedEmail().getCalendarText()).isEqualTo(ProtectedCalendarFixture.CALENDAR);
            current = ProtectedCalendarFixture.MAIL.emailBuilder().copying(current).buildEmail();
        }
    }

    @Test
    void equivalentEncryptedEmlAlreadyExposesTheCalendar() {
        Email converted = ProtectedCalendarFixture.MAIL.converter().fromEml(eml)
                .withSmimeDecryption(ProtectedCalendarFixture.keys(resources)).toEmail();
        assertThat(converted.getCalendarText()).isEqualTo(ProtectedCalendarFixture.CALENDAR);
        assertThat(converted.getCalendarMethod()).isEqualTo(CalendarMethod.REQUEST);
    }

    @Test
    void theRetainedSignedRepresentationIndependentlyVerifiesAndKeepsItsCalendar() throws Exception {
        Email merged = read(true).buildEmail();
        AttachmentResource retained = merged.getDecryptedAttachments().stream()
                .filter(r -> "signed-email.eml".equals(r.getName())).findFirst().orElseThrow();
        byte[] bytes = retained.readAllBytes();
        assertThat(SmimeCryptoSupport.checkSignature(new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(bytes)))).isTrue();
        assertThat(ProtectedCalendarFixture.MAIL.converter().fromEml(bytes).toEmail().getCalendarText())
                .isEqualTo(ProtectedCalendarFixture.CALENDAR);
    }

    @Test
    void editsAndClearsAfterExplicitCopiedMergeRemainAuthoritative() throws Exception {
        Email separate = read(true).notMergingSingleSMIMESignedAttachment().buildEmail();
        EmailPopulatingBuilder copy = ProtectedCalendarFixture.MAIL.emailBuilder().copying(separate)
                .clearSMIMESignedAttachmentMergingBehavior();
        Email edited = copy.withCalendarText(CalendarMethod.CANCEL, "caller calendar")
                .withContentEncodingConfig(ContentEncodingConfig.builder()
                        .withCalendarTextContentTransferEncoding(ContentTransferEncoding.QUOTED_PRINTABLE).build()).buildEmail();
        assertThat(edited.getCalendarText()).isEqualTo("caller calendar");
        assertThat(edited.getCalendarMethod()).isEqualTo(CalendarMethod.CANCEL);
        assertThat(edited.getCalendarTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.QUOTED_PRINTABLE);
        Email cleared = copy.clearCalendarText().withContentEncodingConfig(ContentEncodingConfig.builder().build())
                .clearSMIMESignedAttachmentMergingBehavior().buildEmail();
        assertThat(cleared.getCalendarText()).isNull();
        assertThat(cleared.getCalendarMethod()).isNull();
        assertThat(cleared.getCalendarTextContentTransferEncoding()).isNull();
        assertThat(cleared.getSmimeSignedEmail().getCalendarText()).isEqualTo(ProtectedCalendarFixture.CALENDAR);
    }

    private static Email mergeCalendar(String text, ContentTransferEncoding encoding, boolean copied) {
        ContentEncodingConfig.Builder innerEncoding = ContentEncodingConfig.builder();
        if (encoding != null) innerEncoding.withCalendarTextContentTransferEncoding(encoding);
        Email inner = ProtectedCalendarFixture.MAIL.emailBuilder().startingBlank()
                .withCalendarText(CalendarMethod.REQUEST, text)
                .withContentEncodingConfig(innerEncoding.build())
                .buildEmail();
        InternalEmailPopulatingBuilder outer = (InternalEmailPopulatingBuilder) ProtectedCalendarFixture.MAIL.emailBuilder().startingBlank();
        outer.withCalendarText(CalendarMethod.CANCEL, "outer calendar")
                .withContentEncodingConfig(ContentEncodingConfig.builder()
                        .withCalendarTextContentTransferEncoding(ContentTransferEncoding.QUOTED_PRINTABLE).build());
        outer.withSmimeSignedEmail(inner);
        if (!copied) return outer.buildEmail();
        Email separate = outer.notMergingSingleSMIMESignedAttachment().buildEmail();
        return ProtectedCalendarFixture.MAIL.emailBuilder().copying(separate)
                .clearSMIMESignedAttachmentMergingBehavior().buildEmail();
    }

    private static Email merged(boolean copied) {
        if (!copied) return read(true).buildEmail();
        Email separate = read(true).notMergingSingleSMIMESignedAttachment().buildEmail();
        return ProtectedCalendarFixture.MAIL.emailBuilder().copying(separate)
                .clearSMIMESignedAttachmentMergingBehavior().buildEmail();
    }

    private static int calendarBodyParts(Part part) throws Exception {
        if (part.isMimeType("text/calendar")) return 1;
        int count = 0;
        if (part.isMimeType("multipart/*")) {
            Multipart content = (Multipart) part.getContent();
            for (int i = 0; i < content.getCount(); i++) count += calendarBodyParts(content.getBodyPart(i));
        }
        return count;
    }

    private static Email roundTrip(Email email) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(email); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (Email) input.readObject();
        }
    }

    private static EmailPopulatingBuilder read(boolean fluent) {
        return fluent ? ProtectedCalendarFixture.MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg))
                .withSmimeDecryption(ProtectedCalendarFixture.keys(resources)).toEmailBuilder()
                : EmailConverter.outlookMsgToEmailBuilder(new ByteArrayInputStream(msg), ProtectedCalendarFixture.keys(resources)).getEmailBuilder();
    }

    private static void assertRealProtectedInner(Email outer) {
        assertThat(outer.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(SIGNED_ENCRYPTED);
        assertThat(outer.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(DECRYPTED);
        assertThat(outer.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(VALID);
        assertThat(outer.getSmimeSignedEmail()).isNotNull();
        assertThat(outer.getSmimeSignedEmail().getCalendarText()).isEqualTo(ProtectedCalendarFixture.CALENDAR);
        assertThat(outer.getSmimeSignedEmail().getCalendarMethod()).isEqualTo(CalendarMethod.REQUEST);
        assertThat(outer.getSmimeSignedEmail().getCalendarTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
    }

    private static void assertCalendarProjection(Email outer) {
        assertAll("calendar text, method and encoding must follow the selected inner calendar",
                () -> assertThat(outer.getCalendarText()).isEqualTo(ProtectedCalendarFixture.CALENDAR),
                () -> assertThat(outer.getCalendarMethod()).isEqualTo(CalendarMethod.REQUEST),
                () -> assertThat(outer.getCalendarTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64));
    }
}
