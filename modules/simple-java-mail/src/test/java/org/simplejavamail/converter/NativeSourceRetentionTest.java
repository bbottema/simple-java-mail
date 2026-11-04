package org.simplejavamail.converter;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceContent;
import org.simplejavamail.api.email.EmailSourceData;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.api.email.EmailSourceProperty;
import org.simplejavamail.internal.outlooksupport.internal.model.OutlookMessageProxy;
import org.simplejavamail.outlookmessageparser.OutlookMessageParser;
import org.simplejavamail.outlookmessageparser.model.OutlookMessage;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class NativeSourceRetentionTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final String RTF_SAMPLE = "#576 RtfSampleEmail.msg";
    private static final String RTF_ATTACHMENT_SAMPLE = "#576 RtfSampleEmailWithAttachment.msg";
    private static final String NESTED_SAMPLE = "#298 Email with nested msg with own attachment.msg";

    @ParameterizedTest
    @ValueSource(strings = { RTF_SAMPLE, RTF_ATTACHMENT_SAMPLE })
    void expectedRtfConversionKeepsTheExactReaderOriginalWithoutAProblem(final String fixture) throws Exception {
        final byte[] msg = resource(fixture);
        final OutlookMessage reader = parse(msg);
        assertThat(reader.getBodyRTF()).isNotEmpty();
        assertThat(reader.getBodyHTML()).isNull();

        final Email email = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        assertThat(email.getHTMLText()).isEqualTo(reader.getConvertedBodyHTML()).isNotEmpty();
        assertThat(email.getPlainText()).isEqualTo(reader.getBodyText()).isNotEmpty();
        final EmailSourcePart original = nativeBody(email.getSourceData(), "rtf");
        assertThat(original.getText()).isEqualTo(reader.getBodyRTF());
        assertThat(original.getContent()).isNull();
        assertThat(email.getProblems()).isEmpty();
        assertThat(original.getOrigin()).isSameAs(email.getSourceData().getOrigin());

        assertNoDuplicatedBodyProperties(email.getSourceData());
        final Email copy = MAIL.emailBuilder().copying(email).withHTMLText("<p>Application replacement</p>").buildEmail();
        assertThat(copy.getSourceData()).isSameAs(email.getSourceData());
        assertThat(nativeBody(copy.getSourceData(), "rtf").getText()).isEqualTo(reader.getBodyRTF());
        assertThat(copy.getProblems()).isEmpty();
    }

    @Test
    void healthyNativeFilePartsHaveMetadataWithoutAnotherCanonicalPayload() throws Exception {
        final byte[] msg = resource(RTF_ATTACHMENT_SAMPLE);
        final OutlookMessage reader = parse(msg);
        final Email email = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        assertThat(email.getAttachments()).hasSize(reader.fetchTrueAttachments().size()).isNotEmpty();

        final List<EmailSourcePart> fileParts = parts(email.getSourceData()).stream()
                .filter(part -> property(part, "filename") != null || property(part, "longFilename") != null)
                .collect(java.util.stream.Collectors.toList());
        assertThat(fileParts).hasSize(email.getAttachments().size());
        for (EmailSourcePart part : fileParts) {
            assertThat(part.getContent()).isNull();
            assertThat(part.getText()).isNull();
        }
        assertNoDuplicatedBodyProperties(email.getSourceData());
    }

    @Test
    void uninterpretableNativeRtfRetainsItsFullReaderBytesAndUsableNeighbor() throws Exception {
        // Keep declared sizes small while making the compressed-RTF magic invalid.
        final byte[] failedRtf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(16).putInt(4).putInt(0x21444142).putInt(0).putInt(0).array();
        final byte[] msg;
        try (POIFSFileSystem file = new POIFSFileSystem(new ByteArrayInputStream(resource(RTF_SAMPLE)))) {
            replaceProperty(file.getRoot(), "1009", "0102", failedRtf);
            replaceUnicode(file.getRoot(), "1000", "Readable native neighbor");
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            file.writeFilesystem(output);
            msg = output.toByteArray();
        }
        final OutlookMessage reader = parse(msg);
        assertThat(reader.getBodyRTF()).isNull();
        assertThat(reader.getPropertyValue(0x1009)).isInstanceOf(byte[].class);
        assertThat((byte[]) reader.getPropertyValue(0x1009)).containsExactly(failedRtf);

        final Email email = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        assertThat(email.getPlainText()).isEqualTo("Readable native neighbor");
        final EmailSourcePart failed = nativeBody(email.getSourceData(), "rtf");
        assertThat(failed.getContent()).isNotNull();
        assertThat(failed.getContent().getRepresentation()).isEqualTo(EmailSourceContent.Representation.READER_BYTES);
        assertThat(failed.getContent().isFullyCaptured()).isTrue();
        assertThat(failed.getContent().readAllBytes()).containsExactly(failedRtf);
        assertThat(failed.getContent().getSize()).isEqualTo(failedRtf.length);
        assertThat(email.getProblems()).anySatisfy(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.FIELD_NOT_INTERPRETED);
            assertThat(problem.getOperation()).isEqualTo(EmailProblem.Operation.IMPORT);
            assertThat(problem.getSources()).contains(failed);
            assertThat(problem.getOrigin()).isSameAs(failed.getOrigin());
        });
    }

    @Test
    void nestedNativeMessagesExposeTheirOwnHeadersPropertiesAndIdentity() throws Exception {
        final byte[] msg;
        final String nestedId = "<nested-source@example.org>";
        final String nestedHeaders = "Message-ID: " + nestedId + "\r\nSubject: Native nested subject\r\nX-Native-Nested: retained\r\n";
        try (POIFSFileSystem file = new POIFSFileSystem(new ByteArrayInputStream(resource(NESTED_SAMPLE)))) {
            final DirectoryEntry nested = embeddedMessageDirectory(file.getRoot());
            replaceUnicode(nested, "007D", nestedHeaders);
            replaceUnicode(nested, "1035", nestedId);
            replaceUnicode(nested, "0037", "Native nested subject");
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            file.writeFilesystem(output);
            msg = output.toByteArray();
        }
        final OutlookMessageProxy reader = new OutlookMessageProxy(parse(msg));
        final org.simplejavamail.api.internal.outlooksupport.model.OutlookMessage nestedReader = reader.getOutlookAttachments().stream()
                .map(org.simplejavamail.api.internal.outlooksupport.model.OutlookAttachment::getEmbeddedMessage)
                .filter(Objects::nonNull).findFirst().orElseThrow(AssertionError::new);
        assertThat(nestedReader.getHeaders()).contains("X-Native-Nested: retained");
        assertThat(nestedReader.getMessageId()).isEqualTo(nestedId);

        final Email email = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        final EmailSourcePart nested = parts(email.getSourceData()).stream()
                .filter(part -> part.getHeaders().stream().anyMatch(header -> header.getName().equalsIgnoreCase("X-Native-Nested")))
                .findFirst().orElseThrow(() -> new AssertionError("Nested received headers were not retained"));
        assertThat(nested.getHeaders()).anySatisfy(header -> {
            assertThat(header.getName()).isEqualTo("X-Native-Nested");
            assertThat(header.getRawValue()).isEqualTo("retained");
            assertThat(header.getOrigin()).isSameAs(nested.getOrigin());
        });
        assertThat(nested.getOrigin().getMessageId()).isEqualTo(nestedId);
        assertThat(nested.getOrigin().getReference()).isNotEqualTo(email.getSourceData().getOrigin().getReference());
        assertThat(nested.getProperties()).anySatisfy(property -> {
            assertThat(property.getName()).isEqualTo("0x0037");
            assertThat(property.getValue()).isEqualTo("Native nested subject");
        });
    }

    private static void assertNoDuplicatedBodyProperties(final EmailSourceData source) {
        assertThat(source.getProperties()).extracting(EmailSourceProperty::getName).doesNotContain("propertyListing");
        for (EmailSourceProperty property : source.getProperties()) {
            if (List.of("0x1000", "0x1009", "0x1013").contains(property.getName())) {
                assertThat(property.getValue()).isNull();
                assertThat(property.getContent()).isNull();
            }
        }
    }

    private static EmailSourcePart nativeBody(final EmailSourceData source, final String kind) {
        return parts(source).stream().filter(part -> kind.equals(property(part, "bodyFormat")))
                .findFirst().orElseThrow(() -> new AssertionError("No captured native " + kind + " body"));
    }

    private static String property(final EmailSourcePart part, final String name) {
        return part.getProperties().stream().filter(property -> property.getName().equals(name))
                .map(EmailSourceProperty::getValue).findFirst().orElse(null);
    }

    private static List<EmailSourcePart> parts(final EmailSourceData source) {
        final List<EmailSourcePart> result = new ArrayList<>();
        for (EmailSourcePart part : source.getParts()) collect(part, result);
        for (EmailSourceData derived : source.getDerivedSources()) result.addAll(parts(derived));
        return result;
    }

    private static void collect(final EmailSourcePart part, final List<EmailSourcePart> result) {
        result.add(part);
        for (EmailSourcePart child : part.getChildren()) collect(child, result);
    }

    private static OutlookMessage parse(final byte[] msg) throws Exception {
        return new OutlookMessageParser().parseMsg(new ByteArrayInputStream(msg));
    }

    private static byte[] resource(final String name) throws Exception {
        try (InputStream input = NativeSourceRetentionTest.class.getResourceAsStream("/test-messages/" + name)) {
            if (input == null) throw new AssertionError("Missing fixture " + name);
            return input.readAllBytes();
        }
    }

    private static DirectoryEntry embeddedMessageDirectory(final DirectoryEntry directory) {
        for (Iterator<Entry> entries = directory.getEntries(); entries.hasNext();) {
            final Entry entry = entries.next();
            if (entry instanceof DirectoryEntry && entry.getName().equalsIgnoreCase("__substg1.0_3701000D")) return (DirectoryEntry) entry;
        }
        for (Iterator<Entry> entries = directory.getEntries(); entries.hasNext();) {
            final Entry entry = entries.next();
            if (entry instanceof DirectoryEntry) {
                final DirectoryEntry found = findEmbeddedMessageDirectory((DirectoryEntry) entry);
                if (found != null) return found;
            }
        }
        throw new AssertionError("The nested MSG fixture has no embedded-message directory");
    }

    private static DirectoryEntry findEmbeddedMessageDirectory(final DirectoryEntry directory) {
        if (directory.getName().equalsIgnoreCase("__substg1.0_3701000D")) return directory;
        for (Iterator<Entry> entries = directory.getEntries(); entries.hasNext();) {
            final Entry entry = entries.next();
            if (entry instanceof DirectoryEntry) {
                final DirectoryEntry found = findEmbeddedMessageDirectory((DirectoryEntry) entry);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void replaceUnicode(final DirectoryEntry directory, final String code, final String value) throws Exception {
        replaceProperty(directory, code, "001F", (value + '\0').getBytes(StandardCharsets.UTF_16LE));
    }

    private static void replaceProperty(final DirectoryEntry directory, final String code, final String type, final byte[] value) throws Exception {
        final String prefix = "__SUBSTG1.0_" + code.toUpperCase(Locale.ROOT);
        final List<Entry> old = new ArrayList<>();
        for (Iterator<Entry> entries = directory.getEntries(); entries.hasNext();) {
            final Entry entry = entries.next();
            if (entry.getName().toUpperCase(Locale.ROOT).startsWith(prefix)) old.add(entry);
        }
        for (Entry entry : old) entry.delete();
        directory.createDocument("__substg1.0_" + code + type, new ByteArrayInputStream(value));
    }
}
