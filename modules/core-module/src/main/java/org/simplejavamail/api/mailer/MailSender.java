package org.simplejavamail.api.mailer;

import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;

/**
 * Scoped sender provided by {@link Mailer#withOpenConnection(OpenConnectionCallback)}.
 * <p>
 * The sender uses the SMTP connection owned by the surrounding {@link Mailer} call. It is not closeable and does not expose Jakarta Mail's
 * {@code Transport}.
 */
public interface MailSender {

	/**
	 * Sends one email over the surrounding open SMTP connection.
	 * <p>
	 * The email is processed like {@link Mailer#sendMailSync(Email)}: defaults and overrides are applied, validation runs,
	 * the email is converted to a Jakarta Mail message, and the message is submitted to the SMTP server before this method returns.
	 *
	 * @param email The email to send.
	 * @throws MailException Can be thrown if an email isn't validating correctly, or some other problem occurs during sending.
	 * @throws MailSubmissionException If transport submission fails or only partially succeeds.
	 */
	void sendMail(Email email);

	/**
	 * Sends one email over the surrounding open SMTP connection and returns a submission receipt.
	 * <p>
	 * The email is processed like {@link Mailer#sendMailAndGetReceiptSync(Email)}. The returned receipt describes SMTP
	 * submission acceptance using provider-neutral status and recipient groups. It does not prove final recipient mailbox delivery. A failed or partial
	 * submission throws {@link MailSubmissionException}, whose receipt contains the same structured facts and whose cause is the original Jakarta Mail
	 * failure.
	 *
	 * @param email The email to send.
	 * @return A receipt for the completed submission.
	 * @throws MailException Can be thrown if an email isn't validating correctly, or some other problem occurs during sending.
	 * @throws MailSubmissionException If transport submission fails or only partially succeeds.
	 */
	default MailSubmissionReceipt sendMailAndGetReceipt(Email email) {
		sendMail(email);
		return new MailSubmissionReceipt(email.getId(), null, java.time.Instant.now());
	}
}
