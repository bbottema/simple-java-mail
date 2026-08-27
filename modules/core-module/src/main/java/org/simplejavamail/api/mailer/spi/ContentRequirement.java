package org.simplejavamail.api.mailer.spi;

/**
 * Declares how much of a prepared message a {@link MailTransportAdapter} must preserve during submission. An adapter must return {@code true} from
 * {@link MailTransportAdapter#supportsContentRequirement(ContentRequirement)} before receiving a message with that requirement.
 */
public enum ContentRequirement {
	/** Jakarta Mail and the transport provider may perform their normal message finalization and submission-time header handling. */
	NORMAL,
	/** Bytes covered by cryptographic protection must remain stable, while normal submission-time omission of transport-only headers remains allowed. */
	PRESERVE_PROTECTED_CONTENT,
	/** Every supplied RFC 822 byte must be preserved, including header order and folding and headers normally omitted during submission. */
	PRESERVE_ALL_BYTES
}
