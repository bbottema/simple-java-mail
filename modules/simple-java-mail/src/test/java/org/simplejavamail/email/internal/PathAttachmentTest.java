package org.simplejavamail.email.internal;

import jakarta.activation.FileTypeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathAttachmentTest {

    @TempDir
    Path directory;

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @Test
    void attachmentIsLazyRepeatableAndReadOnly() throws Exception {
        final Path path = directory.resolve("report.txt");
        final Email email = mail.emailBuilder().startingBlank().withAttachment(path).buildEmail();
        final AttachmentResource attachment = email.getAttachments().get(0);
        final Email copy = mail.emailBuilder().copying(email).buildEmail();

        assertThat(attachment.getName()).isEqualTo("report.txt");
        assertThat(attachment.getDataSource().getContentType()).isEqualTo(FileTypeMap.getDefaultFileTypeMap().getContentType("report.txt"));
        Files.write(path, "created after building".getBytes(UTF_8));
        assertThat(attachment.readAllData()).isEqualTo("created after building");
        assertThat(attachment.readAllData()).isEqualTo("created after building");
        Files.write(path, "updated borrowed content".getBytes(UTF_8));
        assertThat(copy.getAttachments().get(0).readAllData()).isEqualTo("updated borrowed content");
        assertThatThrownBy(() -> attachment.getDataSource().getOutputStream()).isInstanceOf(IOException.class);
    }

    @Test
    void explicitDisplayNameDoesNotChangeContentTypeFromSourceFilename() throws Exception {
        final Path path = directory.resolve("report.txt");
        Files.write(path, "report".getBytes(UTF_8));
        final AttachmentResource attachment = mail.emailBuilder().startingBlank()
                .withAttachment("download.bin", path).buildEmail().getAttachments().get(0);

        assertThat(attachment.getName()).isEqualTo("download.bin");
        assertThat(attachment.getDataSource().getContentType()).isEqualTo(FileTypeMap.getDefaultFileTypeMap().getContentType("report.txt"));
        assertThat(attachment.readAllData()).isEqualTo("report");
    }

    @Test
    void worksWithPathsWithoutFileConversionAndSerializationFreezesContent() throws Exception {
        final URI archiveUri = URI.create("jar:" + directory.resolve("attachments.zip").toUri());
        final byte[] snapshot;
        try (FileSystem zip = FileSystems.newFileSystem(archiveUri, singletonMap("create", "true"))) {
            final Path entry = zip.getPath("/message.txt");
            final Email email = mail.emailBuilder().startingBlank().withAttachment(entry).buildEmail();
            Files.write(entry, "zip content".getBytes(UTF_8));
            assertThat(email.getAttachments().get(0).readAllData()).isEqualTo("zip content");
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
                output.writeObject(email);
            }
            snapshot = bytes.toByteArray();
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(snapshot))) {
            final Email restored = (Email) input.readObject();
            assertThat(restored.getAttachments().get(0).readAllData()).isEqualTo("zip content");
        }
    }

    @Test
    void missingFileFailsWhenContentIsConsumed() {
        final AttachmentResource attachment = mail.emailBuilder().startingBlank()
                .withAttachment(directory.resolve("missing.txt")).buildEmail().getAttachments().get(0);

        assertThatThrownBy(attachment::readAllBytes).hasCauseInstanceOf(IOException.class);
    }
}
