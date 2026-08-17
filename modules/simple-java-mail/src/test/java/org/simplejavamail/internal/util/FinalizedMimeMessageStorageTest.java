package org.simplejavamail.internal.util;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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

    private String originalThreshold;

    @AfterEach
    void restoreThreshold() {
        if (originalThreshold == null) {
            System.clearProperty(RepeatableMimeEntity.MEMORY_THRESHOLD_PROPERTY);
        } else {
            System.setProperty(RepeatableMimeEntity.MEMORY_THRESHOLD_PROPERTY, originalThreshold);
        }
    }

    @Test
    void largeFinalizedMessageIsRepeatableAndDeterministicallyDeleted() throws Exception {
        lowerThresholdTo(32);
        final FinalizedMimeMessage finalized = FinalizedMimeMessage.finalizeMessage(
                message(repeat('x', 4096)), FinalizedMimeMessage.ProtectionState.CONTENT_PROTECTED);

        assertThat(finalized.usesTemporaryFile()).isTrue();
        assertThat(finalized.storageAvailable()).isTrue();
        assertThat(bytes(finalized)).containsExactly(bytes(finalized));
        assertThat(finalized.getSerializedSize()).isEqualTo(bytes(finalized).length);

        finalized.close();

        assertThat(finalized.storageAvailable()).isFalse();
        assertThatThrownBy(() -> bytes(finalized))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("released");
    }

    @Test
    void failedFinalizationRemovesItsTemporaryFile() throws Exception {
        lowerThresholdTo(32);
        final Set<Path> before = temporaryMimeFiles();
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

        assertThat(temporaryMimeFiles()).isEqualTo(before);
    }

    private void lowerThresholdTo(final int threshold) {
        originalThreshold = System.getProperty(RepeatableMimeEntity.MEMORY_THRESHOLD_PROPERTY);
        System.setProperty(RepeatableMimeEntity.MEMORY_THRESHOLD_PROPERTY, Integer.toString(threshold));
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
            return files.filter(path -> path.getFileName().toString().startsWith(RepeatableMimeEntity.TEMP_FILE_PREFIX))
                    .collect(Collectors.toSet());
        }
    }
}
