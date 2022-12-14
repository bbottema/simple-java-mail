package org.simplejavamail.api.internal.smimesupport.builder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.internal.smimesupport.model.AttachmentDecryptionResult;

import java.util.List;

public interface SmimeParseResult {
	@NotNull OriginalSmimeDetails getOriginalSmimeDetails();
	@Nullable AttachmentResource getSmimeSignedOrEncryptedEmail();
	@NotNull List<AttachmentDecryptionResult> getDecryptedAttachmentResults();
	@NotNull List<AttachmentResource> getDecryptedAttachments();
}