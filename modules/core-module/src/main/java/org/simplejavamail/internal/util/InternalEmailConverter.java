package org.simplejavamail.internal.util;

import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.outlooksupport.model.EmailFromOutlookMessage;
import org.simplejavamail.internal.converter.ImportTraversal;

import java.util.Set;

/**
 * Internal bridge from the Outlook module to main-runtime conversion and HTML CID interpretation. Nested Outlook messages can be
 * converted to MimeMessage (via Email objects) and retained as EML attachments without making the specialist module depend on main.
 */
public interface InternalEmailConverter {
    /** The selected native import shares this scope with nested materialization and diagnostic capture. */
    default ImportTraversal importTraversal() { return ImportTraversal.unlimited(); }
	MimeMessage emailToMimeMessage(@NotNull Email email);

	byte[] mimeMessageToEMLByteArray(@NotNull MimeMessage mimeMessage);

	/** Completes an already-read independent native child under its parent's receive configuration and shared allowance. */
	Email completeOutlookChild(@NotNull EmailFromOutlookMessage child, @NotNull ImportTraversal childScope);

	/** Interpreted complete CID identities, shared with MIME/EML conversion without moving HTML parsing into the optional module. */
	Set<String> referencedContentIds(@NotNull String html);
}
