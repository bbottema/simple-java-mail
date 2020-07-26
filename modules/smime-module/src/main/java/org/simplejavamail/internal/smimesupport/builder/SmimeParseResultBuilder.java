package org.simplejavamail.internal.smimesupport.builder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.internal.smimesupport.builder.SmimeParseResult;
import org.simplejavamail.api.internal.smimesupport.model.AttachmentDecryptionResult;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;

import java.util.ArrayList;
import java.util.List;

// FIXME lombok
public class SmimeParseResultBuilder implements SmimeParseResult {

	@NotNull private final List<AttachmentDecryptionResult> decryptedAttachments = new ArrayList<>();
	@NotNull private final OriginalSmimeDetailsImpl originalSmimeDetails = OriginalSmimeDetailsImpl.builder().build();
	@Nullable private AttachmentResource smimeSignedEmailToProcess;

	public void addDecryptedAttachments(@NotNull final List<AttachmentDecryptionResult> attachments) {
		decryptedAttachments.addAll(attachments);
	}

	public void setSmimeSignedEmailToProcess(@NotNull final AttachmentResource smimeSignedEmailToProcess) {
		this.smimeSignedEmailToProcess = smimeSignedEmailToProcess;
	}

	@Override
	@NotNull
	public OriginalSmimeDetailsImpl getOriginalSmimeDetails() {
		return originalSmimeDetails;
	}

	@Override
	@Nullable
	public AttachmentResource getSmimeSignedEmail() {
		return smimeSignedEmailToProcess;
	}

	@Override
	@NotNull
	public List<AttachmentDecryptionResult> getDecryptedAttachmentResults() {
		return decryptedAttachments;
	}

	@Override
	@NotNull
	public List<AttachmentResource> getDecryptedAttachments() {
		final ArrayList<AttachmentResource> attachmentResources = new ArrayList<>();
		for (final AttachmentDecryptionResult decryptedAttachment : decryptedAttachments) {
			attachmentResources.add(decryptedAttachment.getAttachmentResource());
		}
		return attachmentResources;
	}
}