package org.simplejavamail.converter;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.config.ConfigLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutlookMissingAttachmentTypeTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    private static final byte[] PAYLOAD = {0, 1, 2, 127, (byte) 128, (byte) 255};
    private static final byte[] NEIGHBOR = "Named neighboring attachment".getBytes(StandardCharsets.UTF_8);
    private static final String CID = "nameless@example.org";

    @TempDir Path directory;

    @ParameterizedTest
    @MethodSource("missingMetadataAndRoutes")
    void missingTypeAndNamesKeepReadableMsgContent(final Route route, final boolean inline, final String missing) throws Exception {
        final byte[] msg = msg(inline, missing, missing, missing, PAYLOAD);
        final Path path = Files.write(directory.resolve("missing-metadata.msg"), msg);
        final Email email = convert(route, path, msg);

        assertNeighboringContent(email);
        final AttachmentResource resource = importedResource(email, inline);
        assertThat(resource.getDataSource().getContentType()).isEqualTo("application/octet-stream");
        assertThat(resource.readAllBytes()).isEqualTo(PAYLOAD);
        assertThat(resource.getName()).isEqualTo(inline ? CID : missing);
        if (inline) assertThat(resource.getContentId()).isEqualTo(CID);

        final Email copy = MAIL.emailBuilder().copying(email).buildEmail();
        assertThat(importedResource(copy, inline).readAllBytes()).isEqualTo(PAYLOAD);
        assertThat(importedResource(copy, inline).getDataSource().getContentType()).isEqualTo("application/octet-stream");
    }

    static Stream<Arguments> missingMetadataAndRoutes() {
        return Arrays.stream(Route.values()).flatMap(route -> Stream.of(false, true)
                .flatMap(inline -> Stream.of((String) null, "").map(missing -> Arguments.of(route, inline, missing))));
    }

    @ParameterizedTest
    @MethodSource("availableTypes")
    void availableTypesAndFilenameInferenceKeepTheirMeaning(final boolean inline, final String type,
            final String shortName, final String longName, final String expectedType) throws Exception {
        final byte[] msg = msg(inline, type, shortName, longName, PAYLOAD);
        final Email email = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        final AttachmentResource resource = importedResource(email, inline);
        assertThat(resource.getDataSource().getContentType()).isEqualTo(expectedType);
        assertThat(resource.readAllBytes()).isEqualTo(PAYLOAD);
        assertNeighboringContent(email);
    }

    static Stream<Arguments> availableTypes() {
        return Stream.of(false, true).flatMap(inline -> Stream.of(
                Arguments.of(inline, "application/x-retained; version=1", null, null, "application/x-retained; version=1"),
                Arguments.of(inline, null, null, "payload.txt", "text/plain"),
                Arguments.of(inline, "", "payload.png", null, "image/png")));
    }

    @Test
    void emptyUnnamedBinaryAttachmentStillHasReadableEmptyContent() throws Exception {
        final byte[] msg = msg(false, null, null, null, new byte[0]);
        final Email email = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        assertThat(importedResource(email, false).readAllBytes()).isEmpty();
        assertThat(importedResource(email, false).getDataSource().getContentType()).isEqualTo("application/octet-stream");
        assertNeighboringContent(email);
    }

    @Test
    void fluentMsgImportStillBorrowsItsInputStream() throws Exception {
        final BorrowedInput input = new BorrowedInput(msg(false, null, null, null, PAYLOAD));
        final Email email = MAIL.converter().fromOutlookMsg(input).toEmail();
        assertThat(importedResource(email, false).readAllBytes()).isEqualTo(PAYLOAD);
        assertThat(input.closed).isFalse();
        input.close();
        assertThat(input.closed).isTrue();
    }

    @Test
    void ordinaryExportRetainsRecoveredBinaryBytes() throws Exception {
        final Email email = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg(false, null, null, null, PAYLOAD))).toEmail();
        final Email restored = MAIL.converter().fromEml(MAIL.converter().fromEmail(email).toEmlBytes()).toEmail();
        assertThat(restored.getAttachments()).hasSize(2).anySatisfy(resource -> {
            assertThat(resource.readAllBytes()).isEqualTo(PAYLOAD);
            assertThat(resource.getDataSource().getContentType()).startsWith("application/octet-stream");
        });
        assertThat(restored.getPlainText()).contains("Just a test to get an email with one cc recipient.");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void outgoingAttachmentConstructionStillRequiresAMimeType(final String missingType) {
        assertThatThrownBy(() -> MAIL.emailBuilder().startingBlank().withAttachment(null, PAYLOAD, missingType))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("mimetype is required");
    }

    private static AttachmentResource importedResource(final Email email, final boolean inline) {
        if (inline) {
            assertThat(email.getAttachments()).hasSize(1);
            assertThat(email.getEmbeddedImages()).hasSize(1);
            return email.getEmbeddedImages().get(0);
        }
        assertThat(email.getAttachments()).hasSize(2);
        assertThat(email.getEmbeddedImages()).isEmpty();
        return email.getAttachments().stream().filter(resource -> !"neighbor.txt".equals(resource.getName())).findFirst().orElseThrow();
    }

    private static void assertNeighboringContent(final Email email) {
        assertThat(email.getSubject()).isEqualTo("Test E-Mail");
        assertThat(email.getPlainText()).contains("Just a test to get an email with one cc recipient.");
        assertThat(email.getHTMLText()).contains("Retained HTML body");
        assertThat(email.getFromRecipient().getAddress()).isEqualTo("elias.laugher@gmail.com");
        assertThat(email.getRecipients()).extracting(Recipient::getAddress)
                .containsExactly("sielenkemper@otris.de", "niklas.lindson@gmail.com");
        assertThat(email.getAttachments()).anySatisfy(resource -> {
            assertThat(resource.getName()).isEqualTo("neighbor.txt");
            assertThat(resource.getDataSource().getContentType()).isEqualTo("text/plain");
            assertThat(resource.readAllBytes()).isEqualTo(NEIGHBOR);
        });
    }

    private static Email convert(final Route route, final Path path, final byte[] msg) {
        switch (route) {
            case FLUENT_PATH: return MAIL.converter().fromOutlookMsg(path).toEmail();
            case FLUENT_STREAM: return MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
            case STATIC_FILE: return EmailConverter.outlookMsgToEmail(path.toFile());
            case STATIC_STREAM: return EmailConverter.outlookMsgToEmail(new ByteArrayInputStream(msg));
            default: throw new AssertionError(route);
        }
    }

    /** Keep an actual message container and add sparse attachment streams read by the specialist MSG parser. */
    private static byte[] msg(final boolean inline, final String type, final String shortName,
            final String longName, final byte[] payload) throws Exception {
        try (InputStream input = OutlookMissingAttachmentTypeTest.class.getResourceAsStream("/test-messages/simple email with TO and CC.msg");
                POIFSFileSystem file = new POIFSFileSystem(input)) {
            final DirectoryEntry root = file.getRoot();
            for (final String name : Arrays.asList("__substg1.0_1013001F", "__substg1.0_1013001E", "__substg1.0_10130102")) {
                if (root.hasEntry(name)) root.getEntry(name).delete();
            }
            final String html = "<p>Retained HTML body</p>" + (inline ? "<img src=\"cid:" + CID + "\">" : "");
            stringProperty(root, "1013", html);
            final DirectoryEntry attachment = root.createDirectory("__attach_version1.0_#00000000");
            attachment.createDocument("__substg1.0_37010102", new ByteArrayInputStream(payload));
            stringProperty(attachment, "370E", type);
            stringProperty(attachment, "3704", shortName);
            stringProperty(attachment, "3707", longName);
            if (inline) stringProperty(attachment, "3712", CID);
            final DirectoryEntry neighbor = root.createDirectory("__attach_version1.0_#00000001");
            neighbor.createDocument("__substg1.0_37010102", new ByteArrayInputStream(NEIGHBOR));
            stringProperty(neighbor, "3707", "neighbor.txt");
            stringProperty(neighbor, "370E", "text/plain");
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            file.writeFilesystem(output);
            return output.toByteArray();
        }
    }

    private static void stringProperty(final DirectoryEntry entry, final String property, final String value) throws Exception {
        if (value != null) entry.createDocument("__substg1.0_" + property + "001F",
                new ByteArrayInputStream((value + '\0').getBytes(StandardCharsets.UTF_16LE)));
    }

    private enum Route { FLUENT_PATH, FLUENT_STREAM, STATIC_FILE, STATIC_STREAM }

    private static final class BorrowedInput extends ByteArrayInputStream {
        private boolean closed;
        private BorrowedInput(final byte[] bytes) { super(bytes); }
        @Override public void close() { closed = true; }
    }
}
