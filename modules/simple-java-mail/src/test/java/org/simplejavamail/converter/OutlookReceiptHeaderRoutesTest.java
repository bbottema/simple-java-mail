package org.simplejavamail.converter;

import jakarta.mail.Header;
import jakarta.mail.internet.MimeMessage;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.converter.ConfiguredEmailConverter.OutlookConversion;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.config.ConfigLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
            assertThat(email.getHeaders().keySet()).noneMatch(name -> name.equalsIgnoreCase("Disposition-Notification-To")
                    || name.equalsIgnoreCase("Return-Receipt-To"));
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

    private static int replaceNestedHeaders(final DirectoryNode node, final boolean root) throws Exception {
        int replaced = 0;
        if (!root && "__substg1.0_3701000D".equalsIgnoreCase(node.getName())) {
            final String type = node.hasEntry("__substg1.0_007D001E") ? "001E" : "001F";
            final String stream = "__substg1.0_007D" + type;
            final Charset charset = "001F".equals(type) ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1;
            final String headers = "From: nested@example.org\r\nTo: reader@example.org\r\n"
                    + "disposition-notification-to: nested-receipt@example.org\r\n"
                    + "rEtUrN-rEcEiPt-To: nested-return@example.org\r\n";
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
            if (entry.isDirectoryEntry()) replaced += replaceNestedHeaders((DirectoryNode) entry, false);
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
