package org.simplejavamail.internal.smimesupport;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.internal.smimesupport.model.AttachmentDecryptionResult;

@RequiredArgsConstructor
@Getter
class AttachmentDecryptionResultImpl implements AttachmentDecryptionResult {
	private final OriginalSmimeDetails.SmimeMode smimeMode;
	private final AttachmentResource attachmentResource;
}