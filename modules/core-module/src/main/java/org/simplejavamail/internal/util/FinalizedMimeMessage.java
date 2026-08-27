package org.simplejavamail.internal.util;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.SharedByteArrayInputStream;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * A parsed {@link MimeMessage} backed by one finalized in-memory RFC 822 representation.
 *
 * <p>The raw representation is authoritative: repeated writes are byte-identical, and {@link #saveChanges()} deliberately does nothing.
 * Cryptographic pipeline stages create a new instance when they transform or sign content. Exact EML additionally retains headers that a mail
 * provider would normally omit while submitting.</p>
 */
public final class FinalizedMimeMessage extends MimeMessage {

    public enum ProtectionState {
        NONE,
        CONTENT_PROTECTED,
        FINAL_WIRE_SIGNED
    }

    private static final byte[] CRLF = new byte[] {'\r', '\n'};
    private final byte @NotNull [] serialized;
    @NotNull
    private final ProtectionState protectionState;
    private final boolean preserveAllBytes;

    private FinalizedMimeMessage(@NotNull final Session session,
                                 final byte @NotNull [] serialized,
                                 @NotNull final ProtectionState protectionState,
                                 final boolean preserveAllBytes)
            throws MessagingException {
        super(session, new SharedByteArrayInputStream(serialized));
        this.serialized = serialized;
        this.protectionState = protectionState;
        this.preserveAllBytes = preserveAllBytes;
    }

    /** Finalizes a message for cryptographic processing, including headers, encodings, boundaries, Date, and Message-ID. */
    @NotNull
    public static FinalizedMimeMessage finalizeMessage(@NotNull final MimeMessage message,
                                                       @NotNull final ProtectionState protectionState)
            throws MessagingException {
        message.saveChanges();
        try {
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            message.writeTo(output);
            return fromOwnedBytes(message.getSession(), output.toByteArray(), protectionState, false);
        } catch (IOException e) {
            throw new MessagingException("Unable to finalize MIME message", e);
        }
    }

    /** Wraps already-finalized cryptographic-pipeline bytes without invoking {@code saveChanges()}. */
    @NotNull
    public static FinalizedMimeMessage fromMessageBytes(@NotNull final Session session,
                                                        final byte @NotNull [] serialized,
                                                        @NotNull final ProtectionState protectionState)
            throws MessagingException {
        return fromOwnedBytes(session, serialized.clone(), protectionState, false);
    }

    /** Wraps canonical EML bytes whose complete representation, including normally ignored headers, is authoritative. */
    @NotNull
    public static FinalizedMimeMessage fromExactMessageBytes(@NotNull final Session session,
                                                             final byte @NotNull [] serialized)
            throws MessagingException {
        return fromOwnedBytes(session, serialized.clone(), ProtectionState.NONE, true);
    }

    @NotNull
    private static FinalizedMimeMessage fromOwnedBytes(@NotNull final Session session,
                                                       final byte @NotNull [] serialized,
                                                       @NotNull final ProtectionState protectionState,
                                                       final boolean preserveAllBytes)
            throws MessagingException {
        return new FinalizedMimeMessage(session, serialized, protectionState, preserveAllBytes);
    }

    public byte @NotNull [] getSerializedBytes() {
        return serialized.clone();
    }

    public long getSerializedSize() {
        return serialized.length;
    }

    @NotNull
    public ProtectionState getProtectionState() {
        return protectionState;
    }

    @Override
    public void saveChanges() {
        // The serialized form is authoritative and may be covered by a cryptographic signature.
    }

    @Override
    public void writeTo(@NotNull final OutputStream outputStream) throws IOException {
        outputStream.write(serialized);
    }

    @Override
    public void writeTo(@NotNull final OutputStream outputStream, final String[] ignoreList)
            throws IOException {
        if (preserveAllBytes || ignoreList == null || ignoreList.length == 0) {
            writeTo(outputStream);
            return;
        }
        writeWithoutHeaders(outputStream, ignoreList);
    }

    private void writeWithoutHeaders(@NotNull final OutputStream outputStream, @NotNull final String[] ignoreList)
            throws IOException {
        final int bodyOffset = findHeaderBodySeparator(serialized);
        if (bodyOffset < 0) {
            outputStream.write(serialized);
            return;
        }

        final Set<String> ignored = new HashSet<>();
        for (String header : ignoreList) {
            if (header != null) {
                ignored.add(header.toLowerCase(Locale.ROOT));
            }
        }

        final String headers = new String(serialized, 0, bodyOffset, StandardCharsets.ISO_8859_1);
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
        outputStream.write(serialized, bodyOffset + 4, serialized.length - bodyOffset - 4);
    }

    private static int findHeaderBodySeparator(final byte[] bytes) {
        for (int i = 0; i <= bytes.length - 4; i++) {
            if (bytes[i] == '\r' && bytes[i + 1] == '\n' && bytes[i + 2] == '\r' && bytes[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }
}
