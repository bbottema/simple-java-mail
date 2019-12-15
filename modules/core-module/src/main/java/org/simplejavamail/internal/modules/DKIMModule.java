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
package org.simplejavamail.internal.modules;

import org.simplejavamail.api.email.Email;

import javax.mail.internet.MimeMessage;

/**
 * This interface only serves to hide the DKIM implementation behind an easy-to-load-with-reflection class.
 */
public interface DKIMModule {

	String NAME = "DKIM module";

	/**
	 * Primes the {@link MimeMessage} instance for signing with DKIM. The signing itself is performed by
	 * {@code net.markenwerk.utils.mail.dkim.DkimMessage} and {@code net.markenwerk.utils.mail.dkim.DkimSigner}
	 * during the physical sending of the message.
	 *
	 * @param messageToSign                 The message to be signed when sent.
	 * @param emailContainingSigningDetails The {@link Email} that contains the relevant signing information
	 *
	 * @return The original mime message wrapped in a new one that performs signing when sent.
	 */
	MimeMessage signMessageWithDKIM(MimeMessage messageToSign, Email emailContainingSigningDetails);
}