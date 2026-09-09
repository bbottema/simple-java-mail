package org.simplejavamail.api.mailer;

/** Outcome of RCPT TO only. Even ACCEPTED does not establish final message acceptance. */
public enum SmtpRecipientStatus {
	/** An observed 2xx reply accepted RCPT TO. */
	ACCEPTED,
	/** An observed 4xx reply rejected RCPT TO temporarily. */
	TEMPORARILY_REJECTED,
	/** An observed 5xx reply rejected RCPT TO permanently. */
	PERMANENTLY_REJECTED,
	/** The provider knows RCPT TO was not attempted. */
	NOT_ATTEMPTED,
	/** The provider did not expose a conclusive RCPT TO reply. */
	UNKNOWN
}
