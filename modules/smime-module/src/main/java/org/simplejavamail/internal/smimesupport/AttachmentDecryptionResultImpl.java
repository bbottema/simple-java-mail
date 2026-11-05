package org.simplejavamail.internal.smimesupport;

import lombok.Getter;
import lombok.Builder;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.internal.smimesupport.model.AttachmentDecryptionResult;

@Builder
@Getter
class AttachmentDecryptionResultImpl implements AttachmentDecryptionResult {
	private final OriginalSmimeDetails.SmimeMode smimeMode;
	private final AttachmentResource attachmentResource;
	@Nullable private final String failureReason;
}
