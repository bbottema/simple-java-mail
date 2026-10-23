package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.Header;
import jakarta.mail.internet.MimeUtility;
import lombok.Value;

import java.io.UnsupportedEncodingException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Value
public class DecodedHeader {

    private static final Pattern ENCODED_WORD_CHARSET = Pattern.compile("=\\?([^?\\s]+)\\?");

    String name;
    String value;

    public static DecodedHeader of(Header h) {
        final String decodedName = MimeMessageParser.decodeText(h.getName());
        return new DecodedHeader(
                decodedName,
                MimeMessageParser.isGenericRootHeader(decodedName) ? decodeGenericValue(h.getValue()) : MimeMessageParser.decodeText(h.getValue())
        );
    }

    private static String decodeGenericValue(final String original) {
        try {
            return MimeUtility.decodeText(original);
        } catch (final UnsupportedEncodingException failure) {
            if (canDecodeWithAvailableCharsets(original)) {
                // Preserve the whole source value, including any earlier words that could have been decoded.
                return original;
            }
            throw new MimeMessageParseException(MimeMessageParseException.ERROR_DECODING_TEXT, failure);
        }
    }

    private static boolean canDecodeWithAvailableCharsets(final String original) {
        final Matcher charsets = ENCODED_WORD_CHARSET.matcher(original);
        if (!charsets.find()) {
            return false;
        }
        try {
            // Jakarta Mail still owns encoded-word syntax and transfer decoding. A later unknown codec must also fail this probe.
            // The probe's decoded text is discarded; only the original value can be retained as source evidence.
            MimeUtility.decodeText(charsets.replaceAll("=?ISO-8859-1?"));
            return true;
        } catch (final UnsupportedEncodingException otherDecodingFailure) {
            return false;
        }
    }
}
