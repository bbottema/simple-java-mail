package org.simplejavamail.mailer.internal.util;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.api.mailer.spi.MailTransportResult;

import static org.assertj.core.api.Assertions.assertThat;

class MailTransportFailureInferenceTest {

	@ParameterizedTest
	@ValueSource(ints = {-1, 0, 100, 199, 200, 250, 299, 300, 354, 399, 600, Integer.MAX_VALUE})
	void nonNegativeAndUnusableRepliesDoNotEstablishRejection(final int replyCode) {
		final MessagingException failure = new MessagingException("submission outcome unavailable");
		final SmtpServerResponse response = new SmtpServerResponse(replyCode, replyCode + " captured provider text");

		final MailTransportResult result = MailTransportResult.failed(failure, response);

		assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
		assertThat(result.getStatus().isServerAcceptanceKnown()).isFalse();
		assertThat(result.getRetryDisposition()).isEqualTo(MailRetryDisposition.DUPLICATE_RISK);
		assertThat(result.getFailure()).containsSame(failure);
		assertThat(result.getSmtpResponse()).containsSame(response);
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 421, 499, 500, 550, 599})
	void actualNegativeRepliesEstablishRejection(final int replyCode) {
		final MessagingException failure = new MessagingException("submission rejected");
		final SmtpServerResponse response = new SmtpServerResponse(replyCode, replyCode + " rejected");

		final MailTransportResult result = MailTransportResult.failed(failure, response);

		assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
		assertThat(result.getRetryDisposition()).isEqualTo(MailRetryDisposition.CALLER_POLICY_REQUIRED);
		assertThat(result.getFailure()).containsSame(failure);
		assertThat(result.getSmtpResponse()).containsSame(response);
	}

	@Test
	void missingResponseRetainsUnknownAcceptanceAndDuplicateRisk() {
		final MessagingException failure = new MessagingException("no response was captured");

		final MailTransportResult result = MailTransportResult.failed(failure, null);

		assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
		assertThat(result.getRetryDisposition()).isEqualTo(MailRetryDisposition.DUPLICATE_RISK);
		assertThat(result.getFailure()).containsSame(failure);
		assertThat(result.getSmtpResponse()).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 354, 550})
	void knownRecipientGroupsKeepTheirPrecedenceOverResponseInference(final int replyCode) throws Exception {
		final MessagingException failure = new MessagingException("classified submission failure");
		final SmtpServerResponse response = new SmtpServerResponse(replyCode, "captured reply");
		final Address[] accepted = {new InternetAddress("accepted@example.org")};
		final Address[] unsent = {new InternetAddress("unsent@example.org")};
		final Address[] invalid = {new InternetAddress("invalid@example.org")};

		final MailTransportResult partial = MailTransportResult.failed(failure, response, accepted, unsent, invalid);
		assertThat(partial.getStatus()).isEqualTo(MailSubmissionStatus.PARTIALLY_ACCEPTED);
		assertThat(partial.getAcceptedRecipients()).containsExactly(accepted);
		assertThat(partial.getValidUnsentRecipients()).containsExactly(unsent);
		assertThat(partial.getInvalidRecipients()).containsExactly(invalid);
		assertThat(partial.getRetryDisposition()).isEqualTo(MailRetryDisposition.CALLER_POLICY_REQUIRED);

		for (final MailTransportResult rejected : new MailTransportResult[]{
				MailTransportResult.failed(failure, response, null, unsent, null),
				MailTransportResult.failed(failure, response, null, null, invalid)}) {
			assertThat(rejected.getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
			assertThat(rejected.getRetryDisposition()).isEqualTo(MailRetryDisposition.CALLER_POLICY_REQUIRED);
			assertThat(rejected.getFailure()).containsSame(failure);
			assertThat(rejected.getSmtpResponse()).containsSame(response);
		}
	}
}
