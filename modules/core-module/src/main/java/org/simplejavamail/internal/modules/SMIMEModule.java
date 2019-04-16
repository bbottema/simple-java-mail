package org.simplejavamail.internal.modules;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.OriginalSMimeDetails;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.internet.MimePart;
import java.util.List;

/**
 * This interface only serves to hide the S/MIME implementation behind an easy-to-load-with-reflection class.
 */
public interface SMIMEModule {
	@Nonnull
	List<AttachmentResource> decryptAttachments(@Nonnull List<AttachmentResource> attachments);

	boolean isSMimeAttachment(@Nonnull AttachmentResource attachment);

	@Nonnull
	OriginalSMimeDetails getSMimeDetails(@Nonnull AttachmentResource onlyAttachment);

	@Nullable
	String getSignedByAddress(@Nonnull AttachmentResource smimeAttachment);

	@Nullable
	String getSignedByAddress(@Nonnull MimePart mimePart);
}
