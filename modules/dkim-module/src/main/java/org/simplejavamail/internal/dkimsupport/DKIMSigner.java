package org.simplejavamail.internal.dkimsupport;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.internal.modules.DKIMModule;
import org.simplejavamail.utils.mail.dkim.Canonicalization;
import org.simplejavamail.utils.mail.dkim.DkimMessage;
import org.simplejavamail.utils.mail.dkim.DkimSigner;
import org.simplejavamail.utils.mail.dkim.SigningAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Collections;

import static org.simplejavamail.internal.util.MiscUtil.defaultTo;

/**
 * This class only serves to hide the DKIM implementation behind an easy-to-load-with-reflection class.
 */
@SuppressWarnings("unused") // it is ued through reflection
public class DKIMSigner implements DKIMModule {

	private static final Logger LOGGER = LoggerFactory.getLogger(DKIMSigner.class);

	/**
	 * @see DKIMModule#signMessageWithDKIM(MimeMessage, DkimConfig, Recipient)
	 */
	public MimeMessage signMessageWithDKIM(@NotNull final MimeMessage messageToSign, @NotNull final DkimConfig dkimConfig, @NotNull final Recipient fromRecipient) {
		LOGGER.debug("signing MimeMessage with DKIM...");
		try {
			final DkimSigner dkimSigner = new DkimSigner(dkimConfig.getDkimSigningDomain(), dkimConfig.getDkimSelector(), new ByteArrayInputStream(dkimConfig.getDkimPrivateKeyData()));

			defaultTo(dkimConfig.getExcludedHeadersFromDkimDefaultSigningList(), Collections.<String>emptySet())
					.forEach(dkimSigner::removeHeaderToSign);
			dkimSigner.setIdentity(fromRecipient.getAddress());
			dkimSigner.setHeaderCanonicalization(Canonicalization.RELAXED);
			dkimSigner.setBodyCanonicalization(Canonicalization.RELAXED);
			dkimSigner.setSigningAlgorithm(SigningAlgorithm.SHA256_WITH_RSA);
			dkimSigner.setLengthParam(true);
			dkimSigner.setZParam(false);

			// during our junit tests, we don't want to actually connect to the internet to check the domain key
			if (fromRecipient.getAddress().endsWith("supersecret-testing-domain.com")) {
				dkimSigner.setCheckDomainKey(false);
			}

			return new DkimMessage(messageToSign, dkimSigner);
		} catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException | MessagingException e) {
			throw new org.simplejavamail.internal.dkimsupport.DKIMSigningException(org.simplejavamail.internal.dkimsupport.DKIMSigningException.ERROR_SIGNING_DKIM_INVALID_DOMAINKEY, e);
		}
	}
}