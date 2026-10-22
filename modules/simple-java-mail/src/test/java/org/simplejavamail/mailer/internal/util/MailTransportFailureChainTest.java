package org.simplejavamail.mailer.internal.util;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.MailRecipientDisposition;
import org.simplejavamail.api.mailer.MailRecipientResult;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.spi.MailTransportResult;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class MailTransportFailureChainTest {

	@Test
	void nestedFailureRetainsTheOuterFailureAndFrozenRecipientFacts() throws Exception {
		final InternetAddress accepted = new InternetAddress("Accepted <accepted@example.org>");
		final InternetAddress unsent = new InternetAddress("Unsent <unsent@example.org>");
		final InternetAddress invalid = new InternetAddress("Invalid <invalid@example.org>");
		final Address[] acceptedInput = {accepted};
		final Address[] unsentInput = {unsent};
		final Address[] invalidInput = {invalid};
		final SendFailedException submission = new SendFailedException("partial", null,
				acceptedInput, unsentInput, invalidInput);
		final MessagingException outer = new MessagingException("outer", new MessagingException("inner", submission));

		final MailTransportResult result = MailTransportResult.failed(outer, null);

		assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.PARTIALLY_ACCEPTED);
		assertThat(result.getFailure()).containsSame(outer);
		assertThat(result.getAcceptedRecipients()).containsExactly(accepted);
		assertThat(result.getValidUnsentRecipients()).containsExactly(unsent);
		assertThat(result.getInvalidRecipients()).containsExactly(invalid);
		assertThat(result.getRetryDisposition()).isEqualTo(MailRetryDisposition.CALLER_POLICY_REQUIRED);
		acceptedInput[0] = invalid;
		unsentInput[0] = invalid;
		invalidInput[0] = accepted;
		result.getAcceptedRecipients()[0] = invalid;
		assertThat(result.getAcceptedRecipients()).containsExactly(accepted);
		assertThat(result.getValidUnsentRecipients()).containsExactly(unsent);
		assertThat(result.getInvalidRecipients()).containsExactly(invalid);
		accepted.setAddress("mutated-accepted@example.org");
		unsent.setAddress("mutated-unsent@example.org");
		invalid.setAddress("mutated-invalid@example.org");

		final MailTransportResult ordered = result.withEnvelopeRecipients(new Address[]{
				new InternetAddress("unsent@example.org"), new InternetAddress("accepted@example.org"),
				new InternetAddress("invalid@example.org")});
		assertThat(ordered.getRecipientResults()).extracting(MailRecipientResult::getEnvelopeAddress)
				.containsExactly("unsent@example.org", "accepted@example.org", "invalid@example.org");
		assertThat(ordered.getRecipientResults()).extracting(MailRecipientResult::getDisposition)
				.containsExactly(MailRecipientDisposition.VALID_UNSENT, MailRecipientDisposition.ACCEPTED, MailRecipientDisposition.INVALID);
		assertThat(ordered.getFailure()).containsSame(outer);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 4})
	void firstSendFailedExceptionOwnsTheGroupsIncludingAtTheOuterBoundary(final int wrapperCount) throws Exception {
		final Address first = new InternetAddress("first@example.org");
		final Address later = new InternetAddress("later@example.org");
		final SendFailedException nested = new SendFailedException("later facts", null, null, null, new Address[]{later});
		MessagingException outer = new SendFailedException("first facts", nested, new Address[]{first}, null, null);
		for (int index = 0; index < wrapperCount; index++) {
			outer = new MessagingException("wrapper " + index, outer);
		}

		final MailTransportResult result = MailTransportResult.failed(outer, null);

		assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.PARTIALLY_ACCEPTED);
		assertThat(result.getAcceptedRecipients()).containsExactly(first);
		assertThat(result.getInvalidRecipients()).isEmpty();
		assertThat(result.getFailure()).containsSame(outer);
	}

	@Test
	void equalButDistinctWrappersDoNotStopTheSearch() throws Exception {
		final Address accepted = new InternetAddress("accepted@example.org");
		final SendFailedException submission = new SendFailedException("partial", null, new Address[]{accepted}, null, null);
		final MessagingException inner = new EqualMessagingException(submission);
		final MessagingException outer = new EqualMessagingException(inner);

		final MailTransportResult result = MailTransportResult.failed(outer, null);

		assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.PARTIALLY_ACCEPTED);
		assertThat(result.getAcceptedRecipients()).containsExactly(accepted);
		assertThat(result.getFailure()).containsSame(outer);
	}

	@Test
	void cyclesWithoutRecipientFactsTerminateWithoutReadingTheSameExceptionAgain() {
		final SingleReadMessagingException outer = new SingleReadMessagingException("outer");
		final SingleReadMessagingException inner = new SingleReadMessagingException("inner");
		assertThat(outer.setNextException(inner)).isTrue();
		assertThat(inner.setNextException(outer)).isTrue();

		final MailTransportResult result = MailTransportResult.failed(outer, null);

		assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
		assertThat(result.getRetryDisposition()).isEqualTo(MailRetryDisposition.DUPLICATE_RISK);
		assertThat(result.getFailure()).containsSame(outer);
		assertThat(result.getRecipientResults()).isEmpty();
	}

	@Test
	void arbitraryJavaCausesOutsideTheNextExceptionChainDoNotSupplyRecipientFacts() throws Exception {
		final Address accepted = new InternetAddress("accepted@example.org");
		final SendFailedException submission = new SendFailedException("partial", null, new Address[]{accepted}, null, null);
		final MessagingException outer = new MessagingException("outer", new IOException("Java cause wrapper", submission));

		final MailTransportResult result = MailTransportResult.failed(outer, null);

		assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
		assertThat(result.getRetryDisposition()).isEqualTo(MailRetryDisposition.DUPLICATE_RISK);
		assertThat(result.getAcceptedRecipients()).isEmpty();
		assertThat(result.getFailure()).containsSame(outer);
	}

	private static final class EqualMessagingException extends MessagingException {
		private static final long serialVersionUID = 1L;

		private EqualMessagingException(final Exception next) {
			super("equal wrapper", next);
		}

		@Override
		public boolean equals(final Object other) {
			return other instanceof EqualMessagingException;
		}

		@Override
		public int hashCode() {
			return 1;
		}
	}

	private static final class SingleReadMessagingException extends MessagingException {
		private static final long serialVersionUID = 1L;
		private boolean read;

		private SingleReadMessagingException(final String message) {
			super(message);
		}

		@Override
		public synchronized Exception getNextException() {
			if (read) {
				throw new AssertionError("A cyclic next-exception chain must not revisit an exception");
			}
			read = true;
			return super.getNextException();
		}
	}
}
