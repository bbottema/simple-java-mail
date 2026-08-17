package org.simplejavamail.api.email;

import org.jetbrains.annotations.Nullable;

import java.io.Serializable;

/**
 * Indicates S/MIME details about an email. Used to show how a converted message was signed / encrypted and by whom.
 * <p>
 * Note: the difference between this and {@link org.simplejavamail.api.internal.smimesupport.model.SmimeDetails} is that
 * this class is intended for exposing S/MIME metadata to the end user, while the other class is for internal use
 * by the S/MIME module alone.
 *
 * @see EmailPopulatingBuilder#getOriginalSmimeDetails()
 */
public interface OriginalSmimeDetails extends Serializable {

	enum SmimeMode {
		PLAIN, SIGNED, ENCRYPTED, SIGNED_ENCRYPTED
	}

	enum VerificationStatus {
		NOT_SIGNED, VALID, INVALID, ERROR
	}

	enum DecryptionStatus {
		NOT_ENCRYPTED, DECRYPTED, KEY_MISSING, FAILED
	}

	@Nullable SmimeMode getSmimeMode();
	@Nullable String getSmimeMime();
	@Nullable String getSmimeType();
	@Nullable String getSmimeName();
	@Nullable String getSmimeProtocol();
	@Nullable String getSmimeMicalg();

	/**
	 * @return The common name (CN) from the first signer's certificate, or {@code null} when no signer name could be read.
	 * This is metadata supplied with the S/MIME message, not a trusted identity. Simple Java Mail does not validate the certificate path,
	 * certificate validity or revocation status, key usage, or whether the certificate identifies the message's {@code From} address.
	 */
	@Nullable String getSmimeSignedBy();

	/**
	 * @return {@code true} when every applicable signature represented by these details was cryptographically verified using the signer
	 * certificate supplied with the S/MIME message; {@code false} when at least one signature failed or could not be verified; or
	 * {@code null} when signature verification was not applicable or was not performed.
	 * <p>
	 * A {@code true} result confirms signature consistency and content integrity only. It does not establish certificate trust, certificate
	 * validity or revocation status, key usage, or that the signer certificate belongs to the message's claimed sender.
	 */
	@Nullable Boolean getSmimeSignatureValid();

	/** Cryptographic signature result; it does not assert certificate or sender trust. */
	default VerificationStatus getVerificationStatus() {
		if (getSmimeSignatureValid() == null) return VerificationStatus.NOT_SIGNED;
		return getSmimeSignatureValid() ? VerificationStatus.VALID : VerificationStatus.INVALID;
	}

	default DecryptionStatus getDecryptionStatus() {
		return DecryptionStatus.NOT_ENCRYPTED;
	}

	@Nullable
	default String getFailureReason() {
		return null;
	}

	/** Exact protected message bytes when conversion started from an S/MIME entity. */
	default byte @Nullable [] getOriginalProtectedMessage() {
		return null;
	}
}
