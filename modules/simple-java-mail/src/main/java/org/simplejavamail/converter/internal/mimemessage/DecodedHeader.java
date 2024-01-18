package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.Header;
import lombok.Value;
import lombok.val;

@Value
public class DecodedHeader {

    String name;
    String value;

    public static DecodedHeader of(Header h) {
        return new DecodedHeader(
                MimeMessageParser.decodeText(h.getName()),
                MimeMessageParser.decodeText(h.getValue())
        );
    }
}