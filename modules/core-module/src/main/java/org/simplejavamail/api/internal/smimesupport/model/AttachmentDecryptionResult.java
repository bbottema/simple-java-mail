package org.simplejavamail.api.internal.smimesupport.model;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.jetbrains.annotations.Nullable;

/**
 * Used by the S/MIME module to return the decrypted content as well as the indication of how the content was encrypted / signed.
 */
public interface AttachmentDecryptionResult {
	OriginalSmimeDetails.SmimeMode getSmimeMode();
	AttachmentResource getAttachmentResource();
	/** A recognized automatic child-interpretation failure; its resource remains the original attachment. */
	@Nullable String getFailureReason();
}
