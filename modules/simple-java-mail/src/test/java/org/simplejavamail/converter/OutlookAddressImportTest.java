package org.simplejavamail.converter;

import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.outlookmessageparser.OutlookMessageParser;
import org.simplejavamail.outlookmessageparser.model.OutlookMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

/** Exercises real Outlook containers through both public import routes, independently of outgoing convenience parsing. */
class OutlookAddressImportTest {
    private static final String FIXTURE = "/test-messages/simple email with TO and CC.msg";
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @ParameterizedTest
    @MethodSource("receiptHeaders")
    void receiptHeadersKeepTheirEstablishedLenientInterpretation(final String header, final String source,
            final String expectedName, final String expectedAddress) throws Exception {
        final byte[] msg = withAddressHeader(header, source);
        final OutlookEmailConversionResult imported = mail.converter().fromOutlookMsg(new ByteArrayInputStream(msg))
                .toEmailBuilderWithOutlookData();
        final Email fluent = imported.buildEmail();
        final Email legacy = EmailConverter.outlookMsgToEmail(new ByteArrayInputStream(msg));

        for (Email email : Arrays.asList(fluent, legacy)) {
            final java.util.List<Recipient> destinations = "Disposition-Notification-To".equals(header)
                    ? email.getDispositionNotificationToRecipients() : email.getReturnReceiptToRecipients();
            final Recipient recipient = "Return-Path".equals(header) ? email.getBounceToRecipient()
                    : destinations.isEmpty() ? null : destinations.get(0);
            if (!"Return-Path".equals(header) && source.contains("Second <second@example.org>")) {
                assertThat(destinations).extracting(Recipient::getAddress).containsExactly("first@example.org", "second@example.org");
            }
            if (expectedAddress == null) {
                assertThat(recipient).isNull();
            } else {
                assertThat(recipient.getAddress()).isEqualTo(expectedAddress);
                assertThat(recipient.getName()).isEqualTo(expectedName);
            }
            if ("Return-Path".equals(header) && ("broken <address".equals(source) || "(comment only)".equals(source))) {
                assertThat(email.getHeaders().get("Return-Path")).containsExactly(source);
            }
            assertMessageContentSurvived(email);
            assertThat(email.getFromRecipient().getAddress()).isEqualTo("elias.laugher@gmail.com");
        }
        if (!source.isBlank()) {
            // The inspection metadata retains the full source, including any addresses outside the legacy scalar view.
            assertThat(imported.getOutlookMessageData().getHeaderValues(header)).contains(source);
        }
    }

    static Stream<Arguments> receiptHeaders() {
        return Stream.of("Disposition-Notification-To", "Return-Receipt-To", "Return-Path")
                .flatMap(header -> Stream.of(
                        Arguments.of(header, "\"Doe, Jane\" <jane@example.org>", "Doe, Jane", "jane@example.org"),
                        Arguments.of(header, "First <first@example.org>, Second <second@example.org>", "First", "first@example.org"),
                        Arguments.of(header, "Team:first@example.org;", null, "Team:first@example.org;"),
                        Arguments.of(header, "Undisclosed:;", null, "Undisclosed:;"),
                        Arguments.of(header, "broken <address", null, "Return-Path".equals(header) ? null : "broken <address"),
                        Arguments.of(header, "(comment only)", null, "Return-Path".equals(header) ? null : "(comment only)"),
                        Arguments.of(header, "<>", null, null),
                        Arguments.of(header, "", null, null),
                        Arguments.of(header, "   ", null, null)));
    }

    @Test
    void mixedCaseReturnPathKeepsItsEstablishedAddressAndUnicodeName() throws Exception {
        final byte[] msg = withAddressHeader("rEtUrN-pAtH", "\"Jáné Döe\" <jane@example.org>");
        for (Email email : Arrays.asList(mail.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail(),
                EmailConverter.outlookMsgToEmail(new ByteArrayInputStream(msg)))) {
            assertThat(email.getBounceToRecipient().getAddress()).isEqualTo("jane@example.org");
            assertThat(email.getBounceToRecipient().getName()).isEqualTo("Jáné Döe");
            assertMessageContentSurvived(email);
        }
    }

    @Test
    void uninterpretableReturnPathsRetainMixedCaseSourceOccurrenceOrder() throws Exception {
        final byte[] msg = withAddressHeader("Return-Path", "(first)\r\nreturn-path: broken <address\r\nRETURN-PATH: (last)");
        final OutlookEmailConversionResult imported = mail.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmailBuilderWithOutlookData();
        for (Email email : Arrays.asList(imported.buildEmail(), EmailConverter.outlookMsgToEmail(new ByteArrayInputStream(msg)))) {
            assertThat(email.getBounceToRecipient()).isNull();
            assertThat(email.getHeaders().get("Return-Path")).containsExactly("(first)", "broken <address", "(last)");
            assertMessageContentSurvived(email);
        }
        assertThat(imported.getOutlookMessageData().getRawHeaders()).contains("return-path: broken <address", "RETURN-PATH: (last)");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Subject: Other source headers\r\n"})
    void availableParsedReturnPathsSurviveAbsentRawSource(final String rawHeaders) throws Exception {
        final byte[] msg = withAddressHeader("Return-Path", "(first)\r\nReturn-Path: broken <address");
        final OutlookMessage parsed = new OutlookMessageParser().parseMsg(new ByteArrayInputStream(msg));
        final Field source = OutlookMessage.class.getDeclaredField("headers");
        source.setAccessible(true);
        source.set(parsed, rawHeaders);
        // A specialist parser can retain interpreted headers even when the original header text is unavailable.
        try (MockedConstruction<OutlookMessageParser> ignored = mockConstruction(OutlookMessageParser.class,
                (parser, context) -> when(parser.parseMsg(any(InputStream.class))).thenReturn(parsed))) {
            for (Email email : Arrays.asList(mail.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail(),
                    EmailConverter.outlookMsgToEmail(new ByteArrayInputStream(msg)))) {
                assertThat(email.getBounceToRecipient()).isNull();
                assertThat(email.getHeaders().get("Return-Path")).containsExactly("(first)", "broken <address");
                assertMessageContentSurvived(email);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("senderAndReplyHeaders")
    void senderAndReplyFieldsDoNotApplyOutgoingMailboxCardinality(final String header, final String source,
            final String expectedAddress) throws Exception {
        final byte[] msg = withAddressHeader(header, source);
        final Email fluent = mail.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        final Email legacy = EmailConverter.outlookMsgToEmail(new ByteArrayInputStream(msg));
        for (Email email : Arrays.asList(fluent, legacy)) {
            final Recipient recipient = "From".equals(header) ? email.getFromRecipient() : email.getReplyToRecipients().get(0);
            assertThat(recipient.getAddress()).isEqualTo(expectedAddress);
            if ("Reply-To".equals(header) && source.contains("second@example.org")) {
                assertThat(email.getReplyToRecipients()).extracting(Recipient::getAddress).containsExactly("first@example.org", "second@example.org");
            }
            // The Outlook parser supplies the display name separately; preserve the legacy fixed-name precedence.
            assertThat(recipient.getName()).isEqualTo("From".equals(header) ? "Elias Laugher" : source);
            assertMessageContentSurvived(email);
        }
    }

    static Stream<Arguments> senderAndReplyHeaders() {
        return Stream.of("From", "Reply-To").flatMap(header -> Stream.of(
                Arguments.of(header, "first@example.org, second@example.org", "first@example.org"),
                Arguments.of(header, "Team:first@example.org;", "Team:first@example.org;")));
    }

    private static void assertMessageContentSurvived(final Email email) {
        assertThat(email.getSubject()).isEqualTo("Test E-Mail");
        assertThat(email.getPlainText()).contains("Just a test to get an email with one cc recipient.");
        assertThat(email.getHTMLText()).contains("Just a test to get an email with one cc recipient.");
        assertThat(email.getRecipients()).extracting(Recipient::getAddress)
                .containsExactly("sielenkemper@otris.de", "niklas.lindson@gmail.com");
    }

    /** Replace only the selected address data in a copy of an existing, readable .msg fixture. */
    private static byte[] withAddressHeader(final String header, final String value) throws IOException {
        try (InputStream input = OutlookAddressImportTest.class.getResourceAsStream(FIXTURE);
                POIFSFileSystem file = new POIFSFileSystem(input)) {
            final DirectoryNode root = file.getRoot();
            final String headerStream = stringStream(root, 0x007D);
            assertThat(headerStream).isNotNull();
            final Charset charset = charset(headerStream);
            final String oldHeaders;
            try (InputStream headers = root.createDocumentInputStream(headerStream)) {
                oldHeaders = new String(headers.readAllBytes(), charset).replaceAll("\\x00+$", "");
            }
            final String replacement = oldHeaders
                    .replaceAll("(?im)^" + Pattern.quote(header) + ":[^\\r\\n]*(?:\\r?\\n[ \\t][^\\r\\n]*)*\\r?\\n", "")
                    .replaceAll("[\\r\\n]+$", "") + "\r\n" + header + ": " + value + "\r\n";
            replaceStringProperty(root, 0x007D, replacement);
            if ("From".equals(header)) {
                // The parser also reads the sender and represented-sender MAPI properties.
                final boolean sender = replaceStringProperty(root, 0x0C1F, value);
                final boolean representedSender = replaceStringProperty(root, 0x0065, value);
                assertThat(sender || representedSender).isTrue();
            }
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            file.writeFilesystem(output);
            return output.toByteArray();
        }
    }

    private static boolean replaceStringProperty(final DirectoryNode root, final int property, final String value) throws IOException {
        final String stream = stringStream(root, property);
        if (stream == null) {
            return false;
        }
        final byte[] encoded = (value + '\0').getBytes(charset(stream));
        root.createOrUpdateDocument(stream, new ByteArrayInputStream(encoded));
        final byte[] properties;
        try (InputStream input = root.createDocumentInputStream("__properties_version1.0")) {
            properties = input.readAllBytes();
        }
        final ByteBuffer records = ByteBuffer.wrap(properties).order(ByteOrder.LITTLE_ENDIAN);
        final int tag = (property << 16) | (stream.endsWith("001F") ? 0x001F : 0x001E);
        // A top-level message has a 32-byte property header followed by 16-byte property records.
        for (int offset = 32; offset + 16 <= properties.length; offset += 16) {
            if (records.getInt(offset) == tag) {
                records.putInt(offset + 8, encoded.length);
            }
        }
        root.createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(properties));
        return true;
    }

    private static String stringStream(final DirectoryNode root, final int property) {
        for (String type : new String[]{"001F", "001E"}) {
            final String stream = String.format(Locale.ROOT, "__substg1.0_%04X%s", property, type);
            if (root.hasEntry(stream)) {
                return stream;
            }
        }
        return null;
    }

    private static Charset charset(final String stream) {
        return stream.endsWith("001F") ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1;
    }
}
