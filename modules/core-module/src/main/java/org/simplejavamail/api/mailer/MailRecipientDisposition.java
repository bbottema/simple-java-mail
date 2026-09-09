package org.simplejavamail.api.mailer;

/** Final submission knowledge for one envelope recipient, also defining the receipt's compatibility recipient lists. */
public enum MailRecipientDisposition {
	/** The peer accepted the message for this recipient; this does not mean mailbox delivery. */
	ACCEPTED,
	/** The provider knows this recipient was not submitted, without classifying the address as invalid. */
	VALID_UNSENT,
	/** The provider classified this recipient as invalid. */
	INVALID,
	/** Submission may have happened, or this send path supplies no recipient facts. */
	UNKNOWN
}
