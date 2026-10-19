package org.simplejavamail.email.internal;

import jakarta.activation.DataSource;
import jakarta.activation.FileTypeMap;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.util.Objects.requireNonNull;

/** Borrows a path without opening it until attachment content is consumed. */
final class PathAttachmentDataSource implements DataSource {

    private final Path path;

    PathAttachmentDataSource(@NotNull final Path path) {
        this.path = requireNonNull(path, "path");
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        throw new IOException("Path attachments are read-only");
    }

    @Override
    public String getContentType() {
        return FileTypeMap.getDefaultFileTypeMap().getContentType(getName());
    }

    @Override
    public String getName() {
        final Path filename = path.getFileName();
        return filename == null ? path.toString() : filename.toString();
    }
}
