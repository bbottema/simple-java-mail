package org.simplejavamail.thirdpartyprobe;

import jakarta.mail.util.LineInputStream;
import jakarta.mail.util.LineOutputStream;
import jakarta.mail.util.StreamProvider;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Jakarta Mail requires a StreamProvider even to create a Session, so this fixture supplies just the provider-file line reader.
 * Everything related to message encoding fails deliberately: a connection probe must not need Angus's MIME stream implementation.
 */
public final class FixtureStreamProvider implements StreamProvider {
    @Override
    public LineInputStream inputLineStream(final InputStream input, final boolean allowUtf8) {
        final BufferedReader reader = new BufferedReader(new InputStreamReader(input,
                allowUtf8 ? StandardCharsets.UTF_8 : StandardCharsets.US_ASCII));
        return reader::readLine;
    }

    @Override public InputStream inputBase64(final InputStream input) { throw unexpectedMimeProcessing(); }
    @Override public OutputStream outputBase64(final OutputStream output) { throw unexpectedMimeProcessing(); }
    @Override public InputStream inputBinary(final InputStream input) { throw unexpectedMimeProcessing(); }
    @Override public OutputStream outputBinary(final OutputStream output) { throw unexpectedMimeProcessing(); }
    @Override public OutputStream outputB(final OutputStream output) { throw unexpectedMimeProcessing(); }
    @Override public InputStream inputQ(final InputStream input) { throw unexpectedMimeProcessing(); }
    @Override public OutputStream outputQ(final OutputStream output, final boolean encodingWord) { throw unexpectedMimeProcessing(); }
    @Override public LineOutputStream outputLineStream(final OutputStream output, final boolean allowUtf8) { throw unexpectedMimeProcessing(); }
    @Override public InputStream inputQP(final InputStream input) { throw unexpectedMimeProcessing(); }
    @Override public OutputStream outputQP(final OutputStream output) { throw unexpectedMimeProcessing(); }
    @Override public InputStream inputSharedByteArray(final byte[] bytes) { throw unexpectedMimeProcessing(); }
    @Override public InputStream inputUU(final InputStream input) { throw unexpectedMimeProcessing(); }
    @Override public OutputStream outputUU(final OutputStream output, final String filename) { throw unexpectedMimeProcessing(); }

    private static AssertionError unexpectedMimeProcessing() {
        return new AssertionError("This connection probe unexpectedly needed MIME processing");
    }
}
