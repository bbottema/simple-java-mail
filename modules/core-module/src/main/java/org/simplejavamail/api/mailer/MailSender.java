package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;

/**
 * Scoped sender provided by {@link Mailer#withOpenConnection(OpenConnectionCallback)}.
 * <p>
 * The sender uses the SMTP connection owned by the surrounding {@link Mailer} call. It is not closeable and does not expose Jakarta Mail's
 * {@code Transport}. Use it only on the thread running the callback, before that callback returns or throws. Don't retain it for later or
 * pass it to another thread; use {@link Mailer#async()} for independent asynchronous sends. Simple Java Mail rejects calls outside this scope
 * before preparing or submitting an email.
 */
public interface MailSender {

	/**
	 * Sends one email over the surrounding open SMTP connection and returns its submission receipt.
	 * <p>
	 * The email is processed like {@link Mailer.Sync#sendMail(Email)}: defaults and overrides are applied, validation runs,
	 * the email is converted to a Jakarta Mail message, and the message is submitted to the SMTP server before this method returns.
	 * <p>
	 * You may ignore the returned receipt when only completion matters. It describes SMTP submission acceptance using provider-neutral status and
	 * recipient groups, not final recipient mailbox delivery. A failed or partial submission throws {@link MailSubmissionException}, whose receipt
	 * contains the same structured facts and whose cause is the original Jakarta Mail failure.
	 *
	 * @param email The email to send.
	 * @return A receipt for the completed submission.
	 * @throws MailException If library-owned validation, size, preparation, sending or individual-send cleanup fails. The common root retains its kind,
	 * original cause and optional receipt; a missing receipt does not prove non-acceptance. Invalid arguments and application callback failures
	 * retain their documented boundaries.
	 * @throws MailSubmissionException If transport submission fails or only partially succeeds.
	 * @throws IllegalStateException If called from another thread or after the enclosing callback has ended.
	 */
	@NotNull
	MailSubmissionReceipt sendMail(@NotNull Email email);
}
