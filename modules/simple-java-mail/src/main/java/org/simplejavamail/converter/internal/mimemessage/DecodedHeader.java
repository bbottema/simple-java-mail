package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.Header;
import lombok.Value;
import lombok.val;

@Value
class DecodedHeader {

    String name;
    String value;

    public static DecodedHeader of(Header h) {
        val decodedName = MimeMessageParser.decodeText(h.getName());
        val decodedValue = MimeMessageParser.decodeText(h.getValue());
        return new DecodedHeader(decodedName, decodedValue);
    }
}
