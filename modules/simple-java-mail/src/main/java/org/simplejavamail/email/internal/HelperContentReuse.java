package org.simplejavamail.email.internal;

import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailInspection;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.ValueInspection;
import org.simplejavamail.api.email.ValueAvailability;
import org.simplejavamail.internal.email.OriginalMessageProtection;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.simplejavamail.email.internal.ConfidentialContentState.Component.*;

/** Derives the particular confidential components automatically reused by a new helper operation. */
final class HelperContentReuse {
    private static final Pattern FORMAT_DIRECTIVE = Pattern.compile("%(?:[1-9]\\d*\\$)?[-#+ 0,(<]*\\d*(?:\\.(\\d+))?([a-zA-Z%])");
    private HelperContentReuse() { }

    static ConfidentialContentState reply(final Email source, final String quotation, final List<ValueInspection> images, final boolean permission) {
        final EmailInspection inspection = source.getInspection();
        final ConfidentialContentState previous = InternalEmail.requireInternalEmail(source).getConfidentialContentState();
        final Set<ConfidentialContentState.Component> required = EnumSet.noneOf(ConfidentialContentState.Component.class);
        final boolean intendedEncryption = hasOutgoingEncryption(source);
        final boolean plain = source.getPlainText() != null
                ? intendedEncryption || confidential(inspection.getPlainText()) || previous.requires(PLAIN_TEXT)
                : source.getHTMLText() != null && (intendedEncryption || confidential(inspection.getHTMLText()) || previous.requires(HTML_TEXT));
        final boolean html = source.getHTMLText() != null
                ? intendedEncryption || confidential(inspection.getHTMLText()) || previous.requires(HTML_TEXT)
                : source.getPlainText() != null && (intendedEncryption || confidential(inspection.getPlainText()) || previous.requires(PLAIN_TEXT));
        if (plain) required.add(PLAIN_TEXT);
        if (html && reusesQuotation(quotation)) required.add(HTML_TEXT);
        if (!images.isEmpty() && (intendedEncryption || previous.requires(EMBEDDED_IMAGES) || images.stream().anyMatch(HelperContentReuse::confidential))) {
            required.add(EMBEDDED_IMAGES);
        }
        return ConfidentialContentState.requiring(required, permission);
    }

    static ConfidentialContentState forward(final Email source, final boolean innerEncrypted, final boolean permission) {
        final Set<ConfidentialContentState.Component> required = EnumSet.noneOf(ConfidentialContentState.Component.class);
        if (privateHeaders(source) || !innerEncrypted && confidentialContent(source)) required.add(FORWARDED_MESSAGE);
        return ConfidentialContentState.requiring(required, permission);
    }

    static String publicSubject(final Email source, final boolean reply) {
        final boolean confidential = confidential(source.getInspection().getSubject());
        final String subject = confidential ? InternalEmail.requireInternalEmail(source).getPublicSubject() : source.getSubject();
        if (confidential && subject == null) return reply ? "Re: Your message" : "Fwd: Message";
        if (reply) return subject != null && subject.regionMatches(true, 0, "Re:", 0, 3) ? subject : "Re: " + (subject == null ? "" : subject);
        return "Fwd: " + (subject == null ? "" : subject);
    }

    static boolean privateThreading(final Email source) {
        // A covered absence of Message-ID can still belong to an encrypted authored view carrying private References.
        return InternalEmail.requireInternalEmail(source).isAuthoredHeaderViewUnavailable()
                || decryptedContributions(source.getInspection().getId()) || privateAncestry(source);
    }

    static boolean originalRequiresProtection(final Email source) {
        final OriginalMessageProtection protection = InternalEmail.requireInternalEmail(source).getOriginalMessageProtection();
        if (protection == OriginalMessageProtection.CONFIDENTIAL) return true;
        if (protection == OriginalMessageProtection.AS_RECEIVED || protection == OriginalMessageProtection.INTACT_PROTECTED) return false;
        return capturedConfidentiality(source);
    }

    static boolean neutralOriginalMetadata(final Email source) {
        final OriginalMessageProtection protection = InternalEmail.requireInternalEmail(source).getOriginalMessageProtection();
        return protection == OriginalMessageProtection.CONFIDENTIAL || protection == OriginalMessageProtection.INTACT_PROTECTED
                || capturedConfidentiality(source);
    }

    private static boolean capturedConfidentiality(final Email source) {
        final EmailInspection inspection = source.getInspection();
        if (inspection.getSmime().getDecryptions().stream().anyMatch(attempt -> attempt.getStatus() == DecryptionStatus.DECRYPTED)
                || inspection.getOpenPgp().getDecryptions().stream().anyMatch(attempt -> attempt.getStatus() == DecryptionStatus.DECRYPTED)) return true;
        for (ValueInspection value : List.of(inspection.getPlainText(), inspection.getHTMLText(), inspection.getSubject(), inspection.getFromRecipient(),
                inspection.getToRecipients(), inspection.getCcRecipients(), inspection.getBccRecipients(), inspection.getReplyToRecipients(),
                inspection.getBounceToRecipient(), inspection.getId(), inspection.getSentDate(), inspection.getCalendar())) {
            if (decryptedContributions(value)) return true;
        }
        for (int index = 0; index < source.getAttachments().size(); index++) if (decryptedContributions(inspection.getAttachment(index))) return true;
        for (int index = 0; index < source.getEmbeddedImages().size(); index++) if (decryptedContributions(inspection.getEmbeddedImage(index))) return true;
        return false;
    }

    private static boolean confidentialContent(final Email source) {
        final EmailInspection inspection = source.getInspection();
        if (InternalEmail.requireInternalEmail(source).getConfidentialContentState().isRequired() || hasOutgoingEncryption(source)
                || confidential(inspection.getPlainText()) || confidential(inspection.getHTMLText()) || confidential(inspection.getCalendar())) return true;
        for (int index = 0; index < source.getAttachments().size(); index++) if (confidential(inspection.getAttachment(index))) return true;
        return confidentialImage(source);
    }

    private static boolean privateHeaders(final Email source) {
        final EmailInspection inspection = source.getInspection();
        return privateAncestry(source) || confidential(inspection.getSubject()) || confidential(inspection.getId()) || confidential(inspection.getSentDate())
                || confidential(inspection.getFromRecipient()) || confidential(inspection.getToRecipients())
                || confidential(inspection.getCcRecipients()) || confidential(inspection.getBccRecipients())
                || confidential(inspection.getReplyToRecipients()) || confidential(inspection.getBounceToRecipient());
    }

    private static boolean privateAncestry(final Email source) {
        final Set<String> confidential = InternalEmail.requireInternalEmail(source).getConfidentialHeaderNames();
        return confidential.contains("references") || confidential.contains("in-reply-to");
    }

    private static boolean confidentialImage(final Email source) {
        for (int index = 0; index < source.getEmbeddedImages().size(); index++) {
            if (confidential(source.getInspection().getEmbeddedImage(index))) return true;
        }
        return false;
    }

    private static boolean confidential(final ValueInspection value) {
        return value.getAvailability() == ValueAvailability.PRESENT && decryptedContributions(value);
    }

    private static boolean decryptedContributions(final ValueInspection value) {
        return value.getEncryptionStatus() == EncryptionStatus.DECRYPTED
                || value.getDecryptions().stream().anyMatch(attempt -> attempt.getStatus() == DecryptionStatus.DECRYPTED);
    }

    private static boolean hasOutgoingEncryption(final Email source) {
        return source.getSmimeEncryptionConfig() != null || source.getOpenPgpEncryptionConfig() != null
                || source.getRecipients().stream().anyMatch(recipient -> recipient.getSmimeCertificate() != null);
    }

    /** The actual String.format call has already accepted this template; inspect its argument-consuming directives only. */
    private static boolean reusesQuotation(final String template) {
        final Matcher directives = FORMAT_DIRECTIVE.matcher(template);
        while (directives.find()) {
            final char conversion = Character.toLowerCase(directives.group(2).charAt(0));
            // %b depends only on the non-null String, whereas %s and %h can disclose content or a content-derived hash.
            if (conversion == 's' || conversion == 'h') {
                final String precision = directives.group(1);
                if (precision == null || precision.chars().anyMatch(digit -> digit != '0')) return true;
            }
        }
        return false;
    }
}
