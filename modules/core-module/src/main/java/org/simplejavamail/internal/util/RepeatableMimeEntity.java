package org.simplejavamail.internal.util;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.SharedInputStream;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Repeatable storage for an immutable MIME representation.
 *
 * <p>Small entities stay in memory. Larger entities spill to a private temporary file so parsing and repeated
 * transport writes do not require a second unbounded heap buffer. Instances own their temporary file and must be
 * closed after the final transport write.</p>
 */
final class RepeatableMimeEntity implements Closeable {

    static final String MEMORY_THRESHOLD_PROPERTY = "org.simplejavamail.mime.finalization.memoryThresholdBytes";
    static final int DEFAULT_MEMORY_THRESHOLD = 1024 * 1024;
    static final String TEMP_FILE_PREFIX = "simple-java-mail-mime-";

    private final byte[] memory;
    private final Path temporaryFile;
    private final long size;
    private final FileChannel sharedFileChannel;
    private volatile boolean closed;

    private RepeatableMimeEntity(final byte[] memory, final Path temporaryFile, final long size,
                                 final FileChannel sharedFileChannel) {
        this.memory = memory;
        this.temporaryFile = temporaryFile;
        this.size = size;
        this.sharedFileChannel = sharedFileChannel;
    }

    @NotNull
    static RepeatableMimeEntity fromBytes(final byte @NotNull [] bytes) throws IOException {
        final int threshold = memoryThreshold();
        if (bytes.length <= threshold) {
            return new RepeatableMimeEntity(bytes.clone(), null, bytes.length, null);
        }
        final Path path = createTemporaryFile();
        try {
            Files.write(path, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            return fileBacked(path, bytes.length);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(path);
            throw e;
        }
    }

    @NotNull
    static RepeatableMimeEntity capture(@NotNull final MimeWriter writer) throws MessagingException {
        final SpoolingOutputStream output = new SpoolingOutputStream(memoryThreshold());
        try {
            writer.writeTo(output);
            return output.toEntity();
        } catch (IOException e) {
            output.discard();
            throw new MessagingException("Unable to finalize MIME message", e);
        } catch (MessagingException | RuntimeException e) {
            output.discard();
            throw e;
        }
    }

    @NotNull
    private static RepeatableMimeEntity fileBacked(@NotNull final Path path, final long size) throws IOException {
        path.toFile().deleteOnExit();
        final FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        return new RepeatableMimeEntity(null, path, size, channel);
    }

    @NotNull
    private static Path createTemporaryFile() throws IOException {
        return Files.createTempFile(TEMP_FILE_PREFIX, ".eml");
    }

    private static int memoryThreshold() {
        final String configured = System.getProperty(MEMORY_THRESHOLD_PROPERTY);
        if (configured == null) {
            return DEFAULT_MEMORY_THRESHOLD;
        }
        try {
            final int threshold = Integer.parseInt(configured);
            return threshold >= 0 ? threshold : DEFAULT_MEMORY_THRESHOLD;
        } catch (NumberFormatException ignored) {
            return DEFAULT_MEMORY_THRESHOLD;
        }
    }

    long size() {
        return size;
    }

    boolean isFileBacked() {
        return temporaryFile != null;
    }

    boolean exists() {
        return temporaryFile == null || Files.exists(temporaryFile);
    }

    @NotNull
    InputStream openSharedInputStream() {
        ensureOpen();
        return memory != null
                ? new SharedMemoryInputStream(memory, 0, memory.length)
                : new SharedFileInputStream(this, 0, size);
    }

    @NotNull
    InputStream openInputStream(final long offset) throws IOException {
        ensureOpen();
        if (offset < 0 || offset > size) {
            throw new IOException("Invalid MIME entity offset: " + offset);
        }
        if (memory != null) {
            return new java.io.ByteArrayInputStream(memory, (int) offset, memory.length - (int) offset);
        }
        final InputStream input = Files.newInputStream(temporaryFile);
        long remaining = offset;
        while (remaining > 0) {
            final long skipped = input.skip(remaining);
            if (skipped <= 0) {
                input.close();
                throw new IOException("Unable to seek repeatable MIME entity");
            }
            remaining -= skipped;
        }
        return input;
    }

    void writeTo(@NotNull final OutputStream output) throws IOException {
        try (InputStream input = openInputStream(0)) {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    byte @NotNull [] readAllBytes() throws IOException {
        if (size > Integer.MAX_VALUE) {
            throw new IOException("MIME entity is too large to materialize as a byte array");
        }
        final ByteArrayOutputStream output = new ByteArrayOutputStream((int) size);
        writeTo(output);
        return output.toByteArray();
    }

    private int read(final long position, @NotNull final byte[] target, final int offset, final int length)
            throws IOException {
        ensureOpen();
        if (position >= size) {
            return -1;
        }
        final int requested = (int) Math.min(length, size - position);
        final ByteBuffer buffer = ByteBuffer.wrap(target, offset, requested);
        int total = 0;
        while (buffer.hasRemaining()) {
            final int read = sharedFileChannel.read(buffer, position + total);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                continue;
            }
            total += read;
        }
        return total == 0 ? -1 : total;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Finalized MIME storage has already been released");
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        if (sharedFileChannel != null) {
            try {
                sharedFileChannel.close();
            } catch (IOException e) {
                failure = e;
            }
        }
        if (temporaryFile != null) {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    interface MimeWriter {
        void writeTo(OutputStream outputStream) throws IOException, MessagingException;
    }

    private static final class SpoolingOutputStream extends OutputStream {
        private final int threshold;
        private ByteArrayOutputStream memory = new ByteArrayOutputStream();
        private OutputStream fileOutput;
        private Path path;
        private long count;
        private boolean handedOff;

        private SpoolingOutputStream(final int threshold) {
            this.threshold = threshold;
        }

        @Override
        public void write(final int value) throws IOException {
            ensureCapacity(1);
            current().write(value);
            count++;
        }

        @Override
        public void write(final byte[] bytes, final int offset, final int length) throws IOException {
            ensureCapacity(length);
            current().write(bytes, offset, length);
            count += length;
        }

        private void ensureCapacity(final int additional) throws IOException {
            if (fileOutput == null && count + additional > threshold) {
                path = createTemporaryFile();
                try {
                    fileOutput = Files.newOutputStream(path, StandardOpenOption.TRUNCATE_EXISTING);
                    memory.writeTo(fileOutput);
                    memory = null;
                } catch (IOException | RuntimeException e) {
                    if (fileOutput != null) {
                        try {
                            fileOutput.close();
                        } catch (IOException ignored) {
                            // preserve the original failure
                        }
                    }
                    Files.deleteIfExists(path);
                    throw e;
                }
            }
        }

        private OutputStream current() {
            return fileOutput == null ? memory : fileOutput;
        }

        @NotNull
        private RepeatableMimeEntity toEntity() throws IOException {
            if (handedOff) {
                throw new IllegalStateException("MIME entity storage has already been handed off");
            }
            handedOff = true;
            if (fileOutput == null) {
                return new RepeatableMimeEntity(memory.toByteArray(), null, count, null);
            }
            fileOutput.close();
            fileOutput = null;
            try {
                return fileBacked(path, count);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(path);
                throw e;
            }
        }

        private void discard() {
            if (fileOutput != null) {
                try {
                    fileOutput.close();
                } catch (IOException ignored) {
                    // best effort while handling the original failure
                }
            }
            if (path != null) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    path.toFile().deleteOnExit();
                }
            }
        }
    }

    private static final class SharedMemoryInputStream extends InputStream implements SharedInputStream {
        private final byte[] bytes;
        private final int start;
        private final int end;
        private int position;
        private int mark;

        private SharedMemoryInputStream(final byte[] bytes, final int start, final int end) {
            this.bytes = bytes;
            this.start = start;
            this.end = end;
            this.position = start;
            this.mark = start;
        }

        @Override
        public int read() {
            return position < end ? bytes[position++] & 0xff : -1;
        }

        @Override
        public int read(final byte[] target, final int offset, final int length) {
            if (position >= end) {
                return -1;
            }
            final int actual = Math.min(length, end - position);
            System.arraycopy(bytes, position, target, offset, actual);
            position += actual;
            return actual;
        }

        @Override
        public long getPosition() {
            return position - start;
        }

		@Override
		public int available() {
			return end - position;
		}

		@Override
		public synchronized void mark(final int readLimit) {
			mark = position;
		}

		@Override
		public synchronized void reset() {
			position = mark;
		}

		@Override
		public boolean markSupported() {
			return true;
		}

        @Override
        public InputStream newStream(final long relativeStart, final long relativeEnd) {
            final int newStart = start + checkedRange(relativeStart, end - start);
            final int newEnd = relativeEnd < 0 ? end : start + checkedRange(relativeEnd, end - start);
            if (newEnd < newStart) {
                throw new IllegalArgumentException("Shared stream end precedes start");
            }
            return new SharedMemoryInputStream(bytes, newStart, newEnd);
        }
    }

    private static final class SharedFileInputStream extends InputStream implements SharedInputStream {
        private final RepeatableMimeEntity entity;
        private final long start;
        private final long end;
        private long position;
        private long mark;

        private SharedFileInputStream(final RepeatableMimeEntity entity, final long start, final long end) {
            this.entity = entity;
            this.start = start;
            this.end = end;
            this.position = start;
            this.mark = start;
        }

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];
            final int read = read(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(final byte[] target, final int offset, final int length) throws IOException {
            if (position >= end) {
                return -1;
            }
            final int actual = entity.read(position, target, offset, (int) Math.min(length, end - position));
            if (actual > 0) {
                position += actual;
            }
            return actual;
        }

        @Override
        public long getPosition() {
            return position - start;
        }

		@Override
		public int available() {
			return (int) Math.min(Integer.MAX_VALUE, end - position);
		}

		@Override
		public synchronized void mark(final int readLimit) {
			mark = position;
		}

		@Override
		public synchronized void reset() {
			position = mark;
		}

		@Override
		public boolean markSupported() {
			return true;
		}

        @Override
        public InputStream newStream(final long relativeStart, final long relativeEnd) {
            final long newStart = start + checkedLongRange(relativeStart, end - start);
            final long newEnd = relativeEnd < 0 ? end : start + checkedLongRange(relativeEnd, end - start);
            if (newEnd < newStart) {
                throw new IllegalArgumentException("Shared stream end precedes start");
            }
            return new SharedFileInputStream(entity, newStart, newEnd);
        }
    }

    private static int checkedRange(final long value, final long length) {
        if (value < 0 || value > length || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Shared stream position out of range: " + value);
        }
        return (int) value;
    }

    private static long checkedLongRange(final long value, final long length) {
        if (value < 0 || value > length) {
            throw new IllegalArgumentException("Shared stream position out of range: " + value);
        }
        return value;
    }
}
