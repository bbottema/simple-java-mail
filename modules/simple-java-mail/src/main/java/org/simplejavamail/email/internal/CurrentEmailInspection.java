package org.simplejavamail.email.internal;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.EmailInspection;
import org.simplejavamail.api.email.ValueAvailability;
import org.simplejavamail.api.email.ValueDerivation;
import org.simplejavamail.api.email.ValueInspection;
import org.simplejavamail.api.email.ValueOrigin;
import org.simplejavamail.internal.email.ReceivedEmailInspection;
import org.simplejavamail.internal.email.ValueInspectionImpl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Tracks explicit edits by field and collection occurrence, without equality, hashing or resource reads. */
final class CurrentEmailInspection {
    enum Field { PLAIN_TEXT, HTML_TEXT, SUBJECT, FROM, TO, CC, BCC, REPLY_TO, BOUNCE_TO, ID, SENT_DATE, CALENDAR }
    private ReceivedEmailInspection current = ReceivedEmailInspection.unknown();

    EmailInspection snapshot() { return current; }

    void retainOriginalMessageProtection(final org.simplejavamail.internal.email.OriginalMessageProtection protection) {
        current = current.toBuilder().originalMessageProtection(java.util.Objects.requireNonNull(protection, "protection")).build();
    }

    void retain(final EmailInspection source, final int attachmentCount, final int imageCount) {
        current = ReceivedEmailInspection.copyOf(source, attachmentCount, imageCount);
    }

    void replace(final Field field, final Object value) {
        final ValueInspection replacement = caller(value);
        final ReceivedEmailInspection.ReceivedEmailInspectionBuilder changed = current.toBuilder();
        switch (field) {
            case PLAIN_TEXT: changed.plainText(replacement); break;
            case HTML_TEXT: changed.htmlText(replacement); break;
            case SUBJECT: changed.subject(replacement); break;
            case FROM: changed.fromRecipient(replacement); break;
            case TO: changed.toRecipients(replacement); break;
            case CC: changed.ccRecipients(replacement); break;
            case BCC: changed.bccRecipients(replacement); break;
            case REPLY_TO: changed.replyToRecipients(replacement); break;
            case BOUNCE_TO: changed.bounceToRecipient(replacement); break;
            case ID: changed.id(replacement); break;
            case SENT_DATE: changed.sentDate(replacement); break;
            case CALENDAR: changed.calendar(replacement); break;
            default: throw new IllegalArgumentException("Unsupported selected field " + field);
        }
        current = changed.build();
    }

    void addAttachment(final AttachmentResource value) {
        final List<ValueInspection> values = new ArrayList<>(current.getAttachments());
        final List<AttachmentResource> originals = new ArrayList<>(current.getOriginalAttachments());
        values.add(caller(value));
        originals.add(null);
        current = current.toBuilder().attachments(values).originalAttachments(originals).build();
    }

    void addEmbeddedImage(final AttachmentResource value) {
        final List<ValueInspection> values = new ArrayList<>(current.getEmbeddedImages());
        values.add(caller(value));
        current = current.toBuilder().embeddedImages(values).build();
    }

    void deriveHTML() {
        final ValueInspection previous = current.getHTMLText();
        final List<org.simplejavamail.api.email.SignatureInspection> signatures = new ArrayList<>();
        for (final org.simplejavamail.api.email.SignatureInspection signature : previous.getSignatures()) {
            signatures.add(org.simplejavamail.internal.email.SignatureInspectionImpl.builder().observation(signature.getObservation())
                    .coverage(signature.getCoverage() == org.simplejavamail.api.email.ProtectionCoverage.UNKNOWN ? org.simplejavamail.api.email.ProtectionCoverage.UNKNOWN
                            : org.simplejavamail.api.email.ProtectionCoverage.PARTIAL).build());
        }
        final List<org.simplejavamail.api.email.DecryptionInspection> decryptions = new ArrayList<>();
        for (final org.simplejavamail.api.email.DecryptionInspection decryption : previous.getDecryptions()) {
            decryptions.add(((org.simplejavamail.internal.email.DecryptionInspectionImpl) org.simplejavamail.internal.email.DecryptionInspectionImpl.copyOf(decryption)).toBuilder()
                    .coverage(decryption.getCoverage() == org.simplejavamail.api.email.ProtectionCoverage.UNKNOWN ? org.simplejavamail.api.email.ProtectionCoverage.UNKNOWN
                            : org.simplejavamail.api.email.ProtectionCoverage.PARTIAL).build());
        }
        current = current.toBuilder().htmlText(ValueInspectionImpl.builder().origin(ValueOrigin.DERIVED).derivation(ValueDerivation.UNKNOWN)
                .availability(ValueAvailability.PRESENT).sources(previous.getSources()).signatures(signatures).decryptions(decryptions).build()).build();
    }

    void addDerivedEmbeddedImage(final AttachmentResource value) {
        final List<ValueInspection> values = new ArrayList<>(current.getEmbeddedImages());
        values.add(ValueInspectionImpl.builder().origin(ValueOrigin.DERIVED).derivation(ValueDerivation.UNKNOWN)
                .availability(ValueAvailability.PRESENT).build());
        current = current.toBuilder().embeddedImages(values).build();
    }

    void clearAttachments() { current = current.toBuilder().attachments(List.of()).originalAttachments(List.of()).build(); }
    void clearEmbeddedImages() { current = current.toBuilder().embeddedImages(List.of()).build(); }

    private static ValueInspection caller(final Object value) {
        final boolean absent = value == null || value instanceof Collection<?> && ((Collection<?>) value).isEmpty();
        return ValueInspectionImpl.builder().origin(ValueOrigin.CALLER_SUPPLIED).derivation(ValueDerivation.NONE)
                .availability(absent ? ValueAvailability.ABSENT : ValueAvailability.PRESENT).build();
    }
}
