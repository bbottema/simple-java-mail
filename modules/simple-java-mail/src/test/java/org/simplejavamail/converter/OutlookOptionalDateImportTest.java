package org.simplejavamail.converter;

import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.api.outlook.OutlookMessageData;
import org.simplejavamail.config.ConfigLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Sparse native MSG dates remain optional through the public conversion journeys. */
class OutlookOptionalDateImportTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    private static final Instant SOURCE_DATE = Instant.parse("2021-01-02T12:00:00Z");
    private static final Instant SUBMIT_DATE = Instant.parse("2021-01-01T11:00:00Z");
    private static final Instant MODIFICATION_DATE = Instant.parse("2021-01-03T13:00:00Z");
    private static final byte[] ATTACHMENT = "Readable neighbor".getBytes(StandardCharsets.UTF_8);

    enum Route { STATIC_FILE, STATIC_PATH, STATIC_STREAM, FLUENT_PATH, FLUENT_STREAM, STATIC_METADATA, FLUENT_METADATA }
    enum Dates {
        ALL_PRESENT(true, true, true, true),
        NO_CREATION(true, false, true, true),
        NO_MODIFICATION(true, true, false, true),
        NO_CLIENT_SUBMIT(true, true, true, false),
        CLIENT_SUBMIT_ONLY(false, false, false, true),
        HEADER_ONLY(true, false, false, false),
        CREATION_ONLY(false, true, false, false),
        ALL_ABSENT(false, false, false, false);

        final boolean header;
        final boolean creation;
        final boolean modification;
        final boolean clientSubmit;

        Dates(final boolean header, final boolean creation, final boolean modification, final boolean clientSubmit) {
            this.header = header;
            this.creation = creation;
            this.modification = modification;
            this.clientSubmit = clientSubmit;
        }

        Date sourceDate() { return header || creation ? Date.from(SOURCE_DATE) : null; }
        Date sentDate() { return clientSubmit ? Date.from(SUBMIT_DATE) : header ? Date.from(SOURCE_DATE) : null; }
    }

    @TempDir Path directory;

    @ParameterizedTest
    @MethodSource("routesAndDates")
    void missingOptionalDatesKeepReadableMessageAndNativeMetadata(final Route route, final Dates dates) throws Exception {
        final byte[] msg = message(dates);
        final Path path = directory.resolve("sparse-dates.msg");
        Files.write(path, msg);
        final OutlookEmailConversionResult result;
        final Email email;
        switch (route) {
            case STATIC_FILE: result = null; email = EmailConverter.outlookMsgToEmail(path.toFile()); break;
            case STATIC_PATH: result = null; email = EmailConverter.outlookMsgToEmail(path); break;
            case STATIC_STREAM: result = null; email = EmailConverter.outlookMsgToEmail(new ByteArrayInputStream(msg)); break;
            case FLUENT_PATH: result = null; email = MAIL.converter().fromOutlookMsg(path).toEmail(); break;
            case FLUENT_STREAM: result = null; email = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail(); break;
            case STATIC_METADATA:
                result = EmailConverter.outlookMsgToEmailBuilderWithOutlookData(path);
                email = result.buildEmail();
                break;
            case FLUENT_METADATA:
                result = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmailBuilderWithOutlookData();
                email = result.buildEmail();
                break;
            default: throw new AssertionError(route);
        }
        assertThat(email.getSentDate()).isEqualTo(dates.sentDate());
        assertReadableNeighbors(email);
        assertThat(email.getSourceData().getHeaders()).anySatisfy(header -> {
            assertThat(header.getName()).isEqualTo("X-Date-Neighbor");
            assertThat(header.getRawValue()).isEqualTo("retained");
        });
        if (result != null) {
            final OutlookMessageData metadata = result.getOutlookMessageData();
            assertThat(metadata.getDate()).isEqualTo(dates.sourceDate());
            assertThat(metadata.getCreationDate()).isEqualTo(dates.creation ? Date.from(SOURCE_DATE) : null);
            assertThat(metadata.getLastModificationDate()).isEqualTo(dates.modification ? Date.from(MODIFICATION_DATE) : null);
            assertThat(metadata.getClientSubmitTime()).isEqualTo(dates.clientSubmit ? Date.from(SUBMIT_DATE) : null);
            if (metadata.getDate() != null) {
                metadata.getDate().setTime(0);
                assertThat(metadata.getDate()).isEqualTo(dates.sourceDate());
            }
        }
    }

    static Stream<Arguments> routesAndDates() {
        // Both metadata routes exercise every native-date state through the shared reader and email builder.
        // Each direct route still checks submit precedence, header fallback, creation-only and complete absence.
        return Arrays.stream(Route.values()).flatMap(route -> Arrays.stream(Dates.values())
                .filter(dates -> route == Route.STATIC_METADATA || route == Route.FLUENT_METADATA
                        || dates == Dates.ALL_PRESENT || dates == Dates.NO_CLIENT_SUBMIT
                        || dates == Dates.CREATION_ONLY || dates == Dates.ALL_ABSENT)
                .map(dates -> Arguments.of(route, dates)));
    }

    @Test
    void absentDatesSurviveCopyingAndSerialization() throws Exception {
        final Email original = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(message(Dates.ALL_ABSENT))).toEmail();
        final Email copy = MAIL.emailBuilder().copying(original).buildEmail();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(copy); }
        final Email restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (Email) input.readObject();
        }
        for (final Email email : Arrays.asList(original, copy, restored)) {
            assertThat(email.getSentDate()).isNull();
            assertReadableNeighbors(email);
            assertThat(email.getSourceData().getOrigin().getReference()).isEqualTo(original.getSourceData().getOrigin().getReference());
        }
    }

    private static void assertReadableNeighbors(final Email email) {
        assertThat(email.getSubject()).isEqualTo("Test E-Mail");
        assertThat(email.getPlainText()).contains("Just a test to get an email with one cc recipient.");
        assertThat(email.getHTMLText()).contains("Just a test to get an email with one cc recipient.");
        assertThat(email.getRecipients()).extracting(Recipient::getAddress)
                .containsExactly("sielenkemper@otris.de", "niklas.lindson@gmail.com");
        assertThat(email.getAttachments()).anySatisfy(attachment -> {
            assertThat(attachment.getName()).isEqualTo("neighbor.txt");
            assertThat(attachment.readAllBytes()).isEqualTo(ATTACHMENT);
        });
    }

    /** Remove native date records and source Date together, then add only the chosen independent facts. */
    private static byte[] message(final Dates dates) throws Exception {
        try (InputStream input = OutlookOptionalDateImportTest.class.getResourceAsStream("/test-messages/simple email with TO and CC.msg");
                POIFSFileSystem file = new POIFSFileSystem(input)) {
            final DirectoryNode root = file.getRoot();
            final List<Entry> entries = new ArrayList<>();
            root.forEach(entries::add);
            for (final Entry entry : entries) {
                final String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("__substg1.0_0039") || name.startsWith("__substg1.0_3007") || name.startsWith("__substg1.0_3008")) {
                    entry.delete();
                }
                if (name.startsWith("__substg1.0_007d")) {
                    final Charset charset = name.endsWith("001f") ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1;
                    final String oldHeaders;
                    try (InputStream headers = root.createDocumentInputStream(entry.getName())) {
                        oldHeaders = new String(headers.readAllBytes(), charset).replaceAll("\\x00+$", "");
                    }
                    final String headers = oldHeaders.replaceAll("(?im)^Date:[^\\r\\n]*(?:\\r?\\n[ \\t][^\\r\\n]*)*\\r?\\n", "")
                            .replaceAll("[\\r\\n]+$", "") + "\r\nX-Date-Neighbor: retained\r\n"
                            + (dates.header ? "Date: Sat, 2 Jan 2021 12:00:00 +0000\r\n" : "");
                    root.createOrUpdateDocument(entry.getName(), new ByteArrayInputStream((headers + '\0').getBytes(charset)));
                }
            }
            final byte[] original;
            try (InputStream properties = root.createDocumentInputStream("__properties_version1.0")) { original = properties.readAllBytes(); }
            final ByteArrayOutputStream properties = new ByteArrayOutputStream();
            properties.write(original, 0, 32);
            final ByteBuffer records = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN);
            for (int offset = 32; offset + 16 <= original.length; offset += 16) {
                final int property = records.getInt(offset) >>> 16;
                if (property != 0x0039 && property != 0x3007 && property != 0x3008) properties.write(original, offset, 16);
            }
            if (dates.creation) dateRecord(properties, 0x3007, SOURCE_DATE);
            if (dates.modification) dateRecord(properties, 0x3008, MODIFICATION_DATE);
            if (dates.clientSubmit) dateRecord(properties, 0x0039, SUBMIT_DATE);
            root.createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(properties.toByteArray()));
            final DirectoryNode attachment = (DirectoryNode) root.createDirectory("__attach_version1.0_#00000000");
            attachment.createDocument("__substg1.0_37010102", new ByteArrayInputStream(ATTACHMENT));
            attachment.createDocument("__substg1.0_3707001F", new ByteArrayInputStream("neighbor.txt\0".getBytes(StandardCharsets.UTF_16LE)));
            attachment.createDocument("__substg1.0_370E001F", new ByteArrayInputStream("text/plain\0".getBytes(StandardCharsets.UTF_16LE)));
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            file.writeFilesystem(output);
            return output.toByteArray();
        }
    }

    private static void dateRecord(final ByteArrayOutputStream output, final int property, final Instant instant) {
        final long fileTime = (instant.toEpochMilli() + 11644473600000L) * 10000;
        final byte[] record = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putInt((property << 16) | 0x0040).putInt(6).putLong(fileTime).array();
        output.write(record, 0, record.length);
    }
}
