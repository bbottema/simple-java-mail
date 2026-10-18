package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;

/**
 * A recipient address as it appeared in a delivery report, together with the kind of address being reported.
 * Internet mailboxes have a convenient {@link Recipient} view; addresses from other mail systems keep their original representation.
 * A parsed mailbox is a syntax-checked view, not proof of recipient identity or deliverability.
 */
public interface ReportedRecipient extends Serializable {

	/** The reported address format, such as {@code rfc822}, {@code utf-8} or {@code x400}. Unfamiliar types are preserved. */
	@NotNull String getType();

	/** The reported address without its type prefix, retaining its spelling and case. This may not be an Internet mailbox. */
	@NotNull String getAddress();

	/**
	 * A parsed Internet mailbox when a recognized address format can be read as one mailbox, otherwise {@code null}.
	 * Any name comes from the reported address itself, never a lookup or a guess. To/Cc/Bcc, certificates and notification preferences are not supplied.
	 * This view is prepared during conversion; calling the getter does not parse again or do I/O.
	 */
	@Nullable Recipient getMailbox();

	/** Indicates whether a mailbox view is available without printing the reported type, name or address. */
	@Override
	@NotNull String toString();
}
