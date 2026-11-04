package org.simplejavamail.converter.internal.mimemessage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.simplejavamail.internal.util.InterruptedIoGuard.rethrowIfInterrupted;

/** Selected MIME-reader work that can preserve acquired bytes when reading or interpretation fails. */
final class CapturedMimeContent {
    private CapturedMimeContent() { }

    static byte[] read(final InputStream stream) throws Failure {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean fullyRead = false;
        try (InputStream selected = stream) {
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = selected.read(buffer)) != -1) {
                if (count == 0) {
                    final int next = selected.read();
                    if (next == -1) break;
                    output.write(next);
                } else {
                    output.write(buffer, 0, count);
                }
            }
            fullyRead = true;
        } catch (final IOException failure) {
            rethrowIfInterrupted(failure);
            throw new Failure(output.toByteArray(), fullyRead, false, failure);
        }
        return output.toByteArray();
    }

    static String decode(final byte[] bytes, final Charset encoding) throws Failure {
        try {
            return encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (final CharacterCodingException failure) {
            throw new Failure(bytes, true, true, failure);
        }
    }

    static Failure failureIn(final Throwable failure) {
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof Failure) return (Failure) cause;
        }
        return null;
    }

    /** Temporary failure data; source snapshots extract only the captured bytes, never this exception or a live reader. */
    @SuppressWarnings("serial")
    static final class Failure extends IOException {
        private final byte[] captured;
        private final boolean fullyCaptured;
        private final boolean interpretationFailure;

        private Failure(final byte[] captured, final boolean fullyCaptured, final boolean interpretationFailure, final IOException cause) {
            super(cause.getMessage(), cause);
            this.captured = captured;
            this.fullyCaptured = fullyCaptured;
            this.interpretationFailure = interpretationFailure;
        }

        byte[] getCaptured() { return captured; }
        boolean isFullyCaptured() { return fullyCaptured; }
        boolean isInterpretationFailure() { return interpretationFailure; }
    }
}
