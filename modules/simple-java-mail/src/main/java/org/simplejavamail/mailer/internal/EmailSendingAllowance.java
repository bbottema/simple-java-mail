package org.simplejavamail.mailer.internal;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageHelper;
import org.simplejavamail.internal.util.concurrent.MailSendControl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.simplejavamail.internal.util.MiscUtil.asInternetAddresses;

/** Costs the intended envelope without rendering the body, then waits on the selected registration's allowance. */
final class EmailSendingAllowance {

	private EmailSendingAllowance() {
	}

	static SendingAllowance.Reservation reserve(final SendingAllowance allowance, final Session session, final Email email,
			final MailSendControl control, final MailSendDiagnosticsRecorder diagnostics) throws MessagingException {
		if (!allowance.isEnabled()) {
			return reserveUnlimited(allowance, control, diagnostics);
		}
		final Address[] recipients = resolveIntendedRecipients(email, session);
		final int recipientCount = countRecipientOccurrences(recipients);
		return reserve(allowance, recipientCount, control, diagnostics);
	}

	static SendingAllowance.Reservation reserve(final SendingAllowance allowance, final Address[] recipients,
			final MailSendControl control, final MailSendDiagnosticsRecorder diagnostics) throws MessagingException {
		if (!allowance.isEnabled()) {
			return reserveUnlimited(allowance, control, diagnostics);
		}
		return reserve(allowance, countRecipientOccurrences(recipients), control, diagnostics);
	}

	private static Address[] resolveIntendedRecipients(final Email email, final Session session) throws MessagingException {
		return email.getOverrideReceivers().isEmpty()
				? MimeMessageHelper.resolveRecipientHeaders(email, session)
				: asInternetAddresses(email.getOverrideReceivers(), UTF_8).toArray(new Address[0]);
	}

	private static SendingAllowance.Reservation reserveUnlimited(final SendingAllowance allowance, final MailSendControl control,
			final MailSendDiagnosticsRecorder diagnostics) {
		diagnostics.useUnlimitedSending();
		return allowance.reserve(0, control);
	}

	private static SendingAllowance.Reservation reserve(final SendingAllowance allowance, final int recipientCount,
			final MailSendControl control, final MailSendDiagnosticsRecorder diagnostics) {
		diagnostics.startRateLimitWait();
		final SendingAllowance.Reservation reservation = allowance.reserve(recipientCount, control);
		diagnostics.rateLimitWaitCompleted();
		return reservation;
	}

	/** Jakarta address groups can occur in custom headers; their members, not the group label, are envelope recipients. */
	private static int countRecipientOccurrences(final Address[] recipients) throws MessagingException {
		int count = 0;
		for (final Address recipient : recipients) {
			final int cost = recipient instanceof InternetAddress && ((InternetAddress) recipient).isGroup()
					? countRecipientOccurrences(((InternetAddress) recipient).getGroup(true)) : 1;
			count = Math.addExact(count, cost);
		}
		return count;
	}
}
