package org.simplejavamail.converter;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.ValueAvailability;
import org.simplejavamail.api.email.ValueDerivation;
import org.simplejavamail.api.email.ValueOrigin;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class OutlookReadableBodyInspectionTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final String RTF_TEXT = "{\\rtf1\\ansi\\fromtext Native text\\par second line}";

    @Test
    void sourceBodiesRemainNativeWhileSelectedFieldsAndCopiesHaveTheirOwnFacts() throws Exception {
        final byte[] msg = minimalMsg("<p>Native HTML</p>", RTF_TEXT, "Native text");
        final OutlookEmailConversionResult converted = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg))
                .toEmailBuilderWithOutlookData();
        final Email received = converted.buildEmail();
        assertThat(converted.getOutlookMessageData().getBodyHTML()).isEqualTo("<p>Native HTML</p>");
        assertThat(converted.getOutlookMessageData().getBodyRTF()).isEqualTo(RTF_TEXT);
        assertThat(received.getHTMLText()).isEqualTo("<p>Native HTML</p>");
        assertThat(received.getInspection().getHTMLText().getOrigin()).isEqualTo(ValueOrigin.RECEIVED);
        assertThat(received.getInspection().getHTMLText().getDerivation()).isEqualTo(ValueDerivation.NATIVE_HTML);
        assertThat(received.getInspection().getOriginalMessage().readAllBytes()).isEqualTo(msg);
        final Email copy = MAIL.emailBuilder().copying(received).buildEmail();
        assertThat(copy.getInspection().getHTMLText()).isSameAs(received.getInspection().getHTMLText());
        assertThat(copy.getInspection().getOriginalMessage()).isSameAs(received.getInspection().getOriginalMessage());
        final Email edited = MAIL.emailBuilder().copying(received).withHTMLText("<p>Native HTML</p>").buildEmail();
        assertThat(edited.getInspection().getHTMLText().getOrigin()).isEqualTo(ValueOrigin.CALLER_SUPPLIED);
        assertThat(edited.getInspection().getOriginalMessage().readAllBytes()).isEqualTo(msg);
        assertThat(converted.getOutlookMessageData().getBodyHTML()).isEqualTo("<p>Native HTML</p>");
        assertThat(converted.getOutlookMessageData().getBodyRTF()).isEqualTo(RTF_TEXT);
    }

    @Test
    void presentEmptyHtmlDoesNotSelectTheRtfAlternative() throws Exception {
        final Email received = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(minimalMsg("", RTF_TEXT, "Native text"))).toEmail();
        assertThat(received.getHTMLText()).isEmpty();
        assertThat(received.getInspection().getHTMLText().getAvailability()).isEqualTo(ValueAvailability.PRESENT);
        assertThat(received.getInspection().getHTMLText().getDerivation()).isEqualTo(ValueDerivation.NATIVE_HTML);
        final Email absent = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(minimalMsg(null, null, null))).toEmail();
        assertThat(absent.getHTMLText()).isNull();
        assertThat(absent.getInspection().getHTMLText().getAvailability()).isEqualTo(ValueAvailability.ABSENT);
    }

    @Test
    void encapsulatedTextDerivationDescribesTheActualRenderedAlternative() throws Exception {
        final OutlookEmailConversionResult converted = MAIL.converter().fromOutlookMsg(
                new ByteArrayInputStream(minimalMsg(null, RTF_TEXT, "Native text"))).toEmailBuilderWithOutlookData();
        assertThat(converted.getOutlookMessageData().getBodyHTML()).isNull();
        assertThat(converted.getOutlookMessageData().getBodyRTF()).isEqualTo(RTF_TEXT);
        final Email email = converted.buildEmail();
        assertThat(email.getHTMLText()).contains("Native text", "second line");
        assertThat(email.getInspection().getHTMLText().getDerivation()).isEqualTo(ValueDerivation.RTF_ENCAPSULATED_TEXT);
        assertThat(email.getInspection().getHTMLText().getOrigin()).isEqualTo(ValueOrigin.DERIVED);
    }

    @ParameterizedTest
    @CsvSource({"'simple email with TO and CC.msg',RTF_ENCAPSULATED_HTML,EXTRACTED",
            "'#576 RtfSampleEmail.msg',RTF_RENDERED_HTML,DERIVED"})
    void readerDerivedHtmlRetainsItsRealNativeSource(final String fixture, final ValueDerivation derivation,
            final ValueOrigin origin) throws Exception {
        final byte[] msg = fixture(fixture);
        final OutlookEmailConversionResult converted = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg))
                .toEmailBuilderWithOutlookData();
        final Email email = converted.buildEmail();
        assertThat(converted.getOutlookMessageData().getBodyHTML()).isNull();
        assertThat(converted.getOutlookMessageData().getBodyRTF()).startsWith("{\\rtf");
        assertThat(email.getHTMLText()).isNotEmpty().doesNotContain("{\\rtf");
        assertThat(email.getInspection().getHTMLText().getDerivation()).isEqualTo(derivation);
        assertThat(email.getInspection().getHTMLText().getOrigin()).isEqualTo(origin);
    }

    @Test
    void nativeOriginalForwardPreservesMsgFormatAndPayloadAcrossSnapshot() throws Exception {
        final byte[] msg = minimalMsg("<p>Original native content</p>", RTF_TEXT, "Original text");
        final Email received = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        final Email edited = MAIL.emailBuilder().copying(received).withPlainText("Edited current view").buildEmail();
        final ByteArrayOutputStream snapshot = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(snapshot)) { output.writeObject(edited); }
        final Email restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(snapshot.toByteArray()))) {
            restored = (Email) input.readObject();
        }
        final Email forward = MAIL.emailBuilder().forwardingOriginal(restored).from("reader@example.org").to("archive@example.org").buildEmail();
        final Email output = MAIL.converter().fromEml(MAIL.converter().fromEmail(forward).toEmlBytes()).toEmail();
        assertThat(output.getAttachments()).hasSize(1);
        final AttachmentResource attachment = output.getAttachments().get(0);
        assertThat(attachment.getName()).endsWith(".msg");
        assertThat(attachment.getDataSource().getContentType()).startsWith("application/vnd.ms-outlook");
        assertThat(attachment.readAllBytes()).isEqualTo(msg);
        assertThat(restored.getPlainText()).isEqualTo("Edited current view");
    }

    @Test
    void attachedNativeMessageIsMaterializedAndKeepsItsOwnChildren() throws Exception {
        final Email parent = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(
                fixture("#298 Email with nested msg with own attachment.msg"))).toEmail();
        assertThat(parent.getAttachments()).anySatisfy(attachment -> {
            assertThat(attachment.getEmail()).isNotNull();
            assertThat(attachment.getEmail().getAttachments()).isNotEmpty();
            assertThat(attachment.getEmail().getInspection().getOriginalMessage()).isNull();
            final byte[] before = attachment.readAllBytes();
            MAIL.emailBuilder().copying(attachment.getEmail()).withPlainText("Independent edit").buildEmail();
            assertThat(attachment.readAllBytes()).isEqualTo(before);
        });
    }

    private static byte[] fixture(final String name) throws Exception {
        try (InputStream input = OutlookReadableBodyInspectionTest.class.getResourceAsStream("/test-messages/" + name)) {
            return input.readAllBytes();
        }
    }

    private static byte[] minimalMsg(final String html, final String rtf, final String plain) throws Exception {
        try (POIFSFileSystem file = new POIFSFileSystem(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            string(file, "001A", "IPM.Note");
            string(file, "007D", "From: author@example.org\r\nTo: reader@example.org\r\nDate: Mon, 9 Nov 2026 10:00:00 +0000\r\n");
            if (plain != null) string(file, "1000", plain);
            if (html != null) string(file, "1013", html);
            if (rtf != null) {
                final byte[] bytes = rtf.getBytes(Charset.forName("windows-1252"));
                final byte[] property = ByteBuffer.allocate(bytes.length + 16).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(bytes.length + 12).putInt(bytes.length).putInt(0x414c454d).putInt(0).put(bytes).array();
                file.createDocument(new ByteArrayInputStream(property), "__substg1.0_10090102");
            }
            file.writeFilesystem(output);
            return output.toByteArray();
        }
    }

    private static void string(final POIFSFileSystem file, final String property, final String value) throws Exception {
        file.createDocument(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_16LE)), "__substg1.0_" + property + "001F");
    }
}
