package org.simplejavamail.internal.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;

import java.util.function.Function;

/**
 * Used internally to fetch properties from a provides email, a defauls email and an overrides email, without having
 * to write the same code over and over again. Thinks of it as a Getter strategy pattern for email properties.
 */
@RequiredArgsConstructor
@Getter
public enum EmailProperty {

    HEADERS(Email::getHeaders, true),
    SUBJECT(Email::getSubject, false),
    BODY_TEXT(Email::getPlainText, false),
    BODY_HTML(Email::getHTMLText, false),
    CALENDAR_METHOD(Email::getCalendarMethod, false),
    CALENDAR_TEXT(Email::getCalendarText, false),
    ATTACHMENTS(Email::getAttachments, true),
    EMBEDDED_IMAGES(Email::getEmbeddedImages, true),
    RETURN_RECEIPT_TO(Email::getReturnReceiptTo, false),
    DISPOSITION_NOTIFICATION_TO (Email::getDispositionNotificationTo, false),
    USE_RETURN_RECEIPT_TO(Email::getUseReturnReceiptTo, false),
    USE_DISPOSITION_NOTIFICATION_TO (Email::getUseDispositionNotificationTo, false),
    CONTENT_TRANSFER_ENCODING(Email::getContentTransferEncoding, false),
    FROM_RECIPIENT(Email::getFromRecipient, false),
    REPLYTO_RECIPIENT(Email::getReplyToRecipients, true),
    BOUNCETO_RECIPIENT(Email::getBounceToRecipient, false),
    ALL_RECIPIENTS(Email::getRecipients, true),
    TO_RECIPIENTS(Email::getToRecipients, true),
    CC_RECIPIENTS(Email::getCcRecipients, true),
    BCC_RECIPIENTS(Email::getBccRecipients, true),
    OVERRIDE_RECEIVERS(Email::getOverrideReceivers, true),
    SMIME_SIGNING_CONFIG(Email::getSmimeSigningConfig, false),
    SMIME_ENCRYPTION_CONFIG(Email::getSmimeEncryptionConfig, false),
    DKIM_SIGNING_CONFIG(Email::getDkimConfig, false),
    SENT_DATE(Email::getSentDate, false),
    ID(Email::getId, false),
    MAIL_TO_FORWARD(Email::getEmailToForward, false);

    private final Function<@NotNull Email, @Nullable Object> getter;

    /**
     * Collections needs to be merged, while single values need to be replaced, hence this flag.
     */
    private final boolean isCollectionValue;

    @SuppressWarnings("unchecked")
    public <T> Function<@NotNull Email, @Nullable T> getGetter() {
        return (Function<@NotNull Email, @Nullable T>) getter;
    }
}