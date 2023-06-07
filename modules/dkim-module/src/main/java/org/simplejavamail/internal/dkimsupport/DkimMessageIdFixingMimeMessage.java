package org.simplejavamail.internal.dkimsupport;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.utils.mail.dkim.DkimMessage;
import org.simplejavamail.utils.mail.dkim.DkimSigner;

import static java.lang.String.format;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

public class DkimMessageIdFixingMimeMessage extends DkimMessage {
    @Nullable
    private final String messageId;

    public DkimMessageIdFixingMimeMessage(MimeMessage message, DkimSigner signer, @Nullable String messageId) throws MessagingException {
        super(message, signer);
        this.messageId = messageId;
    }

    @Override
    protected void updateMessageID() throws MessagingException {
        if (valueNullOrEmpty(messageId)) {
            super.updateMessageID();
        } else {
            setHeader("Message-ID", messageId);
        }
    }

    @Override
    public String toString() {
        try {
            return format("DkimMessage<id:%s, subject:%s>", super.getMessageID(), super.getSubject());
        } catch (MessagingException e) {
            throw new IllegalStateException("should not reach here");
        }
    }
}