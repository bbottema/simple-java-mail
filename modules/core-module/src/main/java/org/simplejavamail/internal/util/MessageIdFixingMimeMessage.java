package org.simplejavamail.internal.util;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.Nullable;

import static java.lang.String.format;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

/** A mutable MIME assembly message that preserves its caller-supplied or first generated Message-ID. */
public class MessageIdFixingMimeMessage extends MimeMessage {

    @Nullable
    private String messageId;

    public MessageIdFixingMimeMessage(final Session session, @Nullable final String messageId) {
        super(session);
        this.messageId = messageId;
    }

    @Override
    protected void updateMessageID() throws MessagingException {
        if (valueNullOrEmpty(messageId)) {
            super.updateMessageID();
            messageId = getMessageID();
        } else {
            setHeader("Message-ID", messageId);
        }
    }

    @Override
    public String toString() {
        try {
            return format("MimeMessage<id:%s, subject:%s>", super.getMessageID(), super.getSubject());
        } catch (MessagingException e) {
            throw new IllegalStateException("should not reach here");
        }
    }
}
