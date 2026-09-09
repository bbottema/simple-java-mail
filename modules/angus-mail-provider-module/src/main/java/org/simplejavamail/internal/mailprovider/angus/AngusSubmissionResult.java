package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPAddressSucceededException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailRecipientDisposition;
import org.simplejavamail.api.mailer.MailRecipientResult;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.SmtpRecipientStatus;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.api.mailer.spi.MailTransportResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Turns one Angus exception chain into submission facts without confusing RCPT success with DATA acceptance. */
final class AngusSubmissionResult {

	private AngusSubmissionResult() {
	}

	@NotNull
	static MailTransportResult fromFailure(final MessagingException failure, final Address[] envelope,
			@Nullable final SmtpServerResponse currentResponse) {
		final List<MessagingException> chain = exceptionChain(failure);
		final SMTPSendFailedException transaction = findTransactionFailure(chain);
		final List<RecipientReply> replies = recipientReplies(chain);
		final MailTransportResult submission = classifySubmission(failure, envelope, currentResponse, transaction, chain, replies);
		final boolean ambiguousAbort = hasAmbiguousAbortedRecipient(failure, envelope);
		final List<MailRecipientResult> recipients = describeRecipients(submission, envelope,
				ambiguousAbort ? Collections.emptyList() : replies, transaction, failure, ambiguousAbort);
		return submission.withRecipientResults(recipients, ambiguousAbort ? MailRetryDisposition.CALLER_POLICY_REQUIRED
				: retryDisposition(submission, recipients, transaction, replies));
	}

	private static boolean hasAmbiguousAbortedRecipient(final MessagingException failure, final Address[] envelope) {
		if (!(failure instanceof SMTPAddressFailedException)) {
			return false;
		}
		final Address aborted = ((SMTPAddressFailedException) failure).getAddress();
		return Arrays.stream(envelope).filter(address -> sameMailbox(address, aborted)).count() > 1;
	}

	@NotNull
	private static MailTransportResult classifySubmission(final MessagingException failure, final Address[] envelope,
			@Nullable final SmtpServerResponse currentResponse, @Nullable final SMTPSendFailedException transaction,
			final List<MessagingException> chain, final List<RecipientReply> replies) {
		if (transaction != null && ".".equals(transaction.getCommand()) && transaction.getReturnCode() <= 0) {
			return MailTransportResult.failedWithUnknownAcceptance(failure, knownUnsentRecipients(replies),
					transaction.getInvalidAddresses());
		}
		final SmtpServerResponse response = transaction == null ? currentResponse
				: smtpResponse(transaction.getReturnCode(), transaction.getMessage());
		if (failure == transaction && isFullyAcceptedReportingException(transaction, envelope, replies)) {
			return MailTransportResult.accepted(envelope, response);
		}
		final SendFailedException recipientGroups = findRecipientGroups(chain);
		if (failedBeforeData(transaction, failure)) {
			final Address[] invalid = recipientGroups == null ? null : recipientGroups.getInvalidAddresses();
			final List<Address> unsent = new ArrayList<>(Arrays.asList(envelope));
			if (invalid != null) {
				for (final Address address : invalid) {
					removeAddress(unsent, address);
				}
			}
			return MailTransportResult.failed(failure, response, null, unsent.toArray(new Address[0]), invalid);
		}
		return recipientGroups == null ? MailTransportResult.failed(failure, response)
				: MailTransportResult.failed(failure, response, recipientGroups.getValidSentAddresses(),
						recipientGroups.getValidUnsentAddresses(), recipientGroups.getInvalidAddresses());
	}

	private static boolean isFullyAcceptedReportingException(@Nullable final SMTPSendFailedException transaction,
			final Address[] envelope, final List<RecipientReply> replies) {
		return transaction != null && ".".equals(transaction.getCommand())
				&& transaction.getReturnCode() >= 200 && transaction.getReturnCode() < 300
				&& transaction.getValidSentAddresses() != null && transaction.getValidSentAddresses().length == envelope.length
				&& isEmpty(transaction.getValidUnsentAddresses()) && isEmpty(transaction.getInvalidAddresses())
				&& replies.stream().noneMatch(reply -> reply.failed);
	}

	private static boolean isEmpty(@Nullable final Address[] addresses) {
		return addresses == null || addresses.length == 0;
	}

	private static boolean failedBeforeData(@Nullable final SMTPSendFailedException transaction, final MessagingException failure) {
		return failure instanceof SMTPAddressFailedException || transaction != null
				&& (transaction.getCommand().startsWith("MAIL FROM:") || "DATA".equals(transaction.getCommand()));
	}

	@NotNull
	private static List<MailRecipientResult> describeRecipients(final MailTransportResult submission, final Address[] envelope,
			final List<RecipientReply> replies, @Nullable final SMTPSendFailedException transaction, final MessagingException failure,
			final boolean ambiguousAbort) {
		final List<Address> accepted = new ArrayList<>(Arrays.asList(submission.getAcceptedRecipients()));
		final List<Address> unsent = new ArrayList<>(Arrays.asList(submission.getValidUnsentRecipients()));
		final List<Address> invalid = new ArrayList<>(Arrays.asList(submission.getInvalidRecipients()));
		final List<RecipientReply> remainingReplies = new ArrayList<>(replies);
		final List<MailRecipientResult> recipients = new ArrayList<>();
		boolean pastAbortedRecipient = false;
		for (final Address address : envelope) {
			final RecipientReply reply = removeReply(remainingReplies, address);
			final Boolean attempted = rcptAttempted(reply, pastAbortedRecipient, transaction);
			final MailRecipientDisposition disposition = recipientDisposition(address, reply, submission.getStatus(), accepted, unsent, invalid);
			recipients.add(new MailRecipientResult(address.toString(), address instanceof InternetAddress
					? ((InternetAddress) address).getAddress() : null,
					disposition, attempted, reply == null ? null : reply.response));
			if (!ambiguousAbort && failure instanceof SMTPAddressFailedException
					&& sameMailbox(address, ((SMTPAddressFailedException) failure).getAddress())) {
				pastAbortedRecipient = true;
			}
		}
		return recipients;
	}

	@Nullable
	private static Boolean rcptAttempted(@Nullable final RecipientReply reply, final boolean pastAbortedRecipient,
			@Nullable final SMTPSendFailedException transaction) {
		if (reply != null) {
			return true;
		}
		if (pastAbortedRecipient || transaction != null && transaction.getCommand().startsWith("MAIL FROM:")) {
			return false;
		}
		return null;
	}

	@NotNull
	private static MailRecipientDisposition recipientDisposition(final Address address, @Nullable final RecipientReply reply,
			final MailSubmissionStatus status, final List<Address> accepted, final List<Address> unsent, final List<Address> invalid) {
		// Repeated mailbox occurrences may have different RCPT replies. Consume their matching groups individually.
		if (reply != null && reply.response != null && reply.response.getReturnCode() >= 500
				&& reply.response.getReturnCode() != 552 && removeAddress(invalid, address)) {
			return MailRecipientDisposition.INVALID;
		}
		if (reply == null || !reply.failed) {
			if (removeAddress(accepted, address)) {
				return MailRecipientDisposition.ACCEPTED;
			}
			if (reply != null && status == MailSubmissionStatus.UNKNOWN) {
				return MailRecipientDisposition.UNKNOWN;
			}
		}
		if (removeAddress(unsent, address)) {
			return MailRecipientDisposition.VALID_UNSENT;
		}
		if (removeAddress(invalid, address)) {
			return MailRecipientDisposition.INVALID;
		}
		return MailRecipientDisposition.UNKNOWN;
	}

	private static boolean removeAddress(final List<Address> addresses, final Address address) {
		for (int index = 0; index < addresses.size(); index++) {
			if (sameMailbox(address, addresses.get(index))) {
				addresses.remove(index);
				return true;
			}
		}
		return false;
	}

	@NotNull
	private static MailRetryDisposition retryDisposition(final MailTransportResult submission, final List<MailRecipientResult> recipients,
			@Nullable final SMTPSendFailedException transaction, final List<RecipientReply> replies) {
		if (submission.getStatus() == MailSubmissionStatus.ACCEPTED) {
			return MailRetryDisposition.DO_NOT_RETRY;
		}
		if (submission.getStatus() == MailSubmissionStatus.UNKNOWN
				|| recipients.stream().anyMatch(recipient -> recipient.getDisposition() == MailRecipientDisposition.UNKNOWN)) {
			return MailRetryDisposition.DUPLICATE_RISK;
		}
		if (transaction != null && transaction.getReturnCode() >= 500 && transaction.getReturnCode() < 600) {
			return MailRetryDisposition.DO_NOT_RETRY;
		}
		if (recipients.isEmpty() || transaction == null && replies.isEmpty()) {
			return MailRetryDisposition.CALLER_POLICY_REQUIRED;
		}
		final List<String> acceptedMailboxes = new ArrayList<>();
		for (final MailRecipientResult recipient : recipients) {
			if (recipient.getDisposition() == MailRecipientDisposition.ACCEPTED) {
				acceptedMailboxes.add(mailboxIdentity(recipient));
			}
		}
		int retryable = 0;
		for (final MailRecipientResult recipient : recipients) {
			if (recipient.getDisposition() == MailRecipientDisposition.VALID_UNSENT
					&& recipient.getRcptStatus() != SmtpRecipientStatus.PERMANENTLY_REJECTED) {
				if (acceptedMailboxes.contains(mailboxIdentity(recipient))) {
					return MailRetryDisposition.DUPLICATE_RISK;
				}
				retryable++;
			}
		}
		if (retryable == 0) {
			return MailRetryDisposition.DO_NOT_RETRY;
		}
		return retryable == recipients.size() ? MailRetryDisposition.SAFE_TO_RETRY_ALL : MailRetryDisposition.SAFE_TO_RETRY_UNACCEPTED;
	}

	@NotNull
	private static String mailboxIdentity(final MailRecipientResult recipient) {
		final String mailbox = recipient.getEnvelopeAddress().orElse(recipient.getOriginalAddress());
		final int domainStart = mailbox.lastIndexOf('@') + 1;
		// Domains are case-insensitive, but preserve the potentially case-sensitive local part.
		return mailbox.substring(0, domainStart) + mailbox.substring(domainStart).toLowerCase(Locale.ROOT);
	}

	@NotNull
	private static List<MessagingException> exceptionChain(final MessagingException failure) {
		final List<MessagingException> chain = new ArrayList<>();
		final Set<MessagingException> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Exception current = failure;
		while (current instanceof MessagingException && visited.add((MessagingException) current)) {
			final MessagingException exception = (MessagingException) current;
			chain.add(exception);
			current = exception.getNextException();
		}
		return chain;
	}

	@Nullable
	private static SMTPSendFailedException findTransactionFailure(final List<MessagingException> chain) {
		for (final MessagingException failure : chain) {
			if (failure instanceof SMTPSendFailedException) {
				return (SMTPSendFailedException) failure;
			}
		}
		return null;
	}

	@Nullable
	private static SendFailedException findRecipientGroups(final List<MessagingException> chain) {
		for (final MessagingException failure : chain) {
			if (failure instanceof SendFailedException) {
				return (SendFailedException) failure;
			}
		}
		return null;
	}

	@NotNull
	private static List<RecipientReply> recipientReplies(final List<MessagingException> chain) {
		final List<RecipientReply> replies = new ArrayList<>();
		for (final MessagingException exception : chain) {
			if (exception instanceof SMTPAddressSucceededException) {
				final SMTPAddressSucceededException success = (SMTPAddressSucceededException) exception;
				replies.add(new RecipientReply(success.getAddress(), smtpResponse(success.getReturnCode(), success.getMessage()), false));
			} else if (exception instanceof SMTPAddressFailedException) {
				final SMTPAddressFailedException failure = (SMTPAddressFailedException) exception;
				replies.add(new RecipientReply(failure.getAddress(), smtpResponse(failure.getReturnCode(), failure.getMessage()), true));
			}
		}
		return replies;
	}

	@Nullable
	private static RecipientReply removeReply(final List<RecipientReply> replies, final Address address) {
		for (int index = 0; index < replies.size(); index++) {
			if (sameMailbox(address, replies.get(index).address)) {
				return replies.remove(index);
			}
		}
		return null;
	}

	private static boolean sameMailbox(final Address first, final Address second) {
		if (first == null || second == null) {
			return false;
		}
		return first instanceof InternetAddress && second instanceof InternetAddress
				? ((InternetAddress) first).getAddress().equals(((InternetAddress) second).getAddress())
				: first.toString().equals(second.toString());
	}

	@NotNull
	private static Address[] knownUnsentRecipients(final List<RecipientReply> replies) {
		// Angus puts RCPT 552 in valid-unsent, although its permanent primary code still rules out retry advice.
		return replies.stream().filter(reply -> reply.response != null && (reply.response.getReturnCode() >= 400
				&& reply.response.getReturnCode() < 500 || reply.response.getReturnCode() == 552)).map(reply -> reply.address).toArray(Address[]::new);
	}

	@Nullable
	static SmtpServerResponse smtpResponse(final int code, @Nullable final String text) {
		return code >= 200 && code < 600 ? new SmtpServerResponse(code, text) : null;
	}

	private static final class RecipientReply {
		private final Address address;
		@Nullable private final SmtpServerResponse response;
		private final boolean failed;

		private RecipientReply(final Address address, @Nullable final SmtpServerResponse response, final boolean failed) {
			this.address = address;
			this.response = response;
			this.failed = failed;
		}
	}
}
