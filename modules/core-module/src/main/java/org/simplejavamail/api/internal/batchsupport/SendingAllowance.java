package org.simplejavamail.api.internal.batchsupport;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.internal.util.concurrent.MailSendControl;

/**
 * Lets the optional pool module retain a registration's sending limits without depending on the Mailer implementation.
 * The pool only carries this reference; the sending thread reserves and charges it outside pool locks.
 */
public interface SendingAllowance {

	/** Whether any rule needs envelope costing or waiting; the disabled path must do neither. */
	boolean isEnabled();

	/** Waits on the calling send thread. The caller owns the returned reservation until commit or close. */
	@NotNull Reservation reserve(int recipientCount, @NotNull MailSendControl control);

	/** An unused reservation is released on close; charging immediately before provider invocation makes it non-refundable. */
	interface Reservation extends AutoCloseable {
		void commit(@NotNull MailSendControl control);
		@Override void close();
	}
}
