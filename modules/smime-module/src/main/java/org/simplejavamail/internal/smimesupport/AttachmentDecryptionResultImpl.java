package org.simplejavamail.internal.smimesupport;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.internal.smimesupport.model.AttachmentDecryptionResult;

// FIXME lombok
class AttachmentDecryptionResultImpl implements AttachmentDecryptionResult {
	private final OriginalSmimeDetails.SmimeMode smimeMode;
	private final AttachmentResource attachmentResource;

	public AttachmentDecryptionResultImpl(final OriginalSmimeDetails.SmimeMode smimeMode, final AttachmentResource attachmentResource) {
		this.smimeMode = smimeMode;
		this.attachmentResource = attachmentResource;
	}

	public OriginalSmimeDetails.SmimeMode getSmimeMode() {
		return smimeMode;
	}

	public AttachmentResource getAttachmentResource() {
		return attachmentResource;
	}
}