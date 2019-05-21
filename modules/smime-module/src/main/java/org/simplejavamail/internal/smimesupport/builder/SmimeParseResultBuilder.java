package org.simplejavamail.internal.smimesupport.builder;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.internal.smimesupport.builder.SmimeParseResult;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class SmimeParseResultBuilder implements SmimeParseResult {

	@Nonnull private final List<AttachmentResource> decryptedAttachments = new ArrayList<>();
	@Nonnull private OriginalSmimeDetailsImpl originalSmimeDetails = OriginalSmimeDetailsImpl.builder().build();
	@Nullable private AttachmentResource smimeSignedEmailToProcess;

	public void addDecryptedAttachments(@Nonnull final List<AttachmentResource> attachments) {
		decryptedAttachments.addAll(attachments);
	}

	public void setSmimeSignedEmailToProcess(@Nonnull final AttachmentResource smimeSignedEmailToProcess) {
		this.smimeSignedEmailToProcess = smimeSignedEmailToProcess;
	}

	@Override
	@Nonnull
	public OriginalSmimeDetailsImpl getOriginalSmimeDetails() {
		return originalSmimeDetails;
	}

	@Override
	@Nullable
	public AttachmentResource getSmimeSignedEmail() {
		return smimeSignedEmailToProcess;
	}

	@Override
	@Nonnull
	public List<AttachmentResource> getDecryptedAttachments() {
		return decryptedAttachments;
	}
}