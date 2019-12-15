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
package org.simplejavamail.internal.smimesupport;

import org.simplejavamail.api.email.OriginalSmimeDetails;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.mail.internet.ContentType;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode;
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.*;

public final class SmimeRecognitionUtil {

	private static final List<String> SMIME_MIMETYPES = asList("application/pkcs7-mime", "application/x-pkcs7-mime");

	/**
	 * Used internally to recognize when we should ignore message ID header when starting a new email as a copy.
	 */
	public static final String SMIME_ATTACHMENT_MESSAGE_ID = "<generated-for-smime-signed-attachment@simple-java-mail>";

	/**
	 * @return Whether the given attachment is S/MIME signed / encrypted.
	 */
	static boolean isSmimeContentType(@NotNull final ContentType ct) {
		return SMIME_MIMETYPES.contains(ct.getBaseType()) ||
				isSmimeMultiPartSigned(ct.getBaseType(), ct.getParameter("protocol"));
	}

	public static boolean isGeneratedSmimeMessageId(@NotNull final Map.Entry headerEntry) {
		return headerEntry.getKey().equals("Message-ID") &&
				headerEntry.getValue().equals(SMIME_ATTACHMENT_MESSAGE_ID);
	}

	@NotNull
	public static SmimeMode determineSmimeMode(final OriginalSmimeDetails d) {
		boolean encrypted = SMIME_MIMETYPES.contains(d.getSmimeMime()) && "enveloped-data".equals(d.getSmimeType());
		boolean signed = !encrypted &&
				(SMIME_MIMETYPES.contains(d.getSmimeMime()) ||
						isSmimeMultiPartSigned(d.getSmimeMime(), d.getSmimeProtocol()) ||
						"signed-data".equals(d.getSmimeType()));

		return encrypted ? ENCRYPTED : signed ? SIGNED : PLAIN;
	}

	private static boolean isSmimeMultiPartSigned(@Nullable String mime, @Nullable String protocol) {
		return "multipart/signed".equals(mime) && (protocol == null ||
						protocol.equals("application/pkcs7-signature") || protocol.equals("application/x-pkcs7-signature"));
	}
}