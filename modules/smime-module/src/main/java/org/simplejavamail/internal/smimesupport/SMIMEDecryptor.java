package org.simplejavamail.internal.smimesupport;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.internal.modules.SMIMEModule;

import javax.annotation.Nonnull;
import java.util.List;

public class SMIMEDecryptor implements SMIMEModule {
	@Nonnull
	@Override
	public List<AttachmentResource> decryptAttachments(final List<AttachmentResource> attachments) {
		return null;
	}
}
