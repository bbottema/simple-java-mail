package org.simplejavamail.mailer.internal.util;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.MailRecipientDisposition;
import org.simplejavamail.api.mailer.MailRecipientResult;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.api.mailer.spi.MailTransportResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailTransportResultTest {

	@Test
	void basicResultsCaptureRecipientFactsBeforeTheFirstGetterCall() throws Exception {
		final InternetAddress address = new InternetAddress("Before <before@example.org>");
		final MailTransportResult result = MailTransportResult.accepted(new Address[]{address}, null);
		address.setAddress("after@example.org");
		address.setPersonal("After");

		final List<MailRecipientResult> recipients = result.getRecipientResults();
		assertThat(recipients).singleElement().satisfies(recipient -> {
			assertThat(recipient.getOriginalAddress()).isEqualTo("Before <before@example.org>");
			assertThat(recipient.getEnvelopeAddress()).contains("before@example.org");
			assertThat(recipient.getDisposition()).isEqualTo(MailRecipientDisposition.ACCEPTED);
		});
		assertThat(result.getRecipientResults()).isSameAs(recipients);
		assertThatThrownBy(recipients::clear).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void envelopeCompletionUsesFrozenGroupsAndRetainsDuplicateOccurrences() throws Exception {
		final InternetAddress accepted = new InternetAddress("same@example.org");
		final InternetAddress unsent = new InternetAddress("same@example.org");
		final InternetAddress invalid = new InternetAddress("invalid@example.org");
		final MessagingException failure = new MessagingException("partial");
		final MailTransportResult basic = MailTransportResult.failed(failure, null,
				new Address[]{accepted}, new Address[]{unsent}, new Address[]{invalid});
		accepted.setAddress("mutated-accepted@example.org");
		unsent.setAddress("mutated-unsent@example.org");
		invalid.setAddress("mutated-invalid@example.org");

		final MailTransportResult complete = basic.withEnvelopeRecipients(new Address[]{
				new InternetAddress("same@example.org"), new InternetAddress("unknown@example.org"),
				new InternetAddress("same@example.org"), new InternetAddress("invalid@example.org")});
		assertThat(complete.getRecipientResults()).extracting(MailRecipientResult::getDisposition).containsExactly(
				MailRecipientDisposition.ACCEPTED, MailRecipientDisposition.UNKNOWN,
				MailRecipientDisposition.VALID_UNSENT, MailRecipientDisposition.INVALID);
		assertThat(complete.getFailure()).containsSame(failure);
		assertThat(complete.getRetryDisposition()).isEqualTo(basic.getRetryDisposition());
		assertThat(complete.withEnvelopeRecipients(new Address[0])).isSameAs(complete);
		assertThat(basic.getRecipientResults()).hasSize(3);
	}

	@Test
	void customAddressFactsAreAlsoCapturedBeforeEnvelopeCompletion() {
		final MutableAddress address = new MutableAddress("original custom recipient");
		final MailTransportResult basic = MailTransportResult.accepted(new Address[]{address}, null);
		address.value = "changed custom recipient";
		assertThat(basic.getRecipientResults()).singleElement().satisfies(recipient -> {
			assertThat(recipient.getOriginalAddress()).isEqualTo("original custom recipient");
			assertThat(recipient.getEnvelopeAddress()).isEmpty();
		});
		assertThat(basic.withEnvelopeRecipients(new Address[]{new MutableAddress("original custom recipient")}).getRecipientResults())
				.extracting(MailRecipientResult::getDisposition).containsExactly(MailRecipientDisposition.ACCEPTED);
	}

	@Test
	void suppliedDetailedSnapshotsIncludingEmptyOnesAreNotReinterpreted() throws Exception {
		final MailTransportResult basic = MailTransportResult.unknown(null);
		final MailRecipientResult recipient = new MailRecipientResult("recipient", null, MailRecipientDisposition.UNKNOWN, null, null);
		final List<MailRecipientResult> supplied = new ArrayList<>(List.of(recipient));
		final MailTransportResult detailed = basic.withRecipientResults(supplied, MailRetryDisposition.DUPLICATE_RISK);
		supplied.clear();
		assertThat(detailed.getRecipientResults()).containsExactly(recipient);
		assertThat(detailed.getRecipientResults()).isSameAs(detailed.getRecipientResults());
		assertThat(detailed.withEnvelopeRecipients(new Address[0])).isSameAs(detailed);
		assertThat(detailed.getRetryDisposition()).isEqualTo(MailRetryDisposition.DUPLICATE_RISK);
		final MailTransportResult empty = basic.withRecipientResults(List.of(), MailRetryDisposition.CALLER_POLICY_REQUIRED);
		assertThat(empty.withEnvelopeRecipients(new Address[]{new InternetAddress("ignored@example.org")})).isSameAs(empty);
	}

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

	private static final class MutableAddress extends Address {
		private static final long serialVersionUID = 1L;
		private String value;

		private MutableAddress(final String value) {
			this.value = value;
		}

		@Override
		public String getType() {
			return "custom";
		}

		@Override
		public String toString() {
			return value;
		}

		@Override
		public boolean equals(final Object other) {
			return this == other;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(this);
		}
	}
}
