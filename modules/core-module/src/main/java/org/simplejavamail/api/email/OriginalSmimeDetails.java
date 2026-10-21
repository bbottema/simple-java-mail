package org.simplejavamail.api.email;

import org.jetbrains.annotations.Nullable;

import java.io.Serializable;

/**
 * Indicates S/MIME details about an email. Used to show how a converted message was signed / encrypted and by whom.
 * <p>
 * These are historical results for the converted input, including any retained original protected bytes. Ordinary copying and supported
 * Java serialization preserve them. Defaults/overrides completion resolves outgoing content and settings without copying these results;
 * use the original converted Email for inspection. Plain/unsigned details on a composed Email do not establish the input's protection or
 * a verification result for outgoing MIME. Outgoing signing/encryption configuration is separate and takes effect during MIME preparation.
 * <p>
 * Note: the difference between this and {@link org.simplejavamail.api.internal.smimesupport.model.SmimeDetails} is that
 * this class is intended for exposing S/MIME metadata to the end user, while the other class is for internal use
 * by the S/MIME module alone.
 * <p>
 * When inspection has not established a protection fact, its result is {@code UNKNOWN}. For example, ordinary import
 * remains available without the optional S/MIME module, but recognizing a protected MIME envelope does not verify or
 * decrypt it. Clearly plain MIME can still be identified without cryptographic inspection.
 * <p>
 * When results from more than one represented S/MIME layer are combined, the most severe result is retained. Verification uses
 * {@code ERROR > INVALID > UNKNOWN > VALID > NOT_SIGNED}; decryption uses
 * {@code FAILED > KEY_MISSING > UNKNOWN > DECRYPTED > NOT_ENCRYPTED}. Thus an uninspected layer cannot produce an
 * aggregate all-valid or all-decrypted result. Unrelated attachments do not change their owning message's protection facts.
 *
 * @see EmailPopulatingBuilder#getOriginalSmimeDetails()
 */
public interface OriginalSmimeDetails extends Serializable {

	enum SmimeMode {
		PLAIN, SIGNED, ENCRYPTED, SIGNED_ENCRYPTED,
		/** The available inspection has not established the message's S/MIME mode. */
		UNKNOWN
	}

	enum VerificationStatus {
		NOT_SIGNED, VALID, INVALID, ERROR,
		/** Signature presence or verification could not be established; this is not a verification failure. */
		UNKNOWN
	}

	enum DecryptionStatus {
		NOT_ENCRYPTED, DECRYPTED, KEY_MISSING, FAILED,
		/** Encryption or its decryption outcome could not be established; this is not a decryption failure. */
		UNKNOWN
	}

	/**
	 * Returns the recognized S/MIME mode, or {@link SmimeMode#UNKNOWN} when it could not be established.
	 * A mode recognized from MIME headers alone does not establish successful verification or decryption.
	 */
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
	 * {@code null} when signature verification was not applicable or its result is {@link VerificationStatus#UNKNOWN}.
	 * <p>
	 * A {@code true} result confirms signature consistency and content integrity only. It does not establish certificate trust, certificate
	 * validity or revocation status, key usage, or that the signer certificate belongs to the message's claimed sender.
	 */
	@Nullable Boolean getSmimeSignatureValid();

	/**
	 * Cryptographic signature result; it does not assert certificate or sender trust. When several results are combined,
	 * the most severe verification status is returned.
	 */
	default VerificationStatus getVerificationStatus() {
		if (getSmimeSignatureValid() == null) return VerificationStatus.NOT_SIGNED;
		return getSmimeSignatureValid() ? VerificationStatus.VALID : VerificationStatus.INVALID;
	}

	/** Returns the most severe decryption result represented by these details. */
	default DecryptionStatus getDecryptionStatus() {
		return DecryptionStatus.NOT_ENCRYPTED;
	}

	@Nullable
	default String getFailureReason() {
		return null;
	}

	/**
	 * Returns a defensive copy of the exact outer protected EML bytes when conversion started from an S/MIME entity.
	 *
	 * <p>The bytes are retained in memory by the converted {@link Email} and are included when that email is Java
	 * serialized. For a large protected message, account for roughly another full-message byte array while keeping the
	 * converted email. The exact representation remains available even when signature verification or decryption fails, or inspection is
	 * unavailable because the optional module is absent. Outlook MSG input has no exact original EML representation;
	 * this accessor does not invent one.</p>
	 */
	default byte @Nullable [] getOriginalProtectedMessage() {
		return null;
	}
}
