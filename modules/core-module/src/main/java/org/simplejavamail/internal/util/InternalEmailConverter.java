package org.simplejavamail.internal.util;

import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;

/**
 * API that allows the Outlook module to invoke the EmailConverter API from the main Simple Java Mail module. This is used when converting Outlook messages to Email objects, which contain nested
 * Outlook messages that need to be converted to MimeMessage (via Email objects as well) so they can be added again as EML attachments.
 */
public interface InternalEmailConverter {
	MimeMessage emailToMimeMessage(@NotNull Email email);

	byte[] mimeMessageToEMLByteArray(@NotNull MimeMessage mimeMessage);
}