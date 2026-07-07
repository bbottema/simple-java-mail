package org.simplejavamail.api.mailer;

/**
 * Callback executed while Simple Java Mail keeps one SMTP connection open.
 *
 * @param <E> The checked exception type the caller may throw from the callback.
 */
@FunctionalInterface
public interface OpenConnectionCallback<E extends Exception> {

	/**
	 * Runs caller-managed work with a sender bound to the open SMTP connection.
	 *
	 * @param sender Sender scoped to the surrounding open connection.
	 * @throws E Any checked exception thrown by caller code.
	 */
	void accept(MailSender sender) throws E;
}
