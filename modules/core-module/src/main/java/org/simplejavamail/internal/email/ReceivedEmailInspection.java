package org.simplejavamail.internal.email;

import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.ProtectionFamily;
import org.simplejavamail.api.email.ProtectionInspection;
import org.simplejavamail.api.email.ValueInspection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Immutable current-value associations and retained originals, without retaining a parser or parent Email. */
@ApiStatus.Internal
@Getter
public final class ReceivedEmailInspection implements org.simplejavamail.api.email.EmailInspection {
    private static final long serialVersionUID = 1L;
    private static final ReceivedEmailInspection UNKNOWN = builder().build();
    private final ValueInspection plainText;
    private final ValueInspection htmlText;
    private final ValueInspection subject;
    private final ValueInspection fromRecipient;
    private final ValueInspection toRecipients;
    private final ValueInspection ccRecipients;
    private final ValueInspection bccRecipients;
    private final ValueInspection replyToRecipients;
    private final ValueInspection bounceToRecipient;
    private final ValueInspection id;
    private final ValueInspection sentDate;
    private final ValueInspection calendar;
    private final List<ValueInspection> attachments;
    private final List<ValueInspection> embeddedImages;
    private final ProtectionInspection smime;
    private final ProtectionInspection openPgp;
    private final AttachmentResource originalMessage;
    private final OriginalMessageProtection originalMessageProtection;
    private final boolean authoredHeaderViewUnavailable;
    private final List<AttachmentResource> originalAttachments;

    @Builder(toBuilder = true)
    private ReceivedEmailInspection(final ValueInspection plainText, final ValueInspection htmlText, final ValueInspection subject,
            final ValueInspection fromRecipient, final ValueInspection toRecipients, final ValueInspection ccRecipients,
            final ValueInspection bccRecipients, final ValueInspection replyToRecipients, final ValueInspection bounceToRecipient,
            final ValueInspection id, final ValueInspection sentDate, final ValueInspection calendar,
            final List<? extends ValueInspection> attachments, final List<? extends ValueInspection> embeddedImages,
            final ProtectionInspection smime, final ProtectionInspection openPgp, final AttachmentResource originalMessage,
            final OriginalMessageProtection originalMessageProtection, final boolean authoredHeaderViewUnavailable,
            final List<? extends AttachmentResource> originalAttachments) {
        this.plainText = value(plainText);
        this.htmlText = value(htmlText);
        this.subject = value(subject);
        this.fromRecipient = value(fromRecipient);
        this.toRecipients = value(toRecipients);
        this.ccRecipients = value(ccRecipients);
        this.bccRecipients = value(bccRecipients);
        this.replyToRecipients = value(replyToRecipients);
        this.bounceToRecipient = value(bounceToRecipient);
        this.id = value(id);
        this.sentDate = value(sentDate);
        this.calendar = value(calendar);
        this.attachments = values(attachments);
        this.embeddedImages = values(embeddedImages);
        this.smime = family(smime, ProtectionFamily.SMIME);
        this.openPgp = family(openPgp, ProtectionFamily.OPENPGP);
        this.originalMessage = originalMessage;
        this.originalMessageProtection = originalMessageProtection == null ? OriginalMessageProtection.UNKNOWN : originalMessageProtection;
        this.authoredHeaderViewUnavailable = authoredHeaderViewUnavailable;
        this.originalAttachments = originalAttachments == null
                ? Collections.nCopies(this.attachments.size(), null)
                : Collections.unmodifiableList(new ArrayList<>(originalAttachments));
        if (this.originalAttachments.size() != this.attachments.size()) {
            throw new IllegalArgumentException("Original attachment occurrences must match current attachment occurrences");
        }
    }

    @Override public ValueInspection getHTMLText() { return htmlText; }
    @Override public ValueInspection getAttachment(final int index) { return attachments.get(index); }
    @Override public ValueInspection getEmbeddedImage(final int index) { return embeddedImages.get(index); }
    @Override public AttachmentResource getOriginalAttachment(final int index) { return originalAttachments.get(index); }

    /** Older snapshots have no captured original-payload coverage; do not infer protection from the resource's existence. */
    public OriginalMessageProtection getOriginalMessageProtection() {
        return originalMessageProtection == null ? OriginalMessageProtection.UNKNOWN : originalMessageProtection;
    }

    public static ReceivedEmailInspection unknown() {
        return UNKNOWN;
    }

    public static ReceivedEmailInspection unknown(final int attachmentCount, final int imageCount) {
        if (attachmentCount < 0 || imageCount < 0) throw new IllegalArgumentException("Occurrence counts cannot be negative");
        return builder().attachments(Collections.nCopies(attachmentCount, ValueInspectionImpl.unknown()))
                .embeddedImages(Collections.nCopies(imageCount, ValueInspectionImpl.unknown())).build();
    }

    public static ReceivedEmailInspection copyOf(final org.simplejavamail.api.email.EmailInspection source,
            final int attachmentCount, final int imageCount) {
        Objects.requireNonNull(source, "inspection");
        if (source instanceof ReceivedEmailInspection) {
            final ReceivedEmailInspection owned = (ReceivedEmailInspection) source;
            if (owned.attachments.size() != attachmentCount || owned.embeddedImages.size() != imageCount) {
                throw new IllegalArgumentException("Inspection occurrence counts differ from the represented Email");
            }
            return owned;
        }
        final List<ValueInspection> attachments = new ArrayList<>();
        final List<AttachmentResource> originals = new ArrayList<>();
        for (int index = 0; index < attachmentCount; index++) {
            attachments.add(source.getAttachment(index));
            originals.add(source.getOriginalAttachment(index));
        }
        final List<ValueInspection> images = new ArrayList<>();
        for (int index = 0; index < imageCount; index++) images.add(source.getEmbeddedImage(index));
        return builder().plainText(source.getPlainText()).htmlText(source.getHTMLText()).subject(source.getSubject())
                .fromRecipient(source.getFromRecipient()).toRecipients(source.getToRecipients()).ccRecipients(source.getCcRecipients())
                .bccRecipients(source.getBccRecipients()).replyToRecipients(source.getReplyToRecipients()).bounceToRecipient(source.getBounceToRecipient())
                .id(source.getId()).sentDate(source.getSentDate()).calendar(source.getCalendar()).attachments(attachments).embeddedImages(images)
                .smime(source.getSmime()).openPgp(source.getOpenPgp()).originalMessage(source.getOriginalMessage()).originalAttachments(originals).build();
    }

    private static ValueInspection value(final ValueInspection value) {
        return value == null ? ValueInspectionImpl.unknown() : ValueInspectionImpl.copyOf(value);
    }

    private static List<ValueInspection> values(final List<? extends ValueInspection> values) {
        return values == null ? List.of() : List.copyOf(values.stream().map(ValueInspectionImpl::copyOf).collect(Collectors.toList()));
    }

    private static ProtectionInspection family(final ProtectionInspection value, final ProtectionFamily family) {
        if (value == null) return ProtectionInspectionImpl.builder().family(family).build();
        if (value.getFamily() != family) throw new IllegalArgumentException("Protection family does not match its inspection getter");
        return ProtectionInspectionImpl.copyOf(value);
    }
}
