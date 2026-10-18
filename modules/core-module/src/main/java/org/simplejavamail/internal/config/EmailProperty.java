package org.simplejavamail.internal.config;

import org.jetbrains.annotations.ApiStatus;

/**
 * Serialized names retained for Email snapshots written before the public field selector moved to the API package.
 * Application code uses {@link org.simplejavamail.api.email.EmailProperty}; no governance strategy lives in this compatibility type.
 */
@ApiStatus.Internal
public enum EmailProperty {
    HEADERS,
    SUBJECT,
    BODY_TEXT,
    BODY_HTML,
    CALENDAR_METHOD,
    CALENDAR_TEXT,
    ATTACHMENTS,
    EMBEDDED_IMAGES,
    RETURN_RECEIPT_TO,
    DISPOSITION_NOTIFICATION_TO,
    USE_RETURN_RECEIPT_TO,
    USE_DISPOSITION_NOTIFICATION_TO,
    DELIVERY_STATUS_NOTIFICATION,
    TLS_REQUIRED_FOR_ONWARD_DELIVERY,
    SENDING_TO_ACCEPTED_RECIPIENTS,
    CONTENT_TRANSFER_ENCODING,
    PLAIN_TEXT_CONTENT_TRANSFER_ENCODING,
    HTML_TEXT_CONTENT_TRANSFER_ENCODING,
    CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING,
    FROM_RECIPIENT,
    REPLYTO_RECIPIENT,
    BOUNCETO_RECIPIENT,
    ALL_RECIPIENTS,
    TO_RECIPIENTS,
    CC_RECIPIENTS,
    BCC_RECIPIENTS,
    OVERRIDE_RECEIVERS,
    SMIME_SIGNING_CONFIG,
    SMIME_ENCRYPTION_CONFIG,
    OPENPGP_SIGNING_CONFIG,
    OPENPGP_ENCRYPTION_CONFIG,
    DKIM_SIGNING_CONFIG,
    SENT_DATE,
    ID,
    MAIL_TO_FORWARD;
}
