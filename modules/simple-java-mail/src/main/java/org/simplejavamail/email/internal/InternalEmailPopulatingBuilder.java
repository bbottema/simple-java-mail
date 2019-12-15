/*
 * Copyright (C) 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.email.internal;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.OriginalSmimeDetails;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.mail.internet.MimeMessage;
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
	@NotNull InternalEmailPopulatingBuilder withSmimeSignedEmail(@NotNull Email smimeSignedEmail);
	@NotNull InternalEmailPopulatingBuilder withOriginalSmimeDetails(@NotNull OriginalSmimeDetails originalSmimeDetails);
}