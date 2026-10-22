package org.simplejavamail.api.email;

/** Selects which Email fields should skip ordinary defaults or overrides. This does not suppress configured locks.
 * @see org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#dontApplyDefaultValueFor(EmailProperty...)
 * @see org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#dontApplyOverrideValueFor(EmailProperty...)
 */
public enum EmailProperty {
    HEADERS,
    SUBJECT,
    BODY_TEXT,
    BODY_HTML,
    /**
     * Skips the template's whole calendar, including its text. The submitted Email's calendar remains.
     * @see #CALENDAR_TEXT
     */
    CALENDAR_METHOD,
    /**
     * Skips the template's whole calendar, including its method. Calendar transfer encoding is selected separately.
     * @see #CALENDAR_METHOD
     */
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
    /**
     * Skips To, Cc and Bcc recipients from ordinary templates together. Recipients supplied on the Email remain;
     * suppress separate delivery-recipient overrides with {@link #OVERRIDE_RECEIVERS} instead.
     */
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
