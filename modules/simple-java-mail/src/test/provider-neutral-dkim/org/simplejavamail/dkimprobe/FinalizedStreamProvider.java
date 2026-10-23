package org.simplejavamail.dkimprobe;

import jakarta.mail.util.LineInputStream;
import jakarta.mail.util.LineOutputStream;
import jakarta.mail.util.StreamProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Minimal test SPI for reading already-finalized 8-bit MIME; no SMTP implementation or Angus utilities. */
public final class FinalizedStreamProvider implements StreamProvider {
    public FinalizedStreamProvider() { }
    public InputStream inputBase64(InputStream in) { return java.util.Base64.getMimeDecoder().wrap(in); }
    public OutputStream outputBase64(OutputStream out) { return java.util.Base64.getMimeEncoder().wrap(out); }
    public InputStream inputBinary(InputStream in) { return in; }
    public OutputStream outputBinary(OutputStream out) { return out; }
    public OutputStream outputB(OutputStream out) { return java.util.Base64.getEncoder().wrap(out); }
    public InputStream inputSharedByteArray(byte[] bytes) { return new ByteArrayInputStream(bytes); }
    public LineInputStream inputLineStream(InputStream in, boolean utf8) {
        return () -> {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int value;
            while ((value = in.read()) != -1 && value != '\n') { line.write(value); }
            if (value == -1 && line.size() == 0) { return null; }
            byte[] bytes = line.toByteArray();
            int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
            return new String(bytes, 0, length, utf8 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
        };
    }
    public LineOutputStream outputLineStream(OutputStream out, boolean utf8) {
        return new LineOutputStream() {
            public void writeln(String value) throws IOException {
                out.write(value.getBytes(utf8 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1));
                writeln();
            }
            public void writeln() throws IOException { out.write('\r'); out.write('\n'); }
            public void write(byte[] bytes) throws IOException { out.write(bytes); }
        };
    }
    public InputStream inputQ(InputStream in) { throw unsupported(); }
    public OutputStream outputQ(OutputStream out, boolean text) { throw unsupported(); }
    public InputStream inputQP(InputStream in) { throw unsupported(); }
    public OutputStream outputQP(OutputStream out) { throw unsupported(); }
    public InputStream inputUU(InputStream in) { throw unsupported(); }
    public OutputStream outputUU(OutputStream out, String name) { throw unsupported(); }
    private static UnsupportedOperationException unsupported() { return new UnsupportedOperationException("Outside this finalized-MIME fixture"); }
}
