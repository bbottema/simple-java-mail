package org.simplejavamail.api.internal.smimesupport.builder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.internal.smimesupport.model.AttachmentDecryptionResult;

import java.util.List;

public interface SmimeParseResult {
    /** Synthetic identity of the legacy generated inspection wrapper, not a received message identity. */
    String GENERATED_MESSAGE_ID = "<generated-for-smime-signed-attachment@simple-java-mail>";
	@NotNull OriginalSmimeDetails getOriginalSmimeDetails();
	@Nullable AttachmentResource getSmimeSignedOrEncryptedEmail();
	@NotNull List<AttachmentDecryptionResult> getDecryptedAttachmentResults();
	@NotNull List<AttachmentResource> getDecryptedAttachments();
}
