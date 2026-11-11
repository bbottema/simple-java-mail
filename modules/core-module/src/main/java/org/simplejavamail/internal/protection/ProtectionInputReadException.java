package org.simplejavamail.internal.protection;

import jakarta.mail.MessagingException;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Captures failures while opening, reading or closing a selected protection input stream. */
@ApiStatus.Internal
public final class ProtectionInputReadException extends IOException {
    private static final long serialVersionUID = 1L;
    @Nullable private final IOException readFailure;
    @Nullable private final IOException closeFailure;
    private final byte[] capturedBytes;
    private final boolean fullyAcquired;

    private ProtectionInputReadException(final IOException primary, @Nullable final IOException readFailure,
            @Nullable final IOException closeFailure, final byte[] capturedBytes, final boolean fullyAcquired) {
        super("Unable to acquire selected protection input", primary);
        this.readFailure = readFailure;
        this.closeFailure = closeFailure;
        this.capturedBytes = capturedBytes;
        this.fullyAcquired = fullyAcquired;
    }

    @Nullable public IOException getReadFailure() { return readFailure; }
    @Nullable public IOException getCloseFailure() { return closeFailure; }
    public byte[] getCapturedBytes() { return capturedBytes.clone(); }
    public boolean isFullyAcquired() { return fullyAcquired; }

    /** Opens and closes one selected stream, preserving a read failure and suppressed cleanup failure separately. */
    public static byte[] acquire(final InputSource source) throws ProtectionInputReadException {
        final InputStream opened;
        try {
            opened = Objects.requireNonNull(source.open(), "selected input stream");
        } catch (IOException | MessagingException openingFailure) {
            final IOException failure = openingFailure instanceof IOException ? (IOException) openingFailure
                    : new IOException("Unable to open selected protection input", openingFailure);
            throw new ProtectionInputReadException(failure, failure, null, new byte[0], false);
        }
        final TrackedInput input = new TrackedInput(opened);
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        IOException readFailure = null;
        boolean complete = false;
        try (TrackedInput closing = input) {
            try {
                closing.transferTo(captured);
                complete = true;
            } catch (IOException failure) {
                readFailure = failure;
                throw failure;
            }
        } catch (IOException primary) {
            throw new ProtectionInputReadException(primary, readFailure, input.closeFailure, captured.toByteArray(), complete);
        }
        return captured.toByteArray();
    }

    @FunctionalInterface
    public interface InputSource {
        InputStream open() throws IOException, MessagingException;
    }

    private static final class TrackedInput extends FilterInputStream {
        @Nullable private IOException closeFailure;

        private TrackedInput(final InputStream source) { super(source); }

        @Override public void close() throws IOException {
            try {
                super.close();
            } catch (IOException failure) {
                closeFailure = failure;
                throw failure;
            }
        }
    }
}
