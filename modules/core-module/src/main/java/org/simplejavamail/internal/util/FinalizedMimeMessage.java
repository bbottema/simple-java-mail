package org.simplejavamail.internal.util;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * A parsed {@link MimeMessage} backed by one finalized RFC 822 representation.
 *
 * <p>The raw representation is authoritative: repeated writes are byte-identical, and {@link #saveChanges()}
 * deliberately does nothing. Pipeline stages create a new instance when they transform protected content.</p>
 */
public final class FinalizedMimeMessage extends MimeMessage implements AutoCloseable {

    public enum ProtectionState {
        NONE,
        CONTENT_PROTECTED,
        FINAL_WIRE_SIGNED
    }

    private static final byte[] CRLF = new byte[] {'\r', '\n'};
    private static final int MAX_HEADER_BYTES = 1024 * 1024;
    @NotNull
    private final RepeatableMimeEntity serialized;
    @NotNull
    private final ProtectionState protectionState;

    private FinalizedMimeMessage(@NotNull final Session session,
                                 @NotNull final RepeatableMimeEntity serialized,
                                 @NotNull final ProtectionState protectionState)
            throws MessagingException {
        super(session, serialized.openSharedInputStream());
        this.serialized = serialized;
        this.protectionState = protectionState;
    }

    /** Finalizes a mutable message, including headers, encodings, boundaries, Date, and Message-ID. */
    @NotNull
    public static FinalizedMimeMessage finalizeMessage(@NotNull final MimeMessage message,
                                                       @NotNull final ProtectionState protectionState)
            throws MessagingException {
        message.saveChanges();
        final RepeatableMimeEntity entity = RepeatableMimeEntity.capture(message::writeTo);
        return fromEntity(message.getSession(), entity, protectionState);
    }

    /** Wraps already-finalized bytes without invoking {@code saveChanges()}. */
    @NotNull
    public static FinalizedMimeMessage fromMessageBytes(@NotNull final Session session,
                                                        final byte @NotNull [] serialized,
                                                        @NotNull final ProtectionState protectionState)
            throws MessagingException {
        try {
            return fromEntity(session, RepeatableMimeEntity.fromBytes(serialized), protectionState);
        } catch (IOException e) {
            throw new MessagingException("Unable to store finalized MIME message", e);
        }
    }

    @NotNull
    private static FinalizedMimeMessage fromEntity(@NotNull final Session session,
                                                   @NotNull final RepeatableMimeEntity entity,
                                                   @NotNull final ProtectionState protectionState)
            throws MessagingException {
        try {
            return new FinalizedMimeMessage(session, entity, protectionState);
        } catch (MessagingException | RuntimeException e) {
            try {
                entity.close();
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    public byte @NotNull [] getSerializedBytes() {
        try {
            return serialized.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read finalized MIME message", e);
        }
    }

    public long getSerializedSize() {
        return serialized.size();
    }

    /** Exposed for lifecycle diagnostics and tests; callers should normally remain storage-agnostic. */
    public boolean usesTemporaryFile() {
        return serialized.isFileBacked();
    }

    /** Exposed for lifecycle diagnostics and tests. */
    public boolean storageAvailable() {
        return serialized.exists();
    }

    @NotNull
    public ProtectionState getProtectionState() {
        return protectionState;
    }

    public boolean requiresStableTransportContent() {
        return protectionState != ProtectionState.NONE;
    }

    @Override
    public void saveChanges() {
        // The serialized form is authoritative and may be covered by a cryptographic signature.
    }

    @Override
    public void writeTo(@NotNull final OutputStream outputStream) throws IOException {
        serialized.writeTo(outputStream);
    }

    @Override
    public void writeTo(@NotNull final OutputStream outputStream, final String[] ignoreList)
            throws IOException {
        if (ignoreList == null || ignoreList.length == 0) {
            writeTo(outputStream);
            return;
        }
        writeWithoutHeaders(outputStream, ignoreList);
    }

    private void writeWithoutHeaders(@NotNull final OutputStream outputStream, @NotNull final String[] ignoreList)
            throws IOException {
        final Set<String> ignored = new HashSet<>();
        for (String header : ignoreList) {
            if (header != null) {
                ignored.add(header.toLowerCase(Locale.ROOT));
            }
        }

        final byte[] headerBytes = readHeaders();
        final int bodyOffset = findHeaderBodySeparator(headerBytes);
        if (bodyOffset < 0) {
            serialized.writeTo(outputStream);
            return;
        }

        final String headers = new String(headerBytes, 0, bodyOffset, StandardCharsets.ISO_8859_1);
        final String[] lines = headers.split("\\r\\n", -1);
        boolean skip = false;
        for (String line : lines) {
            if (!line.isEmpty() && line.charAt(0) != ' ' && line.charAt(0) != '\t') {
                final int colon = line.indexOf(':');
                skip = colon > 0 && ignored.contains(line.substring(0, colon).toLowerCase(Locale.ROOT));
            }
            if (!skip) {
                outputStream.write(line.getBytes(StandardCharsets.ISO_8859_1));
                outputStream.write(CRLF);
            }
        }
        outputStream.write(CRLF);
        try (InputStream body = serialized.openInputStream(bodyOffset + 4L)) {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = body.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
        }
    }

    private byte[] readHeaders() throws IOException {
        try (InputStream input = serialized.openInputStream(0);
             ByteArrayOutputStream headers = new ByteArrayOutputStream()) {
            int matched = 0;
            int value;
            while ((value = input.read()) != -1 && headers.size() <= MAX_HEADER_BYTES) {
                headers.write(value);
                if ((matched == 0 || matched == 2) && value == '\r') {
                    matched++;
                } else if ((matched == 1 || matched == 3) && value == '\n') {
                    matched++;
                    if (matched == 4) {
                        return headers.toByteArray();
                    }
                } else {
                    matched = value == '\r' ? 1 : 0;
                }
            }
            return new byte[0];
        }
    }

    private static int findHeaderBodySeparator(final byte[] bytes) {
        for (int i = 0; i <= bytes.length - 4; i++) {
            if (bytes[i] == '\r' && bytes[i + 1] == '\n' && bytes[i + 2] == '\r' && bytes[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void close() {
        try {
            serialized.close();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to release finalized MIME storage", e);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }
}
