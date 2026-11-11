package org.simplejavamail.email.internal;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailInspection;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.ValueInspection;
import org.simplejavamail.api.internal.general.MessageHeader;
import org.simplejavamail.email.internal.EmailConfigurationResolver.CollectionOccurrence;
import org.simplejavamail.email.internal.EmailConfigurationResolver.CollectionSelection;
import org.simplejavamail.internal.configresolution.ConfigurationOrigin;
import org.simplejavamail.internal.configresolution.ConfigurationResolution;
import org.simplejavamail.internal.email.ReceivedEmailInspection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

/** Restores submitted input history and only the current-value associations that actually survive governance selection. */
public final class GovernedEmailInspection {
    private GovernedEmailInspection() { }

    public static void restoreReceivedAssociations(final InternalEmailPopulatingBuilder target, final Email provided,
            final Email defaults, final Email overrides, final Map<EmailProperty, CollectionSelection<?>> collections,
            final Predicate<EmailProperty> forcedReplacement) {
        if (provided == null) return;
        final EmailInspection history = provided.getInspection();
        final ReceivedEmailInspection current = ReceivedEmailInspection.copyOf(target.getSelectionInspection(),
                target.getAttachments().size(), target.getEmbeddedImages().size());
        final ReceivedEmailInspection.ReceivedEmailInspectionBuilder selected = current.toBuilder()
                .originalMessage(history.getOriginalMessage()).smime(history.getSmime()).openPgp(history.getOpenPgp())
                .originalMessageProtection(InternalEmail.requireInternalEmail(provided).getOriginalMessageProtection())
                .authoredHeaderViewUnavailable(InternalEmail.requireInternalEmail(provided).isAuthoredHeaderViewUnavailable());
        if (retains(provided, defaults, overrides, EmailProperty.BODY_TEXT, Email::getPlainText, forcedReplacement)) selected.plainText(history.getPlainText());
        if (retains(provided, defaults, overrides, EmailProperty.BODY_HTML, Email::getHTMLText, forcedReplacement)) selected.htmlText(history.getHTMLText());
        if (retains(provided, defaults, overrides, EmailProperty.SUBJECT, Email::getSubject, forcedReplacement)) selected.subject(history.getSubject());
        if (retains(provided, defaults, overrides, EmailProperty.FROM_RECIPIENT, Email::getFromRecipient, forcedReplacement)) selected.fromRecipient(history.getFromRecipient());
        if (retains(provided, defaults, overrides, EmailProperty.BOUNCETO_RECIPIENT, Email::getBounceToRecipient, forcedReplacement)) selected.bounceToRecipient(history.getBounceToRecipient());
        if (retains(provided, defaults, overrides, EmailProperty.ID, Email::getId, forcedReplacement)) selected.id(history.getId());
        if (retains(provided, defaults, overrides, EmailProperty.SENT_DATE, Email::getSentDate, forcedReplacement)) selected.sentDate(history.getSentDate());
        final Email calendarSource = EmailConfigurationResolver.calendarSource(provided, defaults, overrides);
        if (!forcedReplacement.test(EmailProperty.CALENDAR_TEXT) && (calendarSource == null || calendarSource == provided)) selected.calendar(history.getCalendar());
        if (unchanged(collections.get(EmailProperty.TO_RECIPIENTS), provided.getToRecipients().size(), EmailProperty.TO_RECIPIENTS, forcedReplacement)) selected.toRecipients(history.getToRecipients());
        if (unchanged(collections.get(EmailProperty.CC_RECIPIENTS), provided.getCcRecipients().size(), EmailProperty.CC_RECIPIENTS, forcedReplacement)) selected.ccRecipients(history.getCcRecipients());
        if (unchanged(collections.get(EmailProperty.BCC_RECIPIENTS), provided.getBccRecipients().size(), EmailProperty.BCC_RECIPIENTS, forcedReplacement)) selected.bccRecipients(history.getBccRecipients());
        if (unchanged(collections.get(EmailProperty.REPLYTO_RECIPIENT), provided.getReplyToRecipients().size(), EmailProperty.REPLYTO_RECIPIENT, forcedReplacement)) selected.replyToRecipients(history.getReplyToRecipients());
        restoreAttachments(selected, current, history, collections.get(EmailProperty.ATTACHMENTS));
        restoreImages(selected, current, history, collections.get(EmailProperty.EMBEDDED_IMAGES));
        target.retainInspection(provided.getSourceData(), provided.getProblems());
        target.retainSelectionInspection(selected.build());
        target.retainPublicSubject(InternalEmail.requireInternalEmail(provided).getPublicSubject());
        restoreCapturedReceiptGroups(target, provided, defaults, overrides, forcedReplacement);
    }

    /** Preserve actual input grouping only when both typed request and destination declarations remain submitted-owned. */
    private static void restoreCapturedReceiptGroups(final InternalEmailPopulatingBuilder target, final Email provided,
            final Email defaults, final Email overrides, final Predicate<EmailProperty> forcedReplacement) {
        final Set<String> captured = InternalEmail.requireInternalEmail(provided).getCapturedReceiptGroups();
        if (captured.isEmpty()) return;
        final Set<String> retained = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        final String disposition = MessageHeader.DISPOSITION_NOTIFICATION_TO.getName();
        if (captured.contains(disposition)
                && retains(provided, defaults, overrides, EmailProperty.USE_DISPOSITION_NOTIFICATION_TO, Email::getUseDispositionNotificationTo, forcedReplacement)
                && retains(provided, defaults, overrides, EmailProperty.DISPOSITION_NOTIFICATION_TO,
                        email -> suppliedRecipients(email.getDispositionNotificationToRecipients()), forcedReplacement)) retained.add(disposition);
        final String receipt = MessageHeader.RETURN_RECEIPT_TO.getName();
        if (captured.contains(receipt)
                && retains(provided, defaults, overrides, EmailProperty.USE_RETURN_RECEIPT_TO, Email::getUseReturnReceiptTo, forcedReplacement)
                && retains(provided, defaults, overrides, EmailProperty.RETURN_RECEIPT_TO,
                        email -> suppliedRecipients(email.getReturnReceiptToRecipients()), forcedReplacement)) retained.add(receipt);
        if (retained.isEmpty()) return;
        final Map<String, Collection<String>> readHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        readHeaders.putAll(target.getHeaders());
        final Map<String, Collection<String>> suppliedHeaders = provided.getHeaders();
        for (String name : retained) readHeaders.put(name, suppliedHeaders.get(name));
        target.retainReceivedHeaders(readHeaders);
        target.retainCapturedReceiptGroups(retained);
    }

    private static List<?> suppliedRecipients(final List<?> recipients) {
        return recipients.isEmpty() ? null : recipients;
    }

    /** Lock application rebuilds recipient lists even when only an unrelated policy is active. */
    public static void restoreUntouchedRecipientsAfterLocks(final InternalEmailPopulatingBuilder target, final Email provided,
            final Predicate<EmailProperty> forcedReplacement) {
        final EmailInspection history = provided.getInspection();
        final ReceivedEmailInspection.ReceivedEmailInspectionBuilder selected = ReceivedEmailInspection.copyOf(target.getSelectionInspection(),
                target.getAttachments().size(), target.getEmbeddedImages().size()).toBuilder();
        if (!forcedReplacement.test(EmailProperty.TO_RECIPIENTS)) selected.toRecipients(history.getToRecipients());
        if (!forcedReplacement.test(EmailProperty.CC_RECIPIENTS)) selected.ccRecipients(history.getCcRecipients());
        if (!forcedReplacement.test(EmailProperty.BCC_RECIPIENTS)) selected.bccRecipients(history.getBccRecipients());
        if (!forcedReplacement.test(EmailProperty.REPLYTO_RECIPIENT)) selected.replyToRecipients(history.getReplyToRecipients());
        target.retainSelectionInspection(selected.build());
    }

    /** Exact MIME headers stay authoritative; only a newly supplied envelope-sender interpretation can change its current association. */
    public static void restoreExactAssociations(final InternalEmailPopulatingBuilder target, final Email provided,
            final boolean envelopeSenderReplaced) {
        final EmailInspection history = provided.getInspection();
        final ReceivedEmailInspection.ReceivedEmailInspectionBuilder selected = ReceivedEmailInspection.copyOf(history,
                provided.getAttachments().size(), provided.getEmbeddedImages().size()).toBuilder();
        if (envelopeSenderReplaced) selected.bounceToRecipient(target.getSelectionInspection().getBounceToRecipient());
        target.retainSelectionInspection(selected.build());
    }

    private static boolean retains(final Email provided, final Email defaults, final Email overrides,
            final EmailProperty property, final Function<Email, ?> getter, final Predicate<EmailProperty> forcedReplacement) {
        if (forcedReplacement.test(property)) return false;
        final ConfigurationResolution<?> resolution = EmailConfigurationResolver.scalar(provided, defaults, overrides, property, getter);
        return resolution.getSelected() == null || resolution.getSelected().getOrigin().getKind() == ConfigurationOrigin.Kind.SUBMITTED;
    }

    private static boolean unchanged(final CollectionSelection<?> selected, final int submittedCount, final EmailProperty field,
            final Predicate<EmailProperty> forcedReplacement) {
        if (forcedReplacement.test(field) || selected == null || selected.getOccurrences().size() != submittedCount) return false;
        for (int index = 0; index < submittedCount; index++) {
            final CollectionOccurrence<?> occurrence = selected.getOccurrences().get(index);
            if (occurrence.getOrigin() != ConfigurationOrigin.Kind.SUBMITTED || occurrence.getSourceIndex() != index) return false;
        }
        return true;
    }

    private static void restoreAttachments(final ReceivedEmailInspection.ReceivedEmailInspectionBuilder result,
            final EmailInspection current, final EmailInspection history, final CollectionSelection<?> selection) {
        final List<ValueInspection> values = new ArrayList<>();
        final List<AttachmentResource> originals = new ArrayList<>();
        for (int index = 0; index < selection.getOccurrences().size(); index++) {
            final CollectionOccurrence<?> occurrence = selection.getOccurrences().get(index);
            final boolean submitted = occurrence.getOrigin() == ConfigurationOrigin.Kind.SUBMITTED;
            values.add(submitted ? history.getAttachment(occurrence.getSourceIndex()) : current.getAttachment(index));
            originals.add(submitted ? history.getOriginalAttachment(occurrence.getSourceIndex()) : null);
        }
        result.attachments(values).originalAttachments(originals);
    }

    private static void restoreImages(final ReceivedEmailInspection.ReceivedEmailInspectionBuilder result,
            final EmailInspection current, final EmailInspection history, final CollectionSelection<?> selection) {
        final List<ValueInspection> values = new ArrayList<>();
        for (int index = 0; index < selection.getOccurrences().size(); index++) {
            final CollectionOccurrence<?> occurrence = selection.getOccurrences().get(index);
            values.add(occurrence.getOrigin() == ConfigurationOrigin.Kind.SUBMITTED
                    ? history.getEmbeddedImage(occurrence.getSourceIndex()) : current.getEmbeddedImage(index));
        }
        result.embeddedImages(values);
    }
}
