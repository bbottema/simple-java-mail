package org.simplejavamail.internal.modules;

import org.simplejavamail.api.email.AttachmentResource;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * This interface only serves to hide the S/MIME implementation behind an easy-to-load-with-reflection class.
 */
public interface SMIMEModule {
	@Nonnull
	List<AttachmentResource> decryptAttachments(List<AttachmentResource> attachments);
}
