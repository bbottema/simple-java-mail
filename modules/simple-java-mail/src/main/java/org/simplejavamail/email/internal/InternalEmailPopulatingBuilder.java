package org.simplejavamail.email.internal;

import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.OriginalSmimeDetails;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * This interface is only there to improve readability there where internal builder API is used.
 */
@SuppressWarnings("UnusedReturnValue")
public interface InternalEmailPopulatingBuilder  extends EmailPopulatingBuilder {
	@NotNull InternalEmailPopulatingBuilder withForward(@Nullable MimeMessage emailMessageToForward);
	@NotNull <T> InternalEmailPopulatingBuilder withHeaders(@NotNull Map<String, Collection<T>> headers, boolean ignoreSmimeMessageId);
	@NotNull InternalEmailPopulatingBuilder clearDecryptedAttachments();
	@NotNull InternalEmailPopulatingBuilder withDecryptedAttachments(List<AttachmentResource> decryptedAttachments);
	@NotNull InternalEmailPopulatingBuilder withSmimeSignedEmail(@NotNull Email smimeSignedEmail);
	@NotNull InternalEmailPopulatingBuilder withOriginalSmimeDetails(@NotNull OriginalSmimeDetails originalSmimeDetails);
}