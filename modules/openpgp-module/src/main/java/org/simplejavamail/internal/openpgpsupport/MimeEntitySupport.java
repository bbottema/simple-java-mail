package org.simplejavamail.internal.openpgpsupport;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Byte-oriented MIME operations used where a Jakarta Mail object model would destroy signature identity. */
final class MimeEntitySupport {

    private static final byte[] CRLF = new byte[]{'\r', '\n'};
    private static final byte[] HEADER_SEPARATOR = new byte[]{'\r', '\n', '\r', '\n'};

    private MimeEntitySupport() {
    }

    static byte[] serialize(final MimeMessage message) throws MessagingException {
        if (message instanceof org.simplejavamail.internal.util.FinalizedMimeMessage) {
            return ((org.simplejavamail.internal.util.FinalizedMimeMessage) message).getSerializedBytes();
        }
        try {
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            message.writeTo(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new MessagingException("Unable to serialize MIME message", e);
        }
    }

    static MessageParts splitMessage(final byte[] rawMessage) {
        final int separator = findHeaderSeparator(rawMessage);
        if (separator < 0) {
            throw new IllegalArgumentException("MIME message has no header/body separator");
        }
        final int separatorLength = separatorLength(rawMessage, separator);
        final byte[] headerBytes = Arrays.copyOfRange(rawMessage, 0, separator);
        final byte[] bodyBytes = Arrays.copyOfRange(rawMessage, separator + separatorLength, rawMessage.length);
        return splitHeaders(headerBytes, bodyBytes);
    }

    private static MessageParts splitHeaders(final byte[] headerBytes, final byte[] bodyBytes) {
        final List<byte[]> outer = new ArrayList<>();
        final List<byte[]> content = new ArrayList<>();
        for (byte[] field : headerFields(headerBytes)) {
            final String name = headerName(field);
            if (name.startsWith("content-") && !"content-length".equals(name)) {
                content.add(field);
            } else if (!"content-length".equals(name)) {
                outer.add(field);
            }
        }
        return new MessageParts(joinHeaderFields(outer), joinHeaderFields(content), bodyBytes);
    }

    static byte[] contentEntity(final byte[] rawMessage) {
        final MessageParts parts = splitMessage(rawMessage);
        return join(parts.contentHeaders, HEADER_SEPARATOR, parts.body);
    }

    static byte[] wrapSigned(final byte[] rawMessage, final byte[] signature, final String boundary,
                             final String micalg) {
        final MessageParts parts = splitMessage(rawMessage);
        final byte[] entity = join(parts.contentHeaders, HEADER_SEPARATOR, parts.body);
        final String contentType = "Content-Type: multipart/signed; protocol=\"application/pgp-signature\";"
                + " micalg=" + micalg + "; boundary=\"" + boundary + "\"";
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeAscii(body, "--" + boundary + "\r\n");
        write(body, entity);
        write(body, CRLF);
        writeAscii(body, "--" + boundary + "\r\n");
        writeAscii(body, "Content-Type: application/pgp-signature; name=\"signature.asc\"\r\n");
        writeAscii(body, "Content-Description: OpenPGP digital signature\r\n");
        writeAscii(body, "Content-Disposition: attachment; filename=\"signature.asc\"\r\n\r\n");
        write(body, normalizeCrlf(signature));
        if (!endsWithCrlf(signature)) {
            write(body, CRLF);
        }
        writeAscii(body, "--" + boundary + "--\r\n");
        return buildMessage(parts.outerHeaders, contentType, body.toByteArray());
    }

    static byte[] wrapEncrypted(final byte[] rawMessage, final byte[] encryptedData, final String boundary) {
        final MessageParts parts = splitMessage(rawMessage);
        final String contentType = "Content-Type: multipart/encrypted; protocol=\"application/pgp-encrypted\";"
                + " boundary=\"" + boundary + "\"";
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeAscii(body, "--" + boundary + "\r\n");
        writeAscii(body, "Content-Type: application/pgp-encrypted\r\n\r\n");
        writeAscii(body, "Version: 1\r\n");
        writeAscii(body, "--" + boundary + "\r\n");
        writeAscii(body, "Content-Type: application/octet-stream; name=\"encrypted.asc\"\r\n");
        writeAscii(body, "Content-Description: OpenPGP encrypted message\r\n");
        writeAscii(body, "Content-Disposition: inline; filename=\"encrypted.asc\"\r\n\r\n");
        write(body, normalizeCrlf(encryptedData));
        if (!endsWithCrlf(encryptedData)) {
            write(body, CRLF);
        }
        writeAscii(body, "--" + boundary + "--\r\n");
        return buildMessage(parts.outerHeaders, contentType, body.toByteArray());
    }

    static byte[] restoreMessage(final byte[] protectedMessage, final byte[] effectiveContentEntity) {
        final MessageParts protectedParts = splitMessage(protectedMessage);
        final int separator = findHeaderSeparator(effectiveContentEntity);
        if (separator < 0) {
            throw new IllegalArgumentException("Protected MIME entity has no header/body separator");
        }
        final int separatorLength = separatorLength(effectiveContentEntity, separator);
        final byte[] contentHeaders = Arrays.copyOfRange(effectiveContentEntity, 0, separator);
        final byte[] body = Arrays.copyOfRange(effectiveContentEntity, separator + separatorLength,
                effectiveContentEntity.length);
        return join(protectedParts.outerHeaders, CRLF, normalizeHeaderLines(contentHeaders), HEADER_SEPARATOR, body);
    }

    static List<byte[]> multipartParts(final byte[] rawMessage, final String boundary) {
        final MessageParts messageParts = splitMessage(rawMessage);
        final byte[] body = messageParts.body;
        final byte[] marker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        final List<Integer> starts = new ArrayList<>();
        for (int i = 0; i <= body.length - marker.length; i++) {
            if (matches(body, i, marker) && (i == 0 || precededByLineBreak(body, i))) {
                starts.add(i);
            }
        }
        final List<byte[]> result = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            final int delimiterStart = starts.get(i);
            final int afterMarker = delimiterStart + marker.length;
            if (afterMarker + 1 < body.length && body[afterMarker] == '-' && body[afterMarker + 1] == '-') {
                break;
            }
            final int contentStart = afterLine(body, afterMarker);
            if (contentStart < 0 || i + 1 >= starts.size()) {
                break;
            }
            int contentEnd = starts.get(i + 1);
            if (contentEnd >= 2 && body[contentEnd - 2] == '\r' && body[contentEnd - 1] == '\n') {
                contentEnd -= 2;
            } else if (contentEnd >= 1 && body[contentEnd - 1] == '\n') {
                contentEnd--;
            }
            result.add(Arrays.copyOfRange(body, contentStart, contentEnd));
        }
        return result;
    }

    static byte[] partBody(final byte[] mimePart) {
        final int separator = findHeaderSeparator(mimePart);
        if (separator < 0) {
            return mimePart.clone();
        }
        return Arrays.copyOfRange(mimePart, separator + separatorLength(mimePart, separator), mimePart.length);
    }

    static String boundary(final MimeMessage message) throws MessagingException {
        return new ContentType(message.getContentType()).getParameter("boundary");
    }

    private static byte[] buildMessage(final byte[] outerHeaders, final String contentType, final byte[] body) {
        return join(outerHeaders, CRLF, contentType.getBytes(StandardCharsets.ISO_8859_1), HEADER_SEPARATOR, body);
    }

    private static List<byte[]> headerFields(final byte[] rawHeaders) {
        final byte[] normalized = normalizeHeaderLines(rawHeaders);
        final String headers = new String(normalized, StandardCharsets.ISO_8859_1);
        final String[] lines = headers.split("\\r\\n", -1);
        final List<byte[]> fields = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && current.length() > 0) {
                current.append("\r\n").append(line);
            } else {
                if (current.length() > 0) {
                    fields.add(current.toString().getBytes(StandardCharsets.ISO_8859_1));
                }
                current.setLength(0);
                current.append(line);
            }
        }
        if (current.length() > 0) {
            fields.add(current.toString().getBytes(StandardCharsets.ISO_8859_1));
        }
        return fields;
    }

    private static String headerName(final byte[] field) {
        final String text = new String(field, StandardCharsets.ISO_8859_1);
        final int colon = text.indexOf(':');
        return colon < 0 ? "" : text.substring(0, colon).trim().toLowerCase(Locale.ROOT);
    }

    private static byte[] joinHeaderFields(final List<byte[]> fields) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) write(output, CRLF);
            write(output, fields.get(i));
        }
        return output.toByteArray();
    }

    private static byte[] normalizeHeaderLines(final byte[] input) {
        return normalizeCrlf(input);
    }

    static byte[] normalizeCrlf(final byte[] input) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream(input.length + 32);
        for (int i = 0; i < input.length; i++) {
            final byte value = input[i];
            if (value == '\r') {
                output.write('\r');
                output.write('\n');
                if (i + 1 < input.length && input[i + 1] == '\n') i++;
            } else if (value == '\n') {
                output.write('\r');
                output.write('\n');
            } else {
                output.write(value);
            }
        }
        return output.toByteArray();
    }

    private static boolean endsWithCrlf(final byte[] bytes) {
        return bytes.length >= 2 && bytes[bytes.length - 2] == '\r' && bytes[bytes.length - 1] == '\n';
    }

    private static int findHeaderSeparator(final byte[] bytes) {
        for (int i = 0; i <= bytes.length - 4; i++) {
            if (bytes[i] == '\r' && bytes[i + 1] == '\n' && bytes[i + 2] == '\r' && bytes[i + 3] == '\n') {
                return i;
            }
        }
        for (int i = 0; i <= bytes.length - 2; i++) {
            if (bytes[i] == '\n' && bytes[i + 1] == '\n') return i;
        }
        return -1;
    }

    private static int separatorLength(final byte[] bytes, final int offset) {
        return offset + 3 < bytes.length && bytes[offset] == '\r' ? 4 : 2;
    }

    private static int afterLine(final byte[] bytes, final int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == '\n') return i + 1;
        }
        return -1;
    }

    private static boolean precededByLineBreak(final byte[] bytes, final int index) {
        return index > 0 && bytes[index - 1] == '\n';
    }

    private static boolean matches(final byte[] bytes, final int offset, final byte[] candidate) {
        for (int i = 0; i < candidate.length; i++) {
            if (bytes[offset + i] != candidate[i]) return false;
        }
        return true;
    }

    private static byte[] join(final byte[]... values) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) write(output, value);
        return output.toByteArray();
    }

    private static void writeAscii(final ByteArrayOutputStream output, final String value) {
        write(output, value.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void write(final ByteArrayOutputStream output, final byte[] value) {
        output.write(value, 0, value.length);
    }

    static final class MessageParts {
        final byte[] outerHeaders;
        final byte[] contentHeaders;
        final byte[] body;

        MessageParts(final byte[] outerHeaders, final byte[] contentHeaders, final byte[] body) {
            this.outerHeaders = outerHeaders;
            this.contentHeaders = contentHeaders;
            this.body = body;
        }
    }
}
