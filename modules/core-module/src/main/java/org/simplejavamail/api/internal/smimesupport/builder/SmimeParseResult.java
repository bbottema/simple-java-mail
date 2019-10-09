package org.simplejavamail.api.internal.smimesupport.builder;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.OriginalSmimeDetails;

import java.util.List;

public interface SmimeParseResult {
	OriginalSmimeDetails getOriginalSmimeDetails();
	AttachmentResource getSmimeSignedEmail();
	List<AttachmentResource> getDecryptedAttachments();
}
