package org.simplejavamail.api.internal.batchsupport;

import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.internal.util.concurrent.MailSendControl;

/**
 * Lets the Mailer apply the selected SMTP configuration's sending limits before borrowing a pooled connection.
 * This is a destination selection, not a lease: it owns no transport, reserves no capacity and needs no cleanup.
 */
public interface SelectedPoolTransport {

	/** The Session belonging to the original selected pool registration. */
	@NotNull Session getSession();

	/** Sending limits fixed by this pool's registration, not by mutable properties on a caller-owned Session. */
	@NotNull SendingAllowance getSendingAllowance();

	/** Claims from that registration without selecting again; retirement makes acquisition fail rather than switch destinations. */
	@NotNull LifecycleDelegatingTransport acquireTransport(@Nullable MailSendControl control);
}
