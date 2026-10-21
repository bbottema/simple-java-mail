package org.simplejavamail.internal.util;

import jakarta.activation.DataSource;
import jakarta.activation.FileTypeMap;
import jakarta.mail.EncodingAware;
import lombok.Builder;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/** Library-controlled sources whose identity can be compared without consuming borrowed content. */
public final class AttachmentDataSources {

    private AttachmentDataSources() {
    }

    public static DataSource fromPath(final Path path) {
        return new PathSource(path);
    }

    /** Creates an owned, read-only snapshot, including the optional encoding retained by Java serialization. */
    @Builder(builderMethodName = "byteSource")
    private static DataSource fromBytes(final byte[] data, @Nullable final String name,
            @Nullable final String contentType, @Nullable final String encoding) {
        return new ByteSource(data, name, contentType, encoding);
    }

    static boolean sameSource(final DataSource first, final DataSource second) {
        if (first == second) {
            return true;
        }
        if (first instanceof PathSource && second instanceof PathSource) {
            return ((PathSource) first).samePath((PathSource) second);
        }
        if (first instanceof ByteSource && second instanceof ByteSource) {
            return ((ByteSource) first).sameContent((ByteSource) second);
        }
        // Do not call user-provided equals, metadata getters or streams to discover identity.
        return false;
    }

    private static final class PathSource implements DataSource {
        private final Path path;

        private PathSource(final Path path) {
            this.path = requireNonNull(path, "path");
        }

        private boolean samePath(final PathSource other) {
            // In particular, resolving symlinks or normalizing '..' could change the referenced file.
            return path.getFileSystem() == other.path.getFileSystem() && path.equals(other.path);
        }

        @Override public InputStream getInputStream() throws IOException { return Files.newInputStream(path); }
        @Override public OutputStream getOutputStream() throws IOException { throw new IOException("Path attachments are read-only"); }
        @Override public String getContentType() { return FileTypeMap.getDefaultFileTypeMap().getContentType(getName()); }

        @Override
        public String getName() {
            final Path filename = path.getFileName();
            return filename == null ? path.toString() : filename.toString();
        }
    }

    private static final class ByteSource implements DataSource, EncodingAware {
        private final byte[] data;
        private final String name;
        private final String contentType;
        private final String encoding;

        private ByteSource(final byte[] data, final String name, final String contentType, final String encoding) {
            this.data = data.clone();
            this.name = name;
            this.contentType = contentType;
            this.encoding = encoding;
        }

        private boolean sameContent(final ByteSource other) {
            return Objects.equals(name, other.name) && Objects.equals(contentType, other.contentType)
                    && Objects.equals(encoding, other.encoding) && Arrays.equals(data, other.data);
        }

        @Override public InputStream getInputStream() { return new ByteArrayInputStream(data); }
        @Override public OutputStream getOutputStream() throws IOException { throw new IOException("Attachment byte snapshots are read-only"); }
        @Override public String getContentType() { return contentType; }
        @Override public String getName() { return name; }
        @Override public String getEncoding() { return encoding; }
    }
}
