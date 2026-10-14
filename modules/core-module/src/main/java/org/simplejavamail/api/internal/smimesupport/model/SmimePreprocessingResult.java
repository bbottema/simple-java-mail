package org.simplejavamail.api.internal.smimesupport.model;

import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.internal.protectionsupport.model.InboundProtectionResult;

import java.util.List;

/** Result of pre-parse S/MIME recognition, verification, and decryption. */
@ApiStatus.Internal
public interface SmimePreprocessingResult extends InboundProtectionResult<OriginalSmimeDetails> {

	@Override
	boolean isRecognized();

	@Override
	@NotNull MimeMessage getEffectiveMimeMessage();

	@Override
	@NotNull OriginalSmimeDetails getDetails();

	@Nullable OriginalSmimeDetails getNestedSignedDetails();

	@NotNull List<AttachmentResource> getProtectedAttachments();

	@NotNull List<AttachmentResource> getDecryptedArtifacts();
}
