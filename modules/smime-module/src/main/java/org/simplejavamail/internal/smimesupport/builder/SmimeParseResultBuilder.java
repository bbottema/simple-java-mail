package org.simplejavamail.internal.smimesupport.builder;

import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.internal.smimesupport.builder.SmimeParseResult;
import org.simplejavamail.api.internal.smimesupport.model.AttachmentDecryptionResult;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class SmimeParseResultBuilder implements SmimeParseResult {

	@NotNull private final List<AttachmentDecryptionResult> decryptedAttachmentResults = new ArrayList<>();
	@NotNull private final OriginalSmimeDetailsImpl originalSmimeDetails = OriginalSmimeDetailsImpl.builder().build();
	@Nullable private AttachmentResource smimeSignedEmail;

	public void addDecryptedAttachments(@NotNull final List<AttachmentDecryptionResult> attachments) {
		decryptedAttachmentResults.addAll(attachments);
	}

	@Override
	@NotNull
	public List<AttachmentResource> getDecryptedAttachments() {
		final ArrayList<AttachmentResource> attachmentResources = new ArrayList<>();
		for (final AttachmentDecryptionResult decryptedAttachment : decryptedAttachmentResults) {
			attachmentResources.add(decryptedAttachment.getAttachmentResource());
		}
		return attachmentResources;
	}
}