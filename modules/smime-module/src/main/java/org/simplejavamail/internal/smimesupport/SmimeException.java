package org.simplejavamail.internal.smimesupport;

import org.simplejavamail.MailException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * This exception is used to communicate errors during encryption / decryption of S/MIME signed {@link org.simplejavamail.api.email.AttachmentResource}.
 */
@SuppressWarnings("serial")
class SmimeException extends MailException {

	static final String ERROR_DECRYPTING_SMIME_SIGNED_ATTACHMENT = "Error unwrapping S/MIME enveloped attachment: \n\t%s";
	static final String ERROR_DETERMINING_SMIME_SIGNER = "Error determining who signed the S/MIME attachment";
	static final String ERROR_EXTRACTING_SIGNEDBY_FROM_SMIME_SIGNED_ATTACHMENT = "Error extracting signed-by address from S/MIME signed attachment: \n\t%s";
	static final String ERROR_EXTRACTING_SUBJECT_FROM_CERTIFICATE = "Error extracting subject from certificate to which it was issued";
	static final String MIMEPART_ASSUMED_SIGNED_ACTUALLY_NOT_SIGNED = "MimePart that was assumed to be S/MIME signed / encrypted actually wasn't: \n\t%s";
	static final String ERROR_READING_SMIME_CONTENT_TYPE = "Error reading S/MIME Content-Type header from MimeMessage";
	static final String ERROR_READING_PKCS12_KEYSTORE = "Unable to read PKCS12 key store";

	SmimeException(@Nonnull final String message) {
		super(checkNonEmptyArgument(message, "message"));
	}

	SmimeException(@SuppressWarnings("SameParameterValue") @Nonnull final String message, @Nonnull final Exception cause) {
		super(checkNonEmptyArgument(message, "message"), cause);
	}
}