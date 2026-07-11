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
	 * The email is processed like {@link Mailer#sendMail(Email, boolean)} with {@code async=false}: defaults and overrides are applied, validation runs,
	 * the email is converted to a Jakarta Mail message, and the message is submitted to the SMTP server before this method returns.
	 *
	 * @param email The email to send.
	 * @throws MailException Can be thrown if an email isn't validating correctly, or some other problem occurs during sending.
	 */
	void sendMail(Email email);

	/**
	 * Sends one email over the surrounding open SMTP connection and returns a submission receipt.
	 * <p>
	 * The email is processed like {@link Mailer#sendMailAndGetReceipt(Email, boolean)} with {@code async=false}. The returned receipt describes SMTP
	 * submission acceptance when the underlying transport is Angus SMTP. It does not prove final recipient mailbox delivery.
	 *
	 * @param email The email to send.
	 * @return A receipt for the completed submission.
	 * @throws MailException Can be thrown if an email isn't validating correctly, or some other problem occurs during sending.
	 */
	default MailSubmissionReceipt sendMailAndGetReceipt(Email email) {
		sendMail(email);
		return new MailSubmissionReceipt(email.getId(), null, java.time.Instant.now());
	}
}
