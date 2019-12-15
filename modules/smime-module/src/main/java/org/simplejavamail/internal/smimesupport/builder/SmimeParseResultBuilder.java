package org.simplejavamail.internal.smimesupport.builder;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.internal.smimesupport.builder.SmimeParseResult;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

public class SmimeParseResultBuilder implements SmimeParseResult {

	@NotNull private final List<AttachmentResource> decryptedAttachments = new ArrayList<>();
	@NotNull private OriginalSmimeDetailsImpl originalSmimeDetails = OriginalSmimeDetailsImpl.builder().build();
	@Nullable private AttachmentResource smimeSignedEmailToProcess;

	public void addDecryptedAttachments(@NotNull final List<AttachmentResource> attachments) {
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
	public List<AttachmentResource> getDecryptedAttachments() {
		return decryptedAttachments;
	}
}