package org.simplejavamail.api.internal.general;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MessageHeader {
    // taken from: protected jakarta.mail.internet.InternetHeaders constructor
    RECEIVED("Received"),
    RESENT_DATE("Resent-Date"),
    RESENT_FROM("Resent-From"),
    RESENT_SENDER("Resent-Sender"),
    RESENT_TO("Resent-To"),
    RESENT_CC("Resent-Cc"),
    RESENT_BCC("Resent-Bcc"),
    RESENT_MESSAGE_ID("Resent-Message-Id"),
    DATE("Date"),
    FROM("From"),
    SENDER("Sender"),
    REPLY_TO("Reply-To"),
    TO("To"),
    CC("Cc"),
    BCC("Bcc"),
    MESSAGE_ID("Message-Id"),
    SUBJECT("Subject"),
    COMMENTS("Comments"),
    KEYWORDS("Keywords"),
    ERRORS_TO("Errors-To"),
    MIME_VERSION("MIME-Version"),
    CONTENT_TYPE("Content-Type"),
    CONTENT_TRANSFER_ENCODING("Content-Transfer-Encoding"),
    CONTENT_MD5("Content-MD5"),
    CONTENT_LENGTH("Content-Length"),
    COLON(":"),
    STATUS("Status"),
    CONTENT_DISPOSITION("Content-Disposition"),
    SIZE("size"),
    FILENAME("filename"),
    CONTENT_ID("Content-ID"),
    NAME("name"),
    // headers that are not part of the standard but are used by some email clients
    DISPOSITION_NOTIFICATION_TO("Disposition-Notification-To"),
    RETURN_RECEIPT_TO("Return-Receipt-To"),
    RETURN_PATH("Return-Path"),
    // common headers from Google et all that we handle differently
    IN_REPLY_TO("In-Reply-To"),
    REFERENCES("References"),
;
    private final String name;
}