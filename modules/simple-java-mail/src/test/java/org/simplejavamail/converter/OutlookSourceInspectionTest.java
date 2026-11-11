package org.simplejavamail.converter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailSourceData;
import org.simplejavamail.api.email.EmailSourceHeader;
import org.simplejavamail.api.email.EmailSourceProperty;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.config.ConfigLoader;

import java.io.*;
import java.nio.file.*;

import static org.assertj.core.api.Assertions.*;

class OutlookSourceInspectionTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    enum Route { STATIC_FILE, STATIC_STREAM, FLUENT_FILE, FLUENT_PATH, FLUENT_STREAM, RESULT }
    @TempDir Path directory;

    @ParameterizedTest @EnumSource(Route.class)
    void everyEntryPointRetainsNativeMetadataAndOrderedHeaders(Route route) throws Exception {
        byte[] msg = OutlookAddressImportTest.withAddressHeader("Received", "first\r\nComments: =?X-NO-SUCH-CHARSET?Q?note?=\r\nreceived: second");
        Path file = directory.resolve("source.msg");
        Files.write(file, msg);
        Email email;
        switch (route) {
            case STATIC_FILE: email = EmailConverter.outlookMsgToEmail(file.toFile()); break;
            case STATIC_STREAM: email = EmailConverter.outlookMsgToEmail(new ByteArrayInputStream(msg)); break;
            case FLUENT_FILE: email = MAIL.converter().fromOutlookMsg(file.toFile()).toEmail(); break;
            case FLUENT_PATH: email = MAIL.converter().fromOutlookMsg(file).toEmail(); break;
            case FLUENT_STREAM: email = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail(); break;
            default:
                OutlookEmailConversionResult result = MAIL.converter().fromOutlookMsg(file).toEmailBuilderWithOutlookData();
                email = result.buildEmail();
                assertThat(result.getOutlookMessageData().getRawHeaders()).contains("Received: first", "received: second");
                assertThat(result.getOutlookMessageData().getPropertyCodes()).isNotEmpty();
        }
        assertThat(headerValues(email.getSourceData(), "Received")).containsExactly("first", "second");
        assertThat(headerValues(email.getSourceData(), "Comments")).containsExactly("=?X-NO-SUCH-CHARSET?Q?note?=");
        assertThat(email.getSourceData().getFormat()).isEqualTo(EmailSourceData.Format.OUTLOOK_MSG);
        assertThat(headerValues(email.getSourceData(), "Received")).containsExactly("first", "second");
        assertThat(propertyValues(email.getSourceData(), "propertyCode")).isNotEmpty();
        assertThat(email.getHeaders().get("received")).containsExactly("first", "second");
        assertThat(email.getHeaders().get("COMMENTS")).containsExactly("=?X-NO-SUCH-CHARSET?Q?note?=");
        Email copy = MAIL.emailBuilder().copying(email).fixingMessageId("<copy@example.org>").buildEmail();
        assertThat(headerValues(copy.getSourceData(), "Received")).containsExactly("first", "second");
    }

    @Test void serializedMetadataHasNoOptionalOutlookImplementationOrParserOwner() throws Exception {
        byte[] msg = OutlookAddressImportTest.withAddressHeader("Comments", "inspect me");
        Email email = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(email); }
        Files.write(Path.of("target/email-source-portability.bin"), bytes.toByteArray());
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
            @Override protected Class<?> resolveClass(ObjectStreamClass descriptor) throws IOException, ClassNotFoundException {
                if (descriptor.getName().startsWith("org.simplejavamail.internal.outlooksupport.")
                        || descriptor.getName().startsWith("org.simplejavamail.outlookmessageparser."))
                    throw new ClassNotFoundException("Optional Outlook implementation is unavailable: " + descriptor.getName());
                return super.resolveClass(descriptor);
            }
        }) {
            Email restored = (Email) input.readObject();
            assertThat(headerValues(restored.getSourceData(), "Comments")).containsExactly("inspect me");
            assertThat(propertyValues(restored.getSourceData(), "propertyCode"))
                    .isEqualTo(propertyValues(email.getSourceData(), "propertyCode"));
        }
    }

    private static java.util.List<String> headerValues(EmailSourceData source, String name) {
        return source.getHeaders().stream().filter(header -> header.getName().equalsIgnoreCase(name))
                .map(EmailSourceHeader::getRawValue).collect(java.util.stream.Collectors.toList());
    }

    private static java.util.List<String> propertyValues(EmailSourceData source, String name) {
        return source.getProperties().stream().filter(property -> property.getName().equals(name))
                .map(EmailSourceProperty::getValue).collect(java.util.stream.Collectors.toList());
    }
}
