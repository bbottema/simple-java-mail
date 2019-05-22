package org.simplejavamail.email.internal;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.OriginalSmimeDetails;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.internet.MimeMessage;
import java.util.List;
import java.util.Map;

/**
 * This interface is only there to improve readability there where internal builder API is used.
 */
@SuppressWarnings("UnusedReturnValue")
public interface InternalEmailPopulatingBuilder  extends EmailPopulatingBuilder {
	@Nonnull InternalEmailPopulatingBuilder withForward(@Nullable MimeMessage emailMessageToForward);
	@Nonnull <T> InternalEmailPopulatingBuilder withHeaders(@Nonnull Map<String, T> headers, boolean ignoreSmimeMessageId);
	@Nonnull InternalEmailPopulatingBuilder withDecryptedAttachments(List<AttachmentResource> decryptedAttachments);
	@Nonnull InternalEmailPopulatingBuilder  withSmimeSignedEmail(@Nonnull Email smimeSignedEmail);
	@Nonnull InternalEmailPopulatingBuilder  withOriginalSmimeDetails(@Nonnull OriginalSmimeDetails originalSmimeDetails);
}