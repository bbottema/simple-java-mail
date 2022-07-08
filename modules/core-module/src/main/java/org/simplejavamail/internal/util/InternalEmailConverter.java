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
package org.simplejavamail.internal.util;

import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;

/**
 * API that allows the Outlook module to invoke the EmailConverter API from the main Simple Java Mail module. This is used when converting Outlook messages to Email objects, which contain nested
 * Outlook messages that need to be converted to MimeMessage (via Email objects as well) so they can be added again as EML attachments.
 */
public interface InternalEmailConverter {
	MimeMessage emailToMimeMessage(@NotNull Email email);

	byte[] mimeMessageToEMLByteArray(@NotNull MimeMessage mimeMessage);
}