package org.simplejavamail.api.email;

/** What the reporting mail server says happened to one recipient's delivery. This is not proof of authenticity or that the recipient read the email. */
public enum DeliveryAction {

	/** The server reports successful delivery. */
	DELIVERED,
	/** Delivery has not finished; the server may still be trying. */
	DELAYED,
	/** The server reports that it has stopped trying to deliver. */
	FAILED,
	/** The server passed the email onward without a final delivery report. */
	RELAYED,
	/** The recipient address expanded into other destinations, such as a mailing list. */
	EXPANDED,
	/** The action was missing, unrecognized or contradictory; inspect the report warnings. */
	UNKNOWN
}
