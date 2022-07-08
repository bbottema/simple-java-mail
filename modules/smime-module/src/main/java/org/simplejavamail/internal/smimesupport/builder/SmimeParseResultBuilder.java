/*
 * Copyright © 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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