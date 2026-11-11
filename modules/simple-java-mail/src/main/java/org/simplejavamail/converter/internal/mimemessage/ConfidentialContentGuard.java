package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.email.internal.ConfidentialContentState;
import org.simplejavamail.email.internal.InternalEmail;

/** Checks the completed artifact; internal base rendering has no release boundary of its own. */
public final class ConfidentialContentGuard {
    private ConfidentialContentGuard() { }

    public static void verifyFinalOutput(final Email email, final MimeMessage completed, final boolean conversionPermission)
            throws MessagingException {
        final ConfidentialContentState state = InternalEmail.requireInternalEmail(email).getConfidentialContentState();
        if (!state.isRequired() || state.isUnencryptedContentAllowed() || conversionPermission) return;
        if (hasOutgoingEncryption(email) && isEncryptedEnvelope(completed)) return;
        throw new ConfidentialContentException();
    }

    /** A base-only preview must not turn helper consent into permission to skip configured encryption. */
    public static void verifyBasePreview(final Email email) {
        if (InternalEmail.requireInternalEmail(email).getConfidentialContentState().isRequired() && hasOutgoingEncryption(email)) {
            throw new ConfidentialContentException("This reply or forward includes confidential content and has outgoing encryption configured. "
                    + "Use the secured rehearsal to return its protected output; helper disclosure permission does not permit skipping configured encryption.");
        }
    }

    private static boolean hasOutgoingEncryption(final Email email) {
        return email.getSmimeEncryptionConfig() != null || email.getOpenPgpEncryptionConfig() != null
                || email.getRecipients().stream().anyMatch(recipient -> recipient.getSmimeCertificate() != null);
    }

    public static boolean isEncryptedEnvelope(final MimeMessage completed) throws MessagingException {
        final ContentType type = new ContentType(completed.getContentType());
        return ((type.match("application/pkcs7-mime") || type.match("application/x-pkcs7-mime"))
                    && "enveloped-data".equalsIgnoreCase(type.getParameter("smime-type")))
                || (type.match("multipart/encrypted")
                    && "application/pgp-encrypted".equalsIgnoreCase(type.getParameter("protocol")));
    }

    /** A recognized output refusal, distinct from incoming processing Problems and arbitrary runtime failures. */
    private static final class ConfidentialContentException extends MailException {
        private static final long serialVersionUID = 1L;
        ConfidentialContentException() {
            this("This reply or forward automatically includes confidential content recovered from encryption. "
                    + "Configure outgoing encryption, explicitly clear the reused components, or deliberately allow unencrypted content "
                    + "on this helper operation or conversion.");
        }
        ConfidentialContentException(final String message) { super(MailFailureKind.PREPARATION, message, null, null); }
    }
}
