package org.simplejavamail.mailer.internal.util;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.api.mailer.spi.MailTransportResult;

import static org.assertj.core.api.Assertions.assertThat;

class MailTransportResultTest {

	@Test
	void partialFailureRetainsOriginalExceptionAndDefensivelyCopiesEveryRecipientGroup() throws Exception {
		final Address accepted = new InternetAddress("accepted@example.com");
		final Address unsent = new InternetAddress("unsent@example.com");
		final Address invalid = new InternetAddress("invalid@example.com");
		final Address[] acceptedInput = {accepted};
		final Address[] unsentInput = {unsent};
		final Address[] invalidInput = {invalid};
		final SendFailedException failure = new SendFailedException(
				"partial", null, acceptedInput, unsentInput, invalidInput);

		final MailTransportResult result = MailTransportResult.failed(
				failure, new SmtpServerResponse(550, "recipient rejected"));
		acceptedInput[0] = invalid;
		unsentInput[0] = invalid;
		invalidInput[0] = accepted;

		assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.PARTIALLY_ACCEPTED);
		assertThat(result.getFailure()).containsSame(failure);
		assertThat(result.getAcceptedRecipients()).containsExactly(accepted);
		assertThat(result.getValidUnsentRecipients()).containsExactly(unsent);
		assertThat(result.getInvalidRecipients()).containsExactly(invalid);

		final Address[] returned = result.getAcceptedRecipients();
		returned[0] = invalid;
		assertThat(result.getAcceptedRecipients()).containsExactly(accepted);
	}

	@Test
	void classifiedFailureWithNoAcceptedRecipientIsRejected() throws Exception {
		final SendFailedException failure = new SendFailedException("rejected", null, null,
				new Address[]{new InternetAddress("unsent@example.com")},
				new Address[]{new InternetAddress("invalid@example.com")});

		final MailTransportResult result = MailTransportResult.failed(failure, null);

		assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
		assertThat(result.getAcceptedRecipients()).isEmpty();
	}

	@Test
	void negativeResponseRejectsAnOtherwiseUnclassifiedFailure() {
		final MessagingException failure = new MessagingException("submission rejected");

		final MailTransportResult result = MailTransportResult.failed(
				failure, new SmtpServerResponse(550, "message rejected"));

		assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
		assertThat(result.getFailure()).containsSame(failure);
	}

	@Test
	void unclassifiedMessagingFailureIsExplicitlyUnknown() {
		final MessagingException failure = new MessagingException("connection dropped after DATA");

		final MailTransportResult result = MailTransportResult.failed(failure, null);

		assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
		assertThat(result.getStatus().isServerAcceptanceKnown()).isFalse();
		assertThat(result.getFailure()).containsSame(failure);
	}

	@Test
	void explicitlyUnknownFailureRetainsOnlyCertainRecipientFacts() throws Exception {
		final MessagingException failure = new MessagingException("final DATA response was not received");
		final Address validUnsent = new InternetAddress("unsent@example.com");
		final Address invalid = new InternetAddress("invalid@example.com");
		final Address replacement = new InternetAddress("replacement@example.com");
		final Address[] validUnsentInput = {validUnsent};
		final Address[] invalidInput = {invalid};

		final MailTransportResult result = MailTransportResult.failedWithUnknownAcceptance(
				failure, validUnsentInput, invalidInput);
		validUnsentInput[0] = replacement;
		invalidInput[0] = replacement;

		assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
		assertThat(result.getSmtpResponse()).isEmpty();
		assertThat(result.getAcceptedRecipients()).isEmpty();
		assertThat(result.getValidUnsentRecipients()).containsExactly(validUnsent);
		assertThat(result.getInvalidRecipients()).containsExactly(invalid);
		assertThat(result.getFailure()).containsSame(failure);
		assertThat(result.isSuccessful()).isFalse();

		result.getValidUnsentRecipients()[0] = replacement;
		result.getInvalidRecipients()[0] = replacement;
		assertThat(result.getValidUnsentRecipients()).containsExactly(validUnsent);
		assertThat(result.getInvalidRecipients()).containsExactly(invalid);
	}

	@Test
	void explicitlyUnknownFailureNormalizesMissingRecipientFacts() {
		final MailTransportResult result = MailTransportResult.failedWithUnknownAcceptance(
				new MessagingException("final DATA response was not received"), null, null);

		assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
		assertThat(result.getAcceptedRecipients()).isEmpty();
		assertThat(result.getValidUnsentRecipients()).isEmpty();
		assertThat(result.getInvalidRecipients()).isEmpty();
	}

	@Test
	void successfulResultReportsAllTransportRecipientsAsAccepted() throws Exception {
		final Address[] recipients = {new InternetAddress("accepted@example.com")};

		final MailTransportResult result = MailTransportResult.accepted(
				recipients, new SmtpServerResponse(250, "queued"));
		recipients[0] = new InternetAddress("changed@example.com");

		assertThat(result.isSuccessful()).isTrue();
		assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
		assertThat(result.getAcceptedRecipients()).extracting(Address::toString)
				.containsExactly("accepted@example.com");
	}
}
