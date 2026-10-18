package org.simplejavamail.internal.config;

import org.jetbrains.annotations.ApiStatus;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;

import java.util.function.Function;

/** Internal getter strategies used when resolving selected Email fields against defaults and overrides. */
@RequiredArgsConstructor
@SuppressFBWarnings("SE_BAD_FIELD")
@ApiStatus.Internal
public enum EmailPropertyAccess {

    HEADERS(Email::getHeaders),
    SUBJECT(Email::getSubject),
    BODY_TEXT(Email::getPlainText),
    BODY_HTML(Email::getHTMLText),
    CALENDAR_METHOD(Email::getCalendarMethod),
    CALENDAR_TEXT(Email::getCalendarText),
    ATTACHMENTS(Email::getAttachments),
    EMBEDDED_IMAGES(Email::getEmbeddedImages),
    RETURN_RECEIPT_TO(Email::getReturnReceiptTo),
    DISPOSITION_NOTIFICATION_TO (Email::getDispositionNotificationTo),
    USE_RETURN_RECEIPT_TO(Email::getUseReturnReceiptTo),
    USE_DISPOSITION_NOTIFICATION_TO (Email::getUseDispositionNotificationTo),
    DELIVERY_STATUS_NOTIFICATION(Email::getDeliveryStatusNotification),
    TLS_REQUIRED_FOR_ONWARD_DELIVERY(email -> email.isTlsRequiredForOnwardDelivery() ? Boolean.TRUE : null),
    SENDING_TO_ACCEPTED_RECIPIENTS(Email::getSendingToAcceptedRecipients),
    CONTENT_TRANSFER_ENCODING(Email::getContentTransferEncoding),
    PLAIN_TEXT_CONTENT_TRANSFER_ENCODING(Email::getPlainTextContentTransferEncoding),
    HTML_TEXT_CONTENT_TRANSFER_ENCODING(Email::getHTMLTextContentTransferEncoding),
    CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING(Email::getCalendarTextContentTransferEncoding),
    FROM_RECIPIENT(Email::getFromRecipient),
    REPLYTO_RECIPIENT(Email::getReplyToRecipients),
    BOUNCETO_RECIPIENT(Email::getBounceToRecipient),
    ALL_RECIPIENTS(Email::getRecipients),
    TO_RECIPIENTS(Email::getToRecipients),
    CC_RECIPIENTS(Email::getCcRecipients),
    BCC_RECIPIENTS(Email::getBccRecipients),
    OVERRIDE_RECEIVERS(Email::getOverrideReceivers),
    SMIME_SIGNING_CONFIG(Email::getSmimeSigningConfig),
    SMIME_ENCRYPTION_CONFIG(Email::getSmimeEncryptionConfig),
    OPENPGP_SIGNING_CONFIG(Email::getOpenPgpSigningConfig),
    OPENPGP_ENCRYPTION_CONFIG(Email::getOpenPgpEncryptionConfig),
    DKIM_SIGNING_CONFIG(Email::getDkimConfig),
    SENT_DATE(Email::getSentDate),
    ID(Email::getId),
    MAIL_TO_FORWARD(Email::getEmailToForward);

    private final Function<@NotNull Email, @Nullable Object> getter;

    public static EmailPropertyAccess forProperty(final org.simplejavamail.api.email.EmailProperty property) {
        return valueOf(property.name());
    }

    @SuppressWarnings("unchecked")
    public <T> Function<@NotNull Email, @Nullable T> getGetter() {
        return (Function<@NotNull Email, @Nullable T>) getter;
    }
}
