package org.simplejavamail.mailer;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailRecipientDisposition;
import org.simplejavamail.api.mailer.MailRecipientResult;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.SmtpRecipientStatus;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.api.mailer.spi.MailTransportResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailSubmissionReceiptTest {

	@Test
	void receiptReturningMethodsRequireAnExplicitMailerImplementation() throws Exception {
		assertThat(Mailer.class.getMethod("sendMailAndGetReceipt", Email.class).isDefault()).isFalse();
		assertThat(Mailer.class.getMethod("sendMailAndGetReceipt", Email.class, boolean.class).isDefault()).isFalse();
	}

	@ParameterizedTest
	@CsvSource({"accepted,ACCEPTED,DO_NOT_RETRY", "rejected,REJECTED,CALLER_POLICY_REQUIRED", "unknown,UNKNOWN,CALLER_POLICY_REQUIRED"})
	void restoresReceiptsWrittenBeforeRecipientFieldsExisted(final String fixture, final MailSubmissionStatus status,
			final MailRetryDisposition retryDisposition) throws Exception {
		final MailSubmissionReceipt restored = readFixture(fixture);
		assertThat(restored.getEmailId()).isEqualTo("<legacy-response>");
		assertThat(restored.getSubmittedAt()).isEqualTo(Instant.parse("2026-09-08T12:00:00Z"));
		assertThat(restored.getStatus()).isEqualTo(status);
		assertThat(restored.getRetryDisposition()).isEqualTo(retryDisposition);
		assertThat(restored.getRecipientResults()).isEmpty();
		assertThat(restored.getRetryableRecipients()).isEmpty();
		assertStableViews(restored);
	}

	@Test
	void restoresGroupedReceiptsWrittenBeforeDetailedRecipientFieldsExisted() throws Exception {
		final MailSubmissionReceipt restored = readFixture("groups");
		assertThat(restored.getEmailId()).isEqualTo("<legacy-groups>");
		assertThat(restored.getStatus()).isEqualTo(MailSubmissionStatus.PARTIALLY_ACCEPTED);
		assertThat(restored.getRetryDisposition()).isEqualTo(MailRetryDisposition.CALLER_POLICY_REQUIRED);
		assertThat(restored.getRecipientResults()).extracting(MailRecipientResult::getOriginalAddress)
				.containsExactly("accepted@example.org", "unsent@example.org", "invalid@example.org");
		assertThat(restored.getRecipientResults()).allSatisfy(recipient -> {
			assertThat(recipient.getRcptAttempted()).isEmpty();
			assertThat(recipient.getRcptResponse()).isEmpty();
		});
		assertStableViews(restored);
	}

	@Test
	void restoresRichReceiptsWhoseCompatibilityListsWereNotStored() throws Exception {
		final MailSubmissionReceipt restored = readFixture("rich");
		assertThat(restored.getEmailId()).isEqualTo("<rich>");
		assertThat(restored.getStatus()).isEqualTo(MailSubmissionStatus.PARTIALLY_ACCEPTED);
		assertThat(restored.getRetryDisposition()).isEqualTo(MailRetryDisposition.SAFE_TO_RETRY_UNACCEPTED);
		assertThat(restored.getAcceptedRecipients()).containsExactly("accepted@example.org");
		assertThat(restored.getValidUnsentRecipients()).containsExactly("temporary@example.org");
		assertThat(restored.getRetryableRecipients()).extracting(MailRecipientResult::getOriginalAddress)
				.containsExactly("temporary@example.org");
		assertThat(restored.getRecipientResults()).extracting(recipient -> recipient.getRcptResponse().orElseThrow().getReturnCode())
				.containsExactly(450, 250);
		assertStableViews(restored);
	}

	@ParameterizedTest
	@ValueSource(strings = {"550 5.1.1 unknown", "550 5.1.1 unknown\r\n", "550-5.1.1 first\r\n550 5.1.1 last\r\n"})
	void extractsConsistentEnhancedStatus(final String reply) {
		assertThat(new SmtpServerResponse(550, reply).getEnhancedStatusCode()).contains("5.1.1");
	}

	@ParameterizedTest
	@ValueSource(strings = {"550 unknown", "550 unknown 5.1.1", "550 4.1.1 mismatch", "450 5.1.1 mismatch",
			"550 5.01.1 leading zero", "550 5.1.1000 too long", "550 5.1.1suffix", "550 5.1", "550 5.1.1.1",
			"550-5.1.1 first\n550 5.2.2 last", "550-5.1.1 first\n550 missing last", "550 5.1.1 first\n\n550 5.1.1 last", ""})
	void doesNotInventEnhancedStatus(final String reply) {
		assertThat(new SmtpServerResponse(550, reply).getEnhancedStatusCode()).isEmpty();
	}

	@Test
	void preservesUnregisteredEnhancedStatusAndAbsence() {
		assertThat(new SmtpServerResponse(450, "450 4.999.999 future status").getEnhancedStatusCode()).contains("4.999.999");
		assertThat(new SmtpServerResponse(250, null).getEnhancedStatusCode()).isEmpty();
	}

	@Test
	void keepsRecipientAndMessageAcceptanceSeparateAndViewsImmutable() throws Exception {
		final List<MailRecipientResult> recipients = new ArrayList<>();
		recipients.add(recipient("temporary@example.org", MailRecipientDisposition.VALID_UNSENT, 450));
		recipients.add(recipient("accepted@example.org", MailRecipientDisposition.ACCEPTED, 250));
		recipients.add(recipient("invalid@example.org", MailRecipientDisposition.INVALID, 550));
		final MailSubmissionReceipt receipt = new MailSubmissionReceipt("<id>", new SmtpServerResponse(250, "250 queued"),
				Instant.now(), MailSubmissionStatus.PARTIALLY_ACCEPTED, recipients, MailRetryDisposition.SAFE_TO_RETRY_UNACCEPTED);
		recipients.clear();

		assertThat(receipt.getRecipientResults()).extracting(MailRecipientResult::getOriginalAddress)
				.containsExactly("temporary@example.org", "accepted@example.org", "invalid@example.org");
		assertThat(receipt.getAcceptedRecipients()).containsExactly("accepted@example.org");
		assertThat(receipt.getValidUnsentRecipients()).containsExactly("temporary@example.org");
		assertThat(receipt.getInvalidRecipients()).containsExactly("invalid@example.org");
		assertThat(receipt.getRetryableRecipients()).extracting(MailRecipientResult::getOriginalAddress).containsExactly("temporary@example.org");
		assertThatThrownBy(() -> receipt.getRecipientResults().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> receipt.getAcceptedRecipients().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> receipt.getValidUnsentRecipients().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> receipt.getInvalidRecipients().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> receipt.getRetryableRecipients().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertStableViews(receipt);
		final MailSubmissionReceipt restored = roundTrip(receipt);
		assertStableViews(restored);
		assertThat(restored.getAcceptedRecipients()).isEqualTo(receipt.getAcceptedRecipients());
		assertThat(restored.getRetryDisposition()).isEqualTo(receipt.getRetryDisposition());
		assertThat(restored.getRecipientResults().get(0).getRcptStatus()).isEqualTo(SmtpRecipientStatus.TEMPORARILY_REJECTED);
	}

	@Test
	void legacyConstructorsRemainUsableWithoutInventingRcptReplies() throws Exception {
		final MailSubmissionReceipt legacy = new MailSubmissionReceipt(null, null, Instant.now(), MailSubmissionStatus.REJECTED,
				Collections.emptyList(), List.of("unsent@example.org"), List.of("invalid@example.org"));
		assertStableViews(legacy);
		final MailSubmissionReceipt restored = roundTrip(legacy);
		assertStableViews(restored);
		assertThat(restored.getRetryDisposition()).isEqualTo(MailRetryDisposition.CALLER_POLICY_REQUIRED);
		assertThat(restored.getRecipientResults()).hasSize(2).allSatisfy(recipient -> {
			assertThat(recipient.getRcptAttempted()).isEmpty();
			assertThat(recipient.getRcptResponse()).isEmpty();
		});
	}

	@Test
	void legacyAdaptersKeepEnvelopeOrderAndCaseSensitiveLocalPartsWithoutInventingReplies() throws Exception {
		final InternetAddress accepted = new InternetAddress("Named <Case@example.org>");
		final InternetAddress unsent = new InternetAddress("case@example.org");
		final InternetAddress unknown = new InternetAddress("unknown@example.org");
		final MessagingException failure = new MessagingException("incomplete provider facts");
		final MailTransportResult result = MailTransportResult.failed(failure, null, new Address[]{accepted}, new Address[]{unsent}, null)
				.withEnvelopeRecipients(new Address[]{unsent, unknown, accepted});
		assertThat(result.getFailure()).containsSame(failure);
		assertThat(result.getRetryDisposition()).isEqualTo(MailRetryDisposition.CALLER_POLICY_REQUIRED);
		assertThat(result.getRecipientResults()).extracting(MailRecipientResult::getDisposition).containsExactly(
				MailRecipientDisposition.VALID_UNSENT, MailRecipientDisposition.UNKNOWN, MailRecipientDisposition.ACCEPTED);
		assertThat(result.getRecipientResults()).allSatisfy(recipient -> {
			assertThat(recipient.getRcptAttempted()).isEmpty();
			assertThat(recipient.getRcptResponse()).isEmpty();
		});
		accepted.setAddress("mutated@example.org");
		assertThat(result.getRecipientResults().get(2).getEnvelopeAddress()).contains("Case@example.org");
		assertThatThrownBy(() -> result.getRecipientResults().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void unknownFinalAcceptanceDoesNotTurnPositiveRcptIntoRetryPermission() {
		final MailRecipientResult ambiguous = recipient("ambiguous@example.org", MailRecipientDisposition.UNKNOWN, 250);
		final MailSubmissionReceipt receipt = new MailSubmissionReceipt(null, null, Instant.now(), MailSubmissionStatus.UNKNOWN,
				List.of(ambiguous), MailRetryDisposition.DUPLICATE_RISK);
		assertThat(ambiguous.getRcptStatus()).isEqualTo(SmtpRecipientStatus.ACCEPTED);
		assertThat(receipt.getAcceptedRecipients()).isEmpty();
		assertThat(receipt.getValidUnsentRecipients()).isEmpty();
		assertThat(receipt.getRetryableRecipients()).isEmpty();
	}

	@Test
	void rejectsContradictoryAttemptFactsAndPreservesUnknown() {
		assertThatThrownBy(() -> new MailRecipientResult("a", "a", MailRecipientDisposition.UNKNOWN, false,
				new SmtpServerResponse(250, "250 OK"))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MailRecipientResult("a", "a", MailRecipientDisposition.UNKNOWN, true,
				new SmtpServerResponse(-1, "[EOF]"))).isInstanceOf(IllegalArgumentException.class);
		assertThat(new MailRecipientResult("custom address", null, MailRecipientDisposition.UNKNOWN, null, null).getEnvelopeAddress()).isEmpty();
		assertThat(new MailRecipientResult("a", "a", MailRecipientDisposition.VALID_UNSENT, false, null).getRcptStatus())
				.isEqualTo(SmtpRecipientStatus.NOT_ATTEMPTED);
	}

	private static MailRecipientResult recipient(final String address, final MailRecipientDisposition disposition, final int replyCode) {
		return new MailRecipientResult(address, address, disposition, true, new SmtpServerResponse(replyCode, replyCode + " reply"));
	}

	private static void assertStableViews(final MailSubmissionReceipt receipt) {
		assertThat(receipt.getRecipientResults()).isSameAs(receipt.getRecipientResults());
		assertThat(receipt.getAcceptedRecipients()).isSameAs(receipt.getAcceptedRecipients());
		assertThat(receipt.getValidUnsentRecipients()).isSameAs(receipt.getValidUnsentRecipients());
		assertThat(receipt.getInvalidRecipients()).isSameAs(receipt.getInvalidRecipients());
		assertThat(receipt.getRetryableRecipients()).isSameAs(receipt.getRetryableRecipients());
		assertThatThrownBy(() -> receipt.getRecipientResults().add(null)).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> receipt.getAcceptedRecipients().add("changed")).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> receipt.getValidUnsentRecipients().add("changed")).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> receipt.getInvalidRecipients().add("changed")).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> receipt.getRetryableRecipients().add(null)).isInstanceOf(UnsupportedOperationException.class);
	}

	private static MailSubmissionReceipt readFixture(final String name) throws Exception {
		try (InputStream fixture = MailSubmissionReceiptTest.class.getResourceAsStream("/serialization/receipt-" + name + ".base64")) {
			assertThat(fixture).isNotNull();
			try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(Base64.getMimeDecoder().decode(fixture.readAllBytes())))) {
				return (MailSubmissionReceipt) input.readObject();
			}
		}
	}

	private static MailSubmissionReceipt roundTrip(final MailSubmissionReceipt receipt) throws Exception {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
			output.writeObject(receipt);
		}
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			return (MailSubmissionReceipt) input.readObject();
		}
	}
}
