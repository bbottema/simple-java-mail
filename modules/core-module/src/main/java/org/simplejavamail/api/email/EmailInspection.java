package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;

/**
 * Captured facts about this Email's currently selected values and historical input. Ordinary values remain on {@link Email}.
 * Reading this immutable view never opens resources, parses a child, loads a module or performs cryptography.
 * Copies retain associations for unchanged values; replacing or clearing a value removes its incoming protection claim.
 * Historical originals and family observations remain available after such edits.
 */
public interface EmailInspection extends Serializable {
    /** Facts for the selected plain-text alternative. */
    @NotNull ValueInspection getPlainText();
    /** Facts for the selected HTML alternative, independently of any native/plain-text fallback. */
    @NotNull ValueInspection getHTMLText();
    /** Facts for the selected logical subject, including established confidentiality. */
    @NotNull ValueInspection getSubject();
    /** Facts for the selected claimed author; a verified signer does not establish this identity. */
    @NotNull ValueInspection getFromRecipient();
    /** Facts for the selected To occurrences in their represented order. */
    @NotNull ValueInspection getToRecipients();
    /** Facts for the selected Cc occurrences, without outer-list union. */
    @NotNull ValueInspection getCcRecipients();
    /** Facts for actually selected Bcc occurrences; missing recipients are not inferred. */
    @NotNull ValueInspection getBccRecipients();
    /** Facts for Reply-To in the selected authored view, including meaningful absence. */
    @NotNull ValueInspection getReplyToRecipients();
    /** Facts for the represented bounce/return-path interpretation. */
    @NotNull ValueInspection getBounceToRecipient();
    /** Facts for the logical Message-ID; generated adapter identity is not received evidence. */
    @NotNull ValueInspection getId();
    /** Facts for the selected stated sent date, independently of receipt/creation timestamps. */
    @NotNull ValueInspection getSentDate();
    /** Describes the selected calendar text and method together. */
    @NotNull ValueInspection getCalendar();
    /** Describes the attachment occurrence at this index; out-of-range indexes are caller errors. */
    @NotNull ValueInspection getAttachment(int index);
    /** Describes the embedded-image occurrence at this index; out-of-range indexes are caller errors. */
    @NotNull ValueInspection getEmbeddedImage(int index);
    /**
     * Historical S/MIME observations for this source and its directly opened attachment payloads. Replacing/clearing current
     * values retains this history; independent child Email body layers are excluded. Check current fields through their value inspections.
     */
    @NotNull ProtectionInspection getSmime();
    /**
     * Historical OpenPGP observations for this source and its directly opened attachment payloads. Replacing/clearing current
     * values retains this history; independent child Email body layers are excluded. Check current fields through their value inspections.
     */
    @NotNull ProtectionInspection getOpenPgp();
    /**
     * Complete retained input in its captured format, or null when unavailable. Edits do not regenerate this historical input.
     * MIME/native reader objects without captured file bytes and incomplete acquired prefixes do not supply an original file.
     */
    @Nullable AttachmentResource getOriginalMessage();
    /**
     * Complete retained input for the attachment occurrence, or null when unavailable. This is the file's original payload,
     * not an enclosing protected message-body container. Untransformed owned storage may be shared with the ordinary resource.
     * @throws IndexOutOfBoundsException if the index does not identify a current attachment occurrence
     */
    @Nullable AttachmentResource getOriginalAttachment(int index);
}
