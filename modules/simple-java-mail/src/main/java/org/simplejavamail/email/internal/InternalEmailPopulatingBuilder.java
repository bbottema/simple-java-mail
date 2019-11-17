package org.simplejavamail.email.internal;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.OriginalSmimeDetails;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.mail.internet.MimeMessage;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * This interface is only there to improve readability there where internal builder API is used.
 */
@SuppressWarnings("UnusedReturnValue")
public interface InternalEmailPopulatingBuilder  extends EmailPopulatingBuilder {
	@NotNull InternalEmailPopulatingBuilder withForward(@Nullable MimeMessage emailMessageToForward);
	@NotNull <T> InternalEmailPopulatingBuilder withHeaders(@NotNull Map<String, T> headers, boolean ignoreSmimeMessageId);
	@NotNull InternalEmailPopulatingBuilder withDecryptedAttachments(List<AttachmentResource> decryptedAttachments);
	@NotNull InternalEmailPopulatingBuilder  withSmimeSignedEmail(@NotNull Email smimeSignedEmail);
	@NotNull InternalEmailPopulatingBuilder  withOriginalSmimeDetails(@NotNull OriginalSmimeDetails originalSmimeDetails);
	@NotNull InternalEmailPopulatingBuilder  withOriginalSentDate(@NotNull Date originalSentDate);
}