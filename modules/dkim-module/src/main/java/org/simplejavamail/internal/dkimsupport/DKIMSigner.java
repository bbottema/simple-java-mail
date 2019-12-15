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
package org.simplejavamail.internal.dkimsupport;

import net.markenwerk.utils.mail.dkim.Canonicalization;
import net.markenwerk.utils.mail.dkim.DkimMessage;
import net.markenwerk.utils.mail.dkim.DkimSigner;
import net.markenwerk.utils.mail.dkim.SigningAlgorithm;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.internal.modules.DKIMModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * This class only serves to hide the DKIM implementation behind an easy-to-load-with-reflection class.
 */
@SuppressWarnings("unused") // it is ued through reflection
public class DKIMSigner implements DKIMModule {

	private static final Logger LOGGER = LoggerFactory.getLogger(DKIMSigner.class);

	/**
	 * @see DKIMModule#signMessageWithDKIM(MimeMessage, Email)
	 */
	public MimeMessage signMessageWithDKIM(final MimeMessage messageToSign, final Email signingDetails) {
		LOGGER.debug("signing MimeMessage with DKIM...");
		try {
			final String dkimSelector = checkNonEmptyArgument(signingDetails.getDkimSelector(), "dkimSelector");
			final DkimSigner dkimSigner = signingDetails.getDkimPrivateKeyFile() != null
					// InputStream is managed by Dkim library
					? new DkimSigner(signingDetails.getDkimSigningDomain(), dkimSelector, signingDetails.getDkimPrivateKeyFile())
					// InputStream is managed by SimpleJavaMail user
					: new DkimSigner(signingDetails.getDkimSigningDomain(), dkimSelector, signingDetails.getDkimPrivateKeyInputStream());
			dkimSigner.setIdentity(checkNonEmptyArgument(signingDetails.getFromRecipient(), "fromRecipient").getAddress());
			dkimSigner.setHeaderCanonicalization(Canonicalization.RELAXED);
			dkimSigner.setBodyCanonicalization(Canonicalization.RELAXED);
			dkimSigner.setSigningAlgorithm(SigningAlgorithm.SHA256_WITH_RSA);
			dkimSigner.setLengthParam(true);
			dkimSigner.setZParam(false);
			return new DkimMessage(messageToSign, dkimSigner);
		} catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException | MessagingException e) {
			throw new org.simplejavamail.internal.dkimsupport.DKIMSigningException(org.simplejavamail.internal.dkimsupport.DKIMSigningException.ERROR_SIGNING_DKIM_INVALID_DOMAINKEY, e);
		}
	}
}