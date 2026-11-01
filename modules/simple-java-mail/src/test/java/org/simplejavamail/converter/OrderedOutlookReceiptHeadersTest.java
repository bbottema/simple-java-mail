package org.simplejavamail.converter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.outlookmessageparser.OutlookMessageParser;
import org.simplejavamail.outlookmessageparser.model.OutlookMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

class OrderedOutlookReceiptHeadersTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    private static final String FIRST = "\"Doe, Jane\" <first@example.org>";
    private static final String SECOND = "Second <second@example.org>";
    private static final String THIRD = "Third <third@example.org>";

    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(Source.class)
    void interleavedReceiptOccurrencesKeepSourceOrderThroughPublicMsgRoutes(final Source source) throws Exception {
        final byte[] msg = withReceiptHeaders();
        final Path path = directory.resolve("ordered-receipts.msg");
        Files.write(path, msg);

        for (final Email imported : source.read(path, msg)) {
            assertOrderedReceipts(imported);
            assertOrderedReceipts(MAIL.emailBuilder().copying(imported).buildEmail());
            assertOrderedReceipts(serializedCopy(imported));
            assertOrderedReceipts(MAIL.converter().fromEml(MAIL.converter().fromEmail(imported).toEml()).toEmail());
            assertThat(imported.getHeaders().get("X-Source-Case")).containsExactly("unchanged");
            assertThat(imported.getSubject()).isEqualTo("Test E-Mail");
            assertThat(imported.getPlainText()).contains("Just a test to get an email with one cc recipient.");
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "Subject: Other source headers\r\n")
    void groupedReceiptValuesRemainAvailableWithoutRawReceiptHeaders(final String rawHeaders) throws Exception {
        final byte[] msg = OutlookAddressImportTest.withAddressHeader("Return-Receipt-To", FIRST + "\r\n"
                + "Return-Receipt-To: " + SECOND + "\r\n"
                + "Disposition-Notification-To: " + THIRD);
        final OutlookMessage parsed = parsedWithRawHeaders(msg, rawHeaders);

        try (MockedConstruction<OutlookMessageParser> ignored = parserReturning(parsed)) {
            for (final Email imported : publicStreamRoutes(msg)) {
                assertThat(imported.getReturnReceiptToRecipients()).extracting(Recipient::getAddress)
                        .containsExactly("first@example.org", "second@example.org");
                assertThat(imported.getDispositionNotificationToRecipients()).extracting(Recipient::getAddress)
                        .containsExactly("third@example.org");
            }
        }
    }

    @Test
    void eachReceiptFamilyFallsBackIndependentlyWhenOnlyTheOtherHasRawOccurrences() throws Exception {
        final byte[] msg = withReceiptHeaders();
        final OutlookMessage parsed = parsedWithRawHeaders(msg,
                "return-receipt-to: " + THIRD + "\r\nReturn-Receipt-To: " + FIRST + "\r\n");

        try (MockedConstruction<OutlookMessageParser> ignored = parserReturning(parsed)) {
            for (final Email imported : publicStreamRoutes(msg)) {
                assertThat(imported.getReturnReceiptToRecipients()).extracting(Recipient::getAddress)
                        .containsExactly("third@example.org", "first@example.org");
                assertThat(imported.getDispositionNotificationToRecipients()).extracting(Recipient::getAddress)
                        .containsExactlyInAnyOrder("first@example.org", "second@example.org", "third@example.org", "first@example.org");
            }
        }
    }

    private static byte[] withReceiptHeaders() throws Exception {
        return OutlookAddressImportTest.withAddressHeader("Return-Receipt-To", FIRST + "\r\n"
                + "Disposition-Notification-To: " + FIRST + "\r\n"
                + "return-receipt-to: " + SECOND + "\r\n"
                + "disposition-notification-to: " + SECOND + "\r\n"
                + "Return-Receipt-To: " + THIRD + ",\r\n\t" + FIRST + "\r\n"
                + "Disposition-Notification-To: " + THIRD + ",\r\n\t" + FIRST + "\r\n"
                + "X-Source-Case: unchanged");
    }

    private static void assertOrderedReceipts(final Email email) {
        for (final List<Recipient> recipients : Arrays.asList(email.getReturnReceiptToRecipients(),
                email.getDispositionNotificationToRecipients())) {
            assertThat(recipients).extracting(Recipient::getAddress)
                    .containsExactly("first@example.org", "second@example.org", "third@example.org", "first@example.org");
            assertThat(recipients).extracting(Recipient::getName).containsExactly("Doe, Jane", "Second", "Third", "Doe, Jane");
        }
        assertThat(email.getUseReturnReceiptTo()).isTrue();
        assertThat(email.getUseDispositionNotificationTo()).isTrue();
    }

    private static OutlookMessage parsedWithRawHeaders(final byte[] msg, final String rawHeaders) throws Exception {
        final OutlookMessage parsed = new OutlookMessageParser().parseMsg(new ByteArrayInputStream(msg));
        final Field headers = OutlookMessage.class.getDeclaredField("headers");
        headers.setAccessible(true);
        headers.set(parsed, rawHeaders);
        return parsed;
    }

    private static MockedConstruction<OutlookMessageParser> parserReturning(final OutlookMessage message) {
        return mockConstruction(OutlookMessageParser.class,
                (parser, context) -> when(parser.parseMsg(any(InputStream.class))).thenReturn(message));
    }

    private static List<Email> publicStreamRoutes(final byte[] msg) {
        return Arrays.asList(MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail(),
                EmailConverter.outlookMsgToEmail(new ByteArrayInputStream(msg)));
    }

    private static Email serializedCopy(final Email email) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ObjectOutputStream objects = new ObjectOutputStream(output)) {
            objects.writeObject(email);
        }
        try (ObjectInputStream objects = new ObjectInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            return (Email) objects.readObject();
        }
    }

    private enum Source {
        FILE {
            @Override List<Email> read(final Path path, final byte[] msg) {
                return Arrays.asList(MAIL.converter().fromOutlookMsg(path.toFile()).toEmail(),
                        EmailConverter.outlookMsgToEmail(path.toFile()));
            }
        },
        PATH {
            @Override List<Email> read(final Path path, final byte[] msg) {
                return Arrays.asList(MAIL.converter().fromOutlookMsg(path).toEmailBuilder().buildEmail(),
                        EmailConverter.outlookMsgToEmailBuilderWithOutlookData(path).buildEmail());
            }
        },
        STREAM {
            @Override List<Email> read(final Path path, final byte[] msg) {
                return publicStreamRoutes(msg);
            }
        };

        abstract List<Email> read(Path path, byte[] msg);
    }
}
