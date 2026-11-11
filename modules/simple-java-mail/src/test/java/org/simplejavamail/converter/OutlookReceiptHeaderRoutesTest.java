package org.simplejavamail.converter;

import jakarta.mail.Header;
import jakarta.mail.internet.MimeMessage;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.EntryUtils;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.converter.ConfiguredEmailConverter.OutlookConversion;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceHeader;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Public MSG entry points use the same receipt interpretation, without replacing Outlook's parser. */
class OutlookReceiptHeaderRoutesTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    private static final String[][] NAMES = {
            {"Disposition-Notification-To", "Return-Receipt-To"},
            {"disposition-notification-to", "return-receipt-to"},
            {"DISPOSITION-NOTIFICATION-TO", "RETURN-RECEIPT-TO"},
            {"dIsPoSiTiOn-NoTiFiCaTiOn-To", "rEtUrN-rEcEiPt-To"}
    };
    private static final String DISPOSITION = "\"Doe, Jane\" <First@EXAMPLE.ORG>, Second <second@example.org>, \"Doe, Jane\" <First@EXAMPLE.ORG>";
    private static final String RETURNS = "Returns <Return@EXAMPLE.ORG>, Backup <backup@example.org>, Returns <Return@EXAMPLE.ORG>";

    @TempDir Path directory;

    @ParameterizedTest
    @MethodSource("routesAndCaseVariants")
    void receiptNamesHaveEquivalentMeaningAcrossPublicMsgRoutes(final Source source, final String dispositionName,
            final String returnName) throws Exception {
        final byte[] msg = OutlookAddressImportTest.withAddressHeader(dispositionName,
                DISPOSITION + "\r\n" + returnName + ": " + RETURNS + "\r\nX-Custom-Case: unchanged\r\nReturn-Path: bounce@example.org");
        final Path path = directory.resolve("receipt.msg");
        Files.write(path, msg);

        final OutlookEmailConversionResult fluentData = fluent(source, path, msg).toEmailBuilderWithOutlookData();
        final OutlookEmailConversionResult staticData = staticWithData(source, path, msg);
        final List<Email> emails = new ArrayList<>(Arrays.asList(
                fluent(source, path, msg).toEmail(),
                fluent(source, path, msg).toEmailBuilder().buildEmail(),
                fluentData.buildEmail(), staticEmail(source, path, msg), staticData.buildEmail()));
        if (source == Source.FILE) emails.add(EmailConverter.outlookMsgToEmailBuilder(path.toFile()).buildEmail());
        if (source == Source.PATH) emails.add(EmailConverter.outlookMsgToEmailBuilder(path).buildEmail());
        if (source == Source.STREAM) emails.add(legacyStreamBuilder(msg));
        for (final Email email : emails) {
            assertThat(email.getDispositionNotificationToRecipients()).extracting(Recipient::getAddress)
                    .containsExactly("First@EXAMPLE.ORG", "second@example.org", "First@EXAMPLE.ORG");
            assertThat(email.getDispositionNotificationToRecipients()).extracting(Recipient::getName)
                    .containsExactly("Doe, Jane", "Second", "Doe, Jane");
            assertThat(email.getReturnReceiptToRecipients()).extracting(Recipient::getAddress)
                    .containsExactly("Return@EXAMPLE.ORG", "backup@example.org", "Return@EXAMPLE.ORG");
            assertThat(email.getReturnReceiptToRecipients()).extracting(Recipient::getName)
                    .containsExactly("Returns", "Backup", "Returns");
            assertThat(email.getUseDispositionNotificationTo()).isTrue();
            assertThat(email.getUseReturnReceiptTo()).isTrue();
            assertThat(email.getHeaders().get("disposition-notification-to")).isNotEmpty();
            assertThat(email.getHeaders().get("RETURN-RECEIPT-TO")).isNotEmpty();
            assertThat(email.getHeaders().get("X-Custom-Case")).containsExactly("unchanged");
            assertThat(email.getFromRecipient().getAddress()).isEqualTo("elias.laugher@gmail.com");
            assertThat(email.getBounceToRecipient().getAddress()).isEqualTo("bounce@example.org");
            assertThat(email.getSubject()).isEqualTo("Test E-Mail");
            assertThat(email.getPlainText()).contains("Just a test to get an email with one cc recipient.");
            assertThat(email.getHTMLText()).contains("Just a test to get an email with one cc recipient.");
            assertThat(email.getRecipients()).extracting(Recipient::getAddress)
                    .containsExactly("sielenkemper@otris.de", "niklas.lindson@gmail.com");
        }
        for (final OutlookEmailConversionResult result : Arrays.asList(fluentData, staticData)) {
            assertThat(result.getOutlookMessageData().getRawHeaders()).contains(dispositionName + ": " + DISPOSITION,
                    returnName + ": " + RETURNS, "X-Custom-Case: unchanged");
            assertThat(result.getOutlookMessageData().getHeaderValues(dispositionName)).containsExactly(DISPOSITION);
            assertThat(result.getOutlookMessageData().getHeaderValues(returnName)).containsExactly(RETURNS);
        }
    }

    static Stream<Arguments> routesAndCaseVariants() {
        return Arrays.stream(Source.values()).flatMap(source -> Arrays.stream(NAMES)
                .map(names -> Arguments.of(source, names[0], names[1])));
    }

    @Test
    void nestedMsgReceiptFieldsBelongToTheNestedMessageAndUseTheTypedRoute() throws Exception {
        final byte[] msg;
        try (InputStream input = getClass().getResourceAsStream("/test-messages/#298 Email with nested msg.msg");
                POIFSFileSystem file = new POIFSFileSystem(input)) {
            assertThat(replaceNestedHeaders(file.getRoot(), true)).isGreaterThan(0);
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            file.writeFilesystem(output);
            msg = output.toByteArray();
        }
        for (final Email parent : Arrays.asList(MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail(),
                EmailConverter.outlookMsgToEmail(new ByteArrayInputStream(msg)))) {
            assertThat(parent.getDispositionNotificationToRecipients()).isEmpty();
            assertThat(parent.getReturnReceiptToRecipients()).isEmpty();
            final List<AttachmentResource> nestedMessages = new ArrayList<>();
            for (final AttachmentResource attachment : parent.getAttachments()) {
                if (attachment.getDataSource().getContentType().startsWith("message/rfc822")) nestedMessages.add(attachment);
            }
            assertThat(nestedMessages).isNotEmpty();
            for (final AttachmentResource attachment : nestedMessages) {
                final byte[] eml = attachment.readAllBytes();
                final MimeMessage nestedMime = MAIL.converter().fromEml(eml).toMimeMessage();
                final List<String> names = new ArrayList<>();
                for (final Header header : Collections.list(nestedMime.getAllHeaders())) names.add(header.getName());
                assertThat(names).contains("Disposition-Notification-To", "Return-Receipt-To")
                        .doesNotContain("disposition-notification-to", "rEtUrN-rEcEiPt-To");
                final Email nested = MAIL.converter().fromEml(eml).toEmail();
                assertThat(nested.getDispositionNotificationToRecipients()).extracting(Recipient::getAddress)
                        .containsExactly("nested-receipt@example.org");
                assertThat(nested.getReturnReceiptToRecipients()).extracting(Recipient::getAddress)
                        .containsExactly("nested-return@example.org");
            }
        }
    }

    @ParameterizedTest
    @MethodSource("uninterpretableNestedFields")
    void nestedAddressRecoveryRetainsTheChildObservationAndSource(final String field, final String value,
            final EmailProblem.Code code) throws Exception {
        final byte[] directMsg = OutlookAddressImportTest.withAddressHeader(field, value);
        final Email direct = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(directMsg)).toEmail();
        assertThat(direct.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getCode()).isEqualTo(code);
            assertThat(problem.getLocation()).isEqualTo(field + "[0]");
            assertThat(problem.getSourceValue()).isEqualTo(value);
        });
        final int nestedCount = 1;
        final String nestedId = "<nested-recovery@example.org>";
        final String headers = "From: nested@example.org\r\nTo: reader@example.org\r\n"
                + "Message-ID: " + nestedId + "\r\n" + field + ": " + value + "\r\n"
                + "Comments: =?X-NO-SUCH-CHARSET?Q?deliberately_unused?=\r\n";
        final byte[] msg = nestedMsg(headers);
        for (final Email parent : Arrays.asList(MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail(),
                EmailConverter.outlookMsgToEmail(new ByteArrayInputStream(msg)))) {
            assertNestedRecovery(parent, field, value, code, nestedCount);
            final Email copy = MAIL.emailBuilder().copying(parent).fixingMessageId("<copy@example.org>").buildEmail();
            assertNestedRecovery(copy, field, value, code, nestedCount);
            assertThat(copy.getProblems()).containsExactlyElementsOf(parent.getProblems());
            assertThat(copy.getSourceData()).isSameAs(parent.getSourceData());

            final ByteArrayOutputStream serialized = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(serialized)) { output.writeObject(copy); }
            try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serialized.toByteArray()))) {
                final Email restored = (Email) input.readObject();
                assertNestedRecovery(restored, field, value, code, nestedCount);
                assertThat(restored.getProblems()).extracting(problem -> problem.getOrigin().getReference())
                        .containsExactlyElementsOf(parent.getProblems().stream().map(problem -> problem.getOrigin().getReference())
                                .collect(java.util.stream.Collectors.toList()));
            }
            assertThat(parent.getPlainText()).isNotEmpty();
            assertThat(parent.getDispositionNotificationToRecipients()).isEmpty();
            assertThat(parent.getReturnReceiptToRecipients()).isEmpty();
            assertThat(parent.getAttachments()).anySatisfy(attachment ->
                    assertThat(attachment.getDataSource().getContentType()).startsWith("message/rfc822"));
        }
    }

    static Stream<Arguments> uninterpretableNestedFields() {
        return Stream.of("Disposition-Notification-To", "Return-Receipt-To", "Return-Path").flatMap(field -> Stream.of(
                Arguments.of(field, ",", EmailProblem.Code.FIELD_NOT_INTERPRETED),
                Arguments.of(field, "=?X-NO-SUCH-CHARSET?Q?Name?= <receipt@example.org>", EmailProblem.Code.VALUE_NOT_DECODED)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Disposition-Notification-To", "Return-Receipt-To", "Return-Path"})
    void nestedLenientAddressInterpretationRetainsTheExistingNoProblemPolicy(final String field) throws Exception {
        final String value = "broken <address";
        final byte[] direct = OutlookAddressImportTest.withAddressHeader(field, value);
        assertThat(MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(direct)).toEmail().getProblems()).isEmpty();
        final byte[] msg = nestedMsg("From: nested@example.org\r\nTo: reader@example.org\r\n" + field + ": " + value + "\r\n");
        final Email parent = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        assertThat(parent.getProblems()).isEmpty();
        for (AttachmentResource attachment : parent.getAttachments()) {
            if (attachment.getDataSource().getContentType().startsWith("message/rfc822")) {
                assertThat(attachment.getEmail()).isNotNull();
                assertThat(attachment.getEmail().getHeaders().get(field)).contains(value);
                final MimeMessage nested = MAIL.converter().fromEml(attachment.readAllBytes()).toMimeMessage();
                if ("Return-Path".equalsIgnoreCase(field)) {
                    assertThat(nested.getHeader(field, null)).isNull();
                } else {
                    // Legacy lenient typed notification destinations remain the notification's authority.
                    assertThat(nested.getHeader(field, null)).contains(value);
                }
            }
        }
    }

    @Test
    void anUnselectedNestedReplyToRemainsSourceEvidenceWithoutAnObservation() throws Exception {
        final String value = "=?X-NO-SUCH-CHARSET?Q?Name?= <reply@example.org>";
        final byte[] msg = nestedMsg("From: nested@example.org\r\nTo: reader@example.org\r\nReply-To: " + value + "\r\n");
        final Email parent = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        assertThat(parent.getProblems()).isEmpty();
        final List<EmailSourceHeader> captured = new ArrayList<>();
        for (EmailSourcePart part : parent.getSourceData().getParts()) collectHeaders(part, captured);
        assertThat(captured).anySatisfy(header -> {
            assertThat(header.getName()).isEqualTo("Reply-To");
            assertThat(header.getRawValue()).isEqualTo(value);
        });
        for (AttachmentResource attachment : parent.getAttachments()) {
            if (attachment.getDataSource().getContentType().startsWith("message/rfc822")) {
                final MimeMessage nested = MAIL.converter().fromEml(attachment.readAllBytes()).toMimeMessage();
                assertThat(nested.getHeader("Reply-To", null)).isNull();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void siblingAndDeeperRecoveriesKeepDistinctOriginsEvenWhenMessageIdsAreAbsentOrEqual(final boolean withId) throws Exception {
        final byte[] msg;
        final String sharedId = withId ? "<shared-nested@example.org>" : null;
        final String field = "Disposition-Notification-To";
        try (InputStream input = getFixture(); InputStream templateInput = getFixture();
                POIFSFileSystem file = new POIFSFileSystem(input); POIFSFileSystem template = new POIFSFileSystem(templateInput)) {
            final DirectoryNode firstAttachment = attachedMessage(file.getRoot());
            final DirectoryNode firstChild = (DirectoryNode) firstAttachment.getEntry("__substg1.0_3701000D");
            final DirectoryNode originalAttachment = attachedMessage(template.getRoot());
            copyAttachment(originalAttachment, file.getRoot(), "__attach_version1.0_#0000F001");
            copyAttachment(originalAttachment, firstChild, "__attach_version1.0_#0000F002");
            final String headers = "From: nested@example.org\r\nTo: reader@example.org\r\n"
                    + (withId ? "Message-ID: " + sharedId + "\r\n" : "") + field + ": ,\r\n";
            assertThat(replaceNestedHeaders(file.getRoot(), true, headers)).isEqualTo(3);
            replaceNestedIds(file.getRoot(), sharedId);
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            file.writeFilesystem(output);
            msg = output.toByteArray();
        }
        final Email parent = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        assertNestedRecovery(parent, field, ",", EmailProblem.Code.FIELD_NOT_INTERPRETED, 3);
        assertThat(parent.getProblems()).extracting(problem -> problem.getOrigin().getReference()).doesNotHaveDuplicates();
        assertThat(parent.getProblems()).extracting(EmailProblem::getLocation).doesNotHaveDuplicates();
        assertThat(parent.getProblems()).extracting(problem -> problem.getOrigin().getMessageId())
                .containsExactly(sharedId, sharedId, sharedId);
        assertThat(parent.getProblems()).anySatisfy(problem ->
                assertThat(problem.getLocation().split("/message/", -1)).hasSize(3));
    }

    private static InputStream getFixture() {
        return OutlookReceiptHeaderRoutesTest.class.getResourceAsStream("/test-messages/#298 Email with nested msg.msg");
    }

    private static byte[] nestedMsg(final String headers) throws Exception {
        try (InputStream input = getFixture(); POIFSFileSystem file = new POIFSFileSystem(input)) {
            assertThat(replaceNestedHeaders(file.getRoot(), true, headers)).isEqualTo(1);
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            file.writeFilesystem(output);
            return output.toByteArray();
        }
    }

    private static DirectoryNode attachedMessage(final DirectoryNode node) throws Exception {
        for (Entry entry : node) {
            if (entry.isDirectoryEntry() && ((DirectoryNode) entry).hasEntry("__substg1.0_3701000D")) return (DirectoryNode) entry;
        }
        throw new AssertionError("The fixture must contain an embedded MSG");
    }

    private static void copyAttachment(final DirectoryNode source, final DirectoryNode target, final String name) throws Exception {
        final DirectoryNode copied = (DirectoryNode) target.createDirectory(name);
        for (Entry entry : source) EntryUtils.copyNodeRecursively(entry, copied);
    }

    private static void replaceNestedIds(final DirectoryNode node, final String id) throws Exception {
        if ("__substg1.0_3701000D".equalsIgnoreCase(node.getName())) {
            for (String type : List.of("001E", "001F")) {
                final String name = "__substg1.0_1035" + type;
                if (node.hasEntry(name)) node.getEntry(name).delete();
            }
            if (id != null) node.createDocument("__substg1.0_1035001F",
                    new ByteArrayInputStream((id + '\0').getBytes(StandardCharsets.UTF_16LE)));
        }
        for (Entry entry : node) if (entry.isDirectoryEntry()) replaceNestedIds((DirectoryNode) entry, id);
    }

    private static void assertNestedRecovery(final Email parent, final String field, final String value,
            final EmailProblem.Code code, final int count) {
        assertThat(parent.getProblems()).hasSize(count);
        final List<EmailSourceHeader> headers = new ArrayList<>();
        for (EmailSourcePart part : parent.getSourceData().getParts()) collectHeaders(part, headers);
        for (EmailProblem problem : parent.getProblems()) {
            assertThat(problem.getOperation()).isEqualTo(EmailProblem.Operation.IMPORT);
            assertThat(problem.getCode()).isEqualTo(code);
            assertThat(problem.getLocation()).startsWith("source/parts[").endsWith("/message/" + field + "[0]");
            assertThat(problem.getSourceValue()).isEqualTo(value);
            assertThat(problem.getOrigin().getReference()).isNotEqualTo(parent.getSourceData().getOrigin().getReference());
            assertThat(problem.getSources()).singleElement().satisfies(source -> {
                assertThat(source).isInstanceOf(EmailSourceHeader.class);
                final EmailSourceHeader header = (EmailSourceHeader) source;
                assertThat(header.getName()).isEqualTo(field);
                assertThat(header.getRawValue()).isEqualTo(value);
                assertThat(header.getOrigin()).isSameAs(problem.getOrigin());
                assertThat(headers).anySatisfy(captured -> assertThat(captured).isSameAs(header));
            });
        }
    }

    private static void collectHeaders(final EmailSourcePart part, final List<EmailSourceHeader> target) {
        target.addAll(part.getHeaders());
        for (EmailSourcePart child : part.getChildren()) collectHeaders(child, target);
    }

    private static int replaceNestedHeaders(final DirectoryNode node, final boolean root) throws Exception {
        return replaceNestedHeaders(node, root, "From: nested@example.org\r\nTo: reader@example.org\r\n"
                + "disposition-notification-to: nested-receipt@example.org\r\n"
                + "rEtUrN-rEcEiPt-To: nested-return@example.org\r\n");
    }

    private static int replaceNestedHeaders(final DirectoryNode node, final boolean root, final String headers) throws Exception {
        int replaced = 0;
        if (!root && "__substg1.0_3701000D".equalsIgnoreCase(node.getName())) {
            final String type = node.hasEntry("__substg1.0_007D001E") ? "001E" : "001F";
            final String stream = "__substg1.0_007D" + type;
            final Charset charset = "001F".equals(type) ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1;
            final byte[] encoded = (headers + '\0').getBytes(charset);
            node.createOrUpdateDocument(stream, new ByteArrayInputStream(encoded));
            final byte[] originalProperties;
            try (InputStream input = node.createDocumentInputStream("__properties_version1.0")) {
                originalProperties = input.readAllBytes();
            }
            final int tag = (0x007D << 16) | Integer.parseInt(type, 16);
            final ByteBuffer existing = ByteBuffer.wrap(originalProperties).order(ByteOrder.LITTLE_ENDIAN);
            int recordOffset = originalProperties.length;
            // Embedded messages have a 24-byte property header; add the optional header property when absent.
            for (int offset = 24; offset + 16 <= originalProperties.length; offset += 16) {
                if (existing.getInt(offset) == tag) recordOffset = offset;
            }
            final byte[] properties = recordOffset == originalProperties.length
                    ? Arrays.copyOf(originalProperties, originalProperties.length + 16) : originalProperties;
            final ByteBuffer records = ByteBuffer.wrap(properties).order(ByteOrder.LITTLE_ENDIAN);
            records.putInt(recordOffset, tag);
            records.putInt(recordOffset + 4, 6);
            records.putInt(recordOffset + 8, encoded.length);
            node.createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(properties));
            replaced++;
        }
        for (final Entry entry : node) {
            if (entry.isDirectoryEntry()) replaced += replaceNestedHeaders((DirectoryNode) entry, false, headers);
        }
        return replaced;
    }

    private static OutlookConversion fluent(final Source source, final Path path, final byte[] msg) {
        switch (source) {
            case FILENAME: return MAIL.converter().fromOutlookMsg(path.toString());
            case FILE: return MAIL.converter().fromOutlookMsg(path.toFile());
            case PATH: return MAIL.converter().fromOutlookMsg(path);
            case STREAM: return MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg));
            default: throw new AssertionError(source);
        }
    }

    private static Email staticEmail(final Source source, final Path path, final byte[] msg) {
        switch (source) {
            case FILENAME: return EmailConverter.outlookMsgToEmail(path.toString());
            case FILE: return EmailConverter.outlookMsgToEmail(path.toFile());
            case PATH: return EmailConverter.outlookMsgToEmail(path);
            case STREAM: return EmailConverter.outlookMsgToEmail(new ByteArrayInputStream(msg));
            default: throw new AssertionError(source);
        }
    }

    private static OutlookEmailConversionResult staticWithData(final Source source, final Path path, final byte[] msg) {
        switch (source) {
            case FILENAME: return EmailConverter.outlookMsgToEmailBuilderWithOutlookData(path.toString());
            case FILE: return EmailConverter.outlookMsgToEmailBuilderWithOutlookData(path.toFile());
            case PATH: return EmailConverter.outlookMsgToEmailBuilderWithOutlookData(path);
            case STREAM: return EmailConverter.outlookMsgToEmailBuilderWithOutlookData(new ByteArrayInputStream(msg));
            default: throw new AssertionError(source);
        }
    }

    @SuppressWarnings("deprecation")
    private static Email legacyStreamBuilder(final byte[] msg) {
        return EmailConverter.outlookMsgToEmailBuilder(new ByteArrayInputStream(msg)).buildEmail();
    }

    private enum Source { FILENAME, FILE, PATH, STREAM }
}
