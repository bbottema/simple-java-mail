package org.simplejavamail.internal.util;

import jakarta.mail.util.LineInputStream;
import jakarta.mail.util.LineOutputStream;
import jakarta.mail.util.SharedByteArrayInputStream;
import jakarta.mail.util.StreamProvider;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * MIME stream primitives needed by the Jakarta Mail API when no full mail implementation is installed.
 * The implementation is deliberately independent of any SMTP provider.
 */
public final class ProviderNeutralStreamProvider implements StreamProvider {

    private static final byte[] CRLF = {'\r', '\n'};
	private static final byte[] BASE64_ALPHABET =
			"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".getBytes(StandardCharsets.US_ASCII);
    @Override
    public InputStream inputBase64(final InputStream in) {
		return Base64.getMimeDecoder().wrap(in);
    }

    @Override
    public OutputStream outputBase64(final OutputStream out) {
		return new Base64EncodingOutputStream(new NonClosingOutputStream(out), 76);
    }

    @Override
    public InputStream inputBinary(final InputStream in) {
		return in;
    }

    @Override
    public OutputStream outputBinary(final OutputStream out) {
		return new NonClosingOutputStream(out);
    }

    @Override
    public OutputStream outputB(final OutputStream out) {
		return new Base64EncodingOutputStream(new NonClosingOutputStream(out), 0);
    }

    @Override
    public InputStream inputQ(final InputStream in) {
		return new QDecoderInputStream(in);
    }

    @Override
    public OutputStream outputQ(final OutputStream out, final boolean encodingWord) {
		return new QEncoderOutputStream(new NonClosingOutputStream(out), encodingWord);
    }

    @Override
    public LineInputStream inputLineStream(final InputStream in, final boolean allowUtf8) {
		return new LocalLineInputStream(in, allowUtf8);
    }

    @Override
    public LineOutputStream outputLineStream(final OutputStream out, final boolean allowUtf8) {
		return new LocalLineOutputStream(out, allowUtf8);
    }

    @Override
    public InputStream inputQP(final InputStream in) {
		return new QuotedPrintableInputStream(in);
    }

    @Override
    public OutputStream outputQP(final OutputStream out) {
		return new QuotedPrintableOutputStream(new NonClosingOutputStream(out));
    }

    @Override
    public InputStream inputSharedByteArray(final byte[] bytes) {
		return new SharedByteArrayInputStream(bytes);
    }

    @Override
    public InputStream inputUU(final InputStream in) {
		return new UuDecoderInputStream(in);
    }

    @Override
    public OutputStream outputUU(final OutputStream out, final String filename) {
		return new UuEncoderOutputStream(new NonClosingOutputStream(out), filename);
    }

	private static final class NonClosingOutputStream extends FilterOutputStream {
		private NonClosingOutputStream(final OutputStream output) {
			super(output);
		}

		@Override
		public void close() throws IOException {
			flush();
		}
	}

	/** Jakarta Mail treats {@link #flush()} as the end of one encoded body. */
	private static final class Base64EncodingOutputStream extends FilterOutputStream {
		private final byte[] pending = new byte[3];
		private final int lineLimit;
		private int pendingCount;
		private int lineLength;

		private Base64EncodingOutputStream(final OutputStream output, final int lineLimit) {
			super(output);
			this.lineLimit = lineLimit;
		}

		@Override
		public void write(final int value) throws IOException {
			pending[pendingCount++] = (byte) value;
			if (pendingCount == pending.length) {
				writeQuantum(3);
				pendingCount = 0;
			}
		}

		@Override
		public void write(final byte[] bytes, final int offset, final int length) throws IOException {
			for (int i = offset; i < offset + length; i++) write(bytes[i]);
		}

		private void writeQuantum(final int count) throws IOException {
			if (lineLimit > 0 && lineLength + 4 > lineLimit) {
				out.write(CRLF);
				lineLength = 0;
			}
			final int first = pending[0] & 0xff;
			final int second = count > 1 ? pending[1] & 0xff : 0;
			final int third = count > 2 ? pending[2] & 0xff : 0;
			out.write(BASE64_ALPHABET[first >>> 2]);
			out.write(BASE64_ALPHABET[(first << 4 | second >>> 4) & 0x3f]);
			out.write(count > 1 ? BASE64_ALPHABET[(second << 2 | third >>> 6) & 0x3f] : '=');
			out.write(count > 2 ? BASE64_ALPHABET[third & 0x3f] : '=');
			lineLength += 4;
		}

		@Override
		public void flush() throws IOException {
			if (pendingCount > 0) {
				writeQuantum(pendingCount);
				pendingCount = 0;
			}
			out.flush();
		}

		@Override
		public void close() throws IOException {
			flush();
			out.close();
		}
	}

    private static final class LocalLineInputStream implements LineInputStream {
        private final PushbackInputStream input;
        private final Charset charset;

        private LocalLineInputStream(final InputStream input, final boolean allowUtf8) {
            this.input = new PushbackInputStream(input, 1);
            this.charset = allowUtf8 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
        }

        @Override
        public String readLine() throws IOException {
            final ByteArrayOutputStream line = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) != -1) {
                if (value == '\n') break;
                if (value == '\r') {
                    final int next = input.read();
                    if (next != '\n' && next != -1) input.unread(next);
                    break;
                }
                line.write(value);
            }
            return value == -1 && line.size() == 0 ? null : new String(line.toByteArray(), charset);
        }
    }

    private static final class LocalLineOutputStream implements LineOutputStream {
        private final OutputStream output;
        private final Charset charset;

        private LocalLineOutputStream(final OutputStream output, final boolean allowUtf8) {
            this.output = output;
            this.charset = allowUtf8 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
        }

        @Override
        public void writeln(final String line) throws IOException {
            write(line.getBytes(charset));
            writeln();
        }

        @Override
        public void writeln() throws IOException {
            output.write(CRLF);
        }

        @Override
        public void write(final byte[] bytes) throws IOException {
            output.write(bytes);
        }
    }

    private static final class QEncoderOutputStream extends FilterOutputStream {
        private final boolean encodingWord;

        private QEncoderOutputStream(final OutputStream out, final boolean encodingWord) {
            super(out);
            this.encodingWord = encodingWord;
        }

        @Override
        public void write(final int value) throws IOException {
            final int unsigned = value & 0xff;
            if (unsigned == ' ') {
                out.write('_');
            } else if (isQSafe(unsigned, encodingWord)) {
                out.write(unsigned);
            } else {
                writeEncoded(out, unsigned);
            }
        }

        @Override
        public void write(final byte[] bytes, final int offset, final int length) throws IOException {
            for (int i = offset; i < offset + length; i++) write(bytes[i]);
        }

        private static boolean isQSafe(final int value, final boolean encodingWord) {
            if (value < 33 || value > 126 || value == '=' || value == '?' || value == '_') return false;
            if (!encodingWord) return true;
            return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                    || value >= '0' && value <= '9' || "!*+-/".indexOf(value) >= 0;
        }
    }

    private static final class QDecoderInputStream extends FilterInputStream {
        private final PushbackInputStream pushback;

        private QDecoderInputStream(final InputStream input) {
            super(new PushbackInputStream(input, 2));
            this.pushback = (PushbackInputStream) in;
        }

        @Override
        public int read() throws IOException {
            final int value = pushback.read();
            if (value == '_') return ' ';
            if (value != '=') return value;
            return decodeHexOrLiteral(pushback);
        }

        @Override
        public int read(final byte[] bytes, final int offset, final int length) throws IOException {
            if (length == 0) return 0;
            int count = 0;
            while (count < length) {
                final int value = read();
                if (value < 0) break;
                bytes[offset + count++] = (byte) value;
            }
            return count == 0 ? -1 : count;
        }
    }

    private static final class QuotedPrintableInputStream extends FilterInputStream {
        private final PushbackInputStream pushback;

        private QuotedPrintableInputStream(final InputStream input) {
            super(new PushbackInputStream(input, 2));
            this.pushback = (PushbackInputStream) in;
        }

        @Override
        public int read() throws IOException {
            final int value = pushback.read();
            if (value != '=') return value;
            final int first = pushback.read();
            if (first == '\n') return read();
            if (first == '\r') {
                final int second = pushback.read();
                if (second == '\n') return read();
                if (second >= 0) pushback.unread(second);
                if (first >= 0) pushback.unread(first);
                return '=';
            }
            if (first >= 0) pushback.unread(first);
            return decodeHexOrLiteral(pushback);
        }

        @Override
        public int read(final byte[] bytes, final int offset, final int length) throws IOException {
            if (length == 0) return 0;
            int count = 0;
            while (count < length) {
                final int value = read();
                if (value < 0) break;
                bytes[offset + count++] = (byte) value;
            }
            return count == 0 ? -1 : count;
        }
    }

    private static int decodeHexOrLiteral(final PushbackInputStream input) throws IOException {
        final int first = input.read();
        final int second = input.read();
        final int high = Character.digit(first, 16);
        final int low = Character.digit(second, 16);
        if (high >= 0 && low >= 0) return high << 4 | low;
        if (second >= 0) input.unread(second);
        if (first >= 0) input.unread(first);
        return '=';
    }

    private static final class QuotedPrintableOutputStream extends FilterOutputStream {
        private int lineLength;
        private boolean pendingCr;
		private final ByteArrayOutputStream pendingWhitespace = new ByteArrayOutputStream();

        private QuotedPrintableOutputStream(final OutputStream output) {
            super(output);
        }

        @Override
        public void write(final int value) throws IOException {
            final int unsigned = value & 0xff;
            if (pendingCr) {
                if (unsigned == '\n') {
					flushWhitespace(true);
                    newline();
                    pendingCr = false;
                    return;
                }
				flushWhitespace(true);
                newline();
                pendingCr = false;
            }
            if (unsigned == '\r') {
                pendingCr = true;
            } else if (unsigned == '\n') {
				flushWhitespace(true);
                newline();
			} else if (unsigned == ' ' || unsigned == '\t') {
				pendingWhitespace.write(unsigned);
            } else if (unsigned >= 33 && unsigned <= 126 && unsigned != '=') {
				flushWhitespace(false);
                token(new byte[]{(byte) unsigned});
            } else {
				flushWhitespace(false);
                final ByteArrayOutputStream encoded = new ByteArrayOutputStream(3);
                writeEncoded(encoded, unsigned);
                token(encoded.toByteArray());
            }
        }

        @Override
        public void write(final byte[] bytes, final int offset, final int length) throws IOException {
            for (int i = offset; i < offset + length; i++) write(bytes[i]);
        }

        private void token(final byte[] token) throws IOException {
            if (lineLength + token.length > 75) {
                out.write('=');
                out.write(CRLF);
                lineLength = 0;
            }
            out.write(token);
            lineLength += token.length;
        }

        private void newline() throws IOException {
            out.write(CRLF);
            lineLength = 0;
        }

        private void flushWhitespace(final boolean trailing) throws IOException {
			final byte[] whitespace = pendingWhitespace.toByteArray();
			pendingWhitespace.reset();
			for (byte value : whitespace) {
				if (trailing) {
					final ByteArrayOutputStream encoded = new ByteArrayOutputStream(3);
					writeEncoded(encoded, value & 0xff);
					token(encoded.toByteArray());
				} else {
					token(new byte[]{value});
				}
			}
		}

		@Override
		public void flush() throws IOException {
			flushWhitespace(true);
			if (pendingCr) {
				newline();
				pendingCr = false;
			}
			out.flush();
		}

        @Override
        public void close() throws IOException {
			flush();
            super.close();
        }
    }

    private static final class UuEncoderOutputStream extends FilterOutputStream {
        private final String filename;
        private final byte[] line = new byte[45];
        private int count;
        private boolean started;

        private UuEncoderOutputStream(final OutputStream output, final String filename) {
            super(output);
            this.filename = filename == null || filename.isEmpty() ? "encoder.buf" : filename;
        }

        @Override
        public void write(final int value) throws IOException {
            start();
            line[count++] = (byte) value;
            if (count == line.length) flushLine();
        }

        @Override
        public void write(final byte[] bytes, final int offset, final int length) throws IOException {
            for (int i = offset; i < offset + length; i++) write(bytes[i]);
        }

        private void start() throws IOException {
            if (!started) {
                out.write(("begin 644 " + filename + "\r\n").getBytes(StandardCharsets.US_ASCII));
                started = true;
            }
        }

        private void flushLine() throws IOException {
            if (count == 0) return;
            out.write(uu(count));
            for (int i = 0; i < count; i += 3) {
                final int a = line[i] & 0xff;
                final int b = i + 1 < count ? line[i + 1] & 0xff : 0;
                final int c = i + 2 < count ? line[i + 2] & 0xff : 0;
                out.write(uu(a >>> 2));
                out.write(uu((a << 4 | b >>> 4) & 0x3f));
                out.write(uu((b << 2 | c >>> 6) & 0x3f));
                out.write(uu(c & 0x3f));
            }
            out.write(CRLF);
            count = 0;
        }

        @Override
        public void close() throws IOException {
            start();
            flushLine();
            out.write('`');
            out.write(CRLF);
            out.write("end\r\n".getBytes(StandardCharsets.US_ASCII));
            super.close();
        }
    }

    private static final class UuDecoderInputStream extends InputStream {
        private final LocalLineInputStream lines;
        private ByteArrayInputStream decoded = new ByteArrayInputStream(new byte[0]);
        private boolean started;
        private boolean finished;

        private UuDecoderInputStream(final InputStream input) {
            this.lines = new LocalLineInputStream(input, false);
        }

        @Override
        public int read() throws IOException {
            while (decoded.available() == 0 && !finished) decodeLine();
            return decoded.read();
        }

        @Override
        public int read(final byte[] bytes, final int offset, final int length) throws IOException {
            if (length == 0) return 0;
            int count = 0;
            while (count < length) {
                final int value = read();
                if (value < 0) break;
                bytes[offset + count++] = (byte) value;
            }
            return count == 0 ? -1 : count;
        }

        private void decodeLine() throws IOException {
            String line;
            while ((line = lines.readLine()) != null) {
                if (!started) {
                    if (line.startsWith("begin ")) started = true;
                    continue;
                }
                if (line.equals("end")) {
                    finished = true;
                    return;
                }
                if (line.isEmpty()) continue;
                final int length = uud(line.charAt(0));
                if (length == 0) continue;
                final ByteArrayOutputStream output = new ByteArrayOutputStream(length);
                int index = 1;
                while (output.size() < length && index + 3 < line.length()) {
                    final int a = uud(line.charAt(index++));
                    final int b = uud(line.charAt(index++));
                    final int c = uud(line.charAt(index++));
                    final int d = uud(line.charAt(index++));
                    output.write(a << 2 | b >>> 4);
                    if (output.size() < length) output.write(b << 4 | c >>> 2);
                    if (output.size() < length) output.write(c << 6 | d);
                }
                decoded = new ByteArrayInputStream(output.toByteArray());
                return;
            }
            finished = true;
        }
    }

    private static int uu(final int value) {
        final int encoded = value & 0x3f;
        return encoded == 0 ? '`' : encoded + 32;
    }

    private static int uud(final int value) {
        return (value - 32) & 0x3f;
    }

    private static void writeEncoded(@NotNull final OutputStream output, final int value) throws IOException {
        final char[] hex = "0123456789ABCDEF".toCharArray();
        output.write('=');
        output.write(hex[value >>> 4 & 0xf]);
        output.write(hex[value & 0xf]);
    }
}
