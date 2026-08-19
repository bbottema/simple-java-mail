package org.simplejavamail.internal.util;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.recipient.RecipientBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinalizedMimeMessageStorageTest {

    private static final String REMOVED_TEMP_FILE_PREFIX = "simple-java-mail-mime-";

    @Test
    void plainMessageIsStructurallyFinalizedWithoutMaterializingItsBytes() throws Exception {
        final Email email = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).emailBuilder().startingBlank()
                .from("sender@example.com")
                .withRecipients(RecipientBuilder.to(null, "receiver@example.com"))
                .withSubject("plain")
                .withPlainText("No cryptographic protection")
                .buildEmail();

        final MimeMessage converted = EmailConverter.emailToMimeMessage(email);
        final String messageId = converted.getMessageID();
        converted.saveChanges();

        assertThat(converted).isNotInstanceOf(FinalizedMimeMessage.class);
        assertThat(messageId).isNotBlank();
        assertThat(converted.getMessageID()).isEqualTo(messageId);
        assertThat(bytes(converted)).containsExactly(bytes(converted));
    }

    @Test
    void protectedMessageIsRepeatableWithoutTemporaryStorageOrCleanupContract() throws Exception {
        final Set<Path> before = temporaryMimeFiles();
        final FinalizedMimeMessage finalized = FinalizedMimeMessage.finalizeMessage(
                message(repeat('x', 2 * 1024 * 1024)), FinalizedMimeMessage.ProtectionState.CONTENT_PROTECTED);

        assertThat((Object) finalized).isNotInstanceOf(AutoCloseable.class);
        assertThat(bytes(finalized)).containsExactly(bytes(finalized));
        assertThat(finalized.getSerializedSize()).isEqualTo(bytes(finalized).length);
        assertThat(temporaryMimeFiles()).isEqualTo(before);
    }

    @Test
    void finalizedMessageAlwaysHonorsIgnoredHeadersRegardlessOfHeaderSize() throws Exception {
        final MimeMessage message = message("body");
        message.setRecipients(jakarta.mail.Message.RecipientType.BCC, "hidden@example.com");
        message.setHeader("X-Large", repeat('x', 1024 * 1024 + 1));
        final FinalizedMimeMessage finalized = FinalizedMimeMessage.finalizeMessage(
                message, FinalizedMimeMessage.ProtectionState.CONTENT_PROTECTED);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();

        finalized.writeTo(output, new String[] {"Bcc"});

        final String serialized = new String(output.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(serialized.contains("Bcc:")).isFalse();
        assertThat(serialized.contains("hidden@example.com")).isFalse();
        assertThat(serialized.contains("X-Large:")).isTrue();
        assertThat(serialized.contains("body")).isTrue();
    }

    @Test
    void ordinaryEmlParsingDoesNotCreateTemporaryStorage() throws Exception {
        final byte[] eml = bytes(message(repeat('x', 2 * 1024 * 1024)));
        final Set<Path> before = temporaryMimeFiles();

        final Email parsed = EmailConverter.emlToEmail(new ByteArrayInputStream(eml));

        assertThat(parsed.getPlainText()).contains("xxxx");
        assertThat(temporaryMimeFiles()).isEqualTo(before);
    }

    @Test
    void failedFinalizationReportsTheSerializationFailure() throws Exception {
        final MimeMessage failing = new MimeMessage(Session.getInstance(new Properties())) {
            @Override
            public void writeTo(final OutputStream outputStream) throws IOException, MessagingException {
                outputStream.write(new byte[4096]);
                throw new IOException("simulated serialization failure");
            }
        };

        assertThatThrownBy(() -> FinalizedMimeMessage.finalizeMessage(
                failing, FinalizedMimeMessage.ProtectionState.CONTENT_PROTECTED))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("finalize MIME");
    }

    private static MimeMessage message(final String body) throws Exception {
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, "receiver@example.com");
        message.setSubject("repeatable");
        message.setText(body);
        return message;
    }

    private static byte[] bytes(final MimeMessage message) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        message.writeTo(output);
        return output.toByteArray();
    }

    private static String repeat(final char value, final int count) {
        final char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }

    private static Set<Path> temporaryMimeFiles() throws IOException {
        final Path tempDirectory = Paths.get(System.getProperty("java.io.tmpdir"));
        if (!Files.isDirectory(tempDirectory)) {
            return new HashSet<>();
        }
        try (Stream<Path> files = Files.list(tempDirectory)) {
            return files.filter(path -> path.getFileName().toString().startsWith(REMOVED_TEMP_FILE_PREFIX))
                    .collect(Collectors.toSet());
        }
    }
}
