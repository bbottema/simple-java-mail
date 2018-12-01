package org.simplejavamail.converter.internal.mimemessage;

import net.markenwerk.utils.mail.dkim.Canonicalization;
import net.markenwerk.utils.mail.dkim.DkimMessage;
import net.markenwerk.utils.mail.dkim.DkimSigner;
import net.markenwerk.utils.mail.dkim.SigningAlgorithm;
import org.simplejavamail.email.Email;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

/**
 * This class only serves to hide the DKIM implementation behind an easy-to-load-with-reflection class.
 */
@SuppressWarnings("unused") // it is ued through reflection
public class DKIMSigner implements DKIMModule {
	
	/**
	 * @see MimeMessageHelper#signMessageWithDKIM(MimeMessage, Email)
	 */
	public MimeMessage signMessageWithDKIM(final MimeMessage messageToSign, final Email emailContainingSigningDetails) {
		try {
			final DkimSigner dkimSigner;
			if (emailContainingSigningDetails.getDkimPrivateKeyFile() != null) {
				// InputStream is managed by Dkim library
				dkimSigner = new DkimSigner(emailContainingSigningDetails.getDkimSigningDomain(), emailContainingSigningDetails.getDkimSelector(),
						emailContainingSigningDetails.getDkimPrivateKeyFile());
			} else {
				// InputStream is managed by SimpleJavaMail user
				dkimSigner = new DkimSigner(emailContainingSigningDetails.getDkimSigningDomain(), emailContainingSigningDetails.getDkimSelector(),
						emailContainingSigningDetails.getDkimPrivateKeyInputStream());
			}
			dkimSigner.setIdentity(emailContainingSigningDetails.getFromRecipient().getAddress());
			dkimSigner.setHeaderCanonicalization(Canonicalization.SIMPLE);
			dkimSigner.setBodyCanonicalization(Canonicalization.RELAXED);
			dkimSigner.setSigningAlgorithm(SigningAlgorithm.SHA256_WITH_RSA);
			dkimSigner.setLengthParam(true);
			dkimSigner.setZParam(false);
			return new DkimMessage(messageToSign, dkimSigner);
		} catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException | MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_SIGNING_DKIM_INVALID_DOMAINKEY, e);
		}
	}
}
