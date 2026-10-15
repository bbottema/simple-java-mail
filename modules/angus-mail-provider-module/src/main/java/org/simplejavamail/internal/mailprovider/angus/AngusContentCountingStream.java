package org.simplejavamail.internal.mailprovider.angus;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.spi.MailTransportProgress;

import java.io.IOException;
import java.io.OutputStream;

/** Counts input handed to an existing stream; never normalizes, buffers or flushes merely to publish progress. */
final class AngusContentCountingStream extends OutputStream {
    private final OutputStream delegate;
    @Nullable private final MailTransportProgress progress;
    private long count;

    AngusContentCountingStream(final OutputStream delegate, @Nullable final MailTransportProgress progress) {
        this.delegate = delegate;
        this.progress = progress;
    }

    @Override
    public void write(final int value) throws IOException {
        delegate.write(value);
        written(1);
    }

    @Override
    public void write(final byte[] bytes, final int offset, final int length) throws IOException {
        delegate.write(bytes, offset, length);
        written(length);
    }

    private void written(final int length) {
        // Only successful writes count. A failed delegate may have written some bytes, but that number is not available to us.
        count = count >= 0 && count <= Long.MAX_VALUE - length ? count + length : -1;
        if (progress != null) {
            progress.contentBytesWritten(count);
        }
    }

    long getCount() {
        return count;
    }

    void finishObservation() {
        if (progress != null) {
            progress.contentTransferFinished(count);
        }
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
