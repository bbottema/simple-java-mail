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

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.MailException;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * This exception is used to communicate errors during encryption / decryption of S/MIME signed {@link org.simplejavamail.api.email.AttachmentResource}.
 */
@SuppressWarnings("serial")
class SmimeException extends MailException {

	static final String ERROR_DECRYPTING_SMIME_SIGNED_ATTACHMENT = "Error unwrapping S/MIME enveloped attachment: %n\t%s";
	static final String ERROR_DETERMINING_SMIME_SIGNER = "Error determining who signed the S/MIME attachment";
	static final String ERROR_EXTRACTING_SIGNEDBY_FROM_SMIME_SIGNED_ATTACHMENT = "Error extracting signed-by address from S/MIME signed attachment: %n\t%s";
	static final String ERROR_EXTRACTING_SUBJECT_FROM_CERTIFICATE = "Error extracting subject from certificate to which it was issued";
	static final String MIMEPART_ASSUMED_SIGNED_ACTUALLY_NOT_SIGNED = "MimePart that was assumed to be S/MIME signed / encrypted actually wasn't: %n\t%s";
	static final String ERROR_READING_SMIME_CONTENT_TYPE = "Error reading S/MIME Content-Type header from MimeMessage";
	static final String ERROR_READING_PKCS12_KEYSTORE = "Unable to read PKCS12 key store";

	SmimeException(@NotNull final String message) {
		super(checkNonEmptyArgument(message, "message"));
	}

	SmimeException(@SuppressWarnings("SameParameterValue") @NotNull final String message, @NotNull final Exception cause) {
		super(checkNonEmptyArgument(message, "message"), cause);
	}
}