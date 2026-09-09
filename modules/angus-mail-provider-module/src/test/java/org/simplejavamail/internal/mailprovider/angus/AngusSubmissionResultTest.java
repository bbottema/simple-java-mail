package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPAddressSucceededException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.MailRecipientDisposition;
import org.simplejavamail.api.mailer.MailRecipientResult;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AngusSubmissionResultTest {

	@Test
	void recipientOrderComesFromEnvelopeEvenWhenExceptionChainIsReordered() throws Exception {
		final InternetAddress accepted = new InternetAddress("Accepted <CaseSensitive@example.org>");
		final InternetAddress temporary = new InternetAddress("temporary@example.org");
		final InternetAddress permanent = new InternetAddress("permanent@example.org");
		final SMTPAddressFailedException temporaryReply = new SMTPAddressFailedException(temporary, "RCPT TO:<temporary@example.org>",
				450, "450 4.2.0 try later");
		temporaryReply.setNextException(new SMTPAddressSucceededException(accepted, "RCPT TO:<CaseSensitive@example.org>", 250, "250 2.1.5 OK"));
		temporaryReply.setNextException(new SMTPAddressFailedException(permanent, "RCPT TO:<permanent@example.org>", 550, "550 5.1.1 no mailbox"));
		final SMTPSendFailedException failure = new SMTPSendFailedException(".", 250, "250 2.0.0 queued", temporaryReply,
				new Address[]{accepted}, new Address[]{temporary}, new Address[]{permanent});
		final MailTransportResult result = AngusSubmissionResult.fromFailure(failure, new Address[]{permanent, accepted, temporary}, null);

		assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.PARTIALLY_ACCEPTED);
		assertThat(result.getFailure()).containsSame(failure);
		assertThat(result.getRecipientResults()).extracting(recipient -> recipient.getRcptResponse().orElseThrow().getReturnCode())
				.containsExactly(550, 250, 450);
		assertThat(result.getRecipientResults().get(1).getOriginalAddress()).isEqualTo(accepted.toString());
		assertThat(result.getRecipientResults().get(1).getEnvelopeAddress()).contains("CaseSensitive@example.org");
		assertThat(result.getRetryDisposition()).isEqualTo(MailRetryDisposition.SAFE_TO_RETRY_UNACCEPTED);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void duplicateMailboxOccurrencesRetainSeparateReplyAndDispositionFacts(final boolean acceptedFirst) throws Exception {
		final InternetAddress address = new InternetAddress("same@example.org");
		final MessagingException acceptedReply = new SMTPAddressSucceededException(address, "RCPT TO:<same@example.org>", 250, "250 accepted");
		final MessagingException temporaryReply = new SMTPAddressFailedException(address, "RCPT TO:<same@example.org>", 450, "450 temporary");
		final MessagingException first = acceptedFirst ? acceptedReply : temporaryReply;
		first.setNextException(acceptedFirst ? temporaryReply : acceptedReply);
		final SMTPSendFailedException failure = new SMTPSendFailedException(".", 250, "250 queued", first,
				new Address[]{address}, new Address[]{address}, null);
		final MailTransportResult result = AngusSubmissionResult.fromFailure(failure, new Address[]{address, address}, null);

		assertThat(result.getRecipientResults()).extracting(MailRecipientResult::getDisposition)
				.containsExactly(acceptedFirst ? MailRecipientDisposition.ACCEPTED : MailRecipientDisposition.VALID_UNSENT,
						acceptedFirst ? MailRecipientDisposition.VALID_UNSENT : MailRecipientDisposition.ACCEPTED);
		assertThat(result.getRecipientResults()).extracting(recipient -> recipient.getRcptResponse().orElseThrow().getResponse())
				.containsExactly(acceptedFirst ? "250 accepted" : "450 temporary", acceptedFirst ? "450 temporary" : "250 accepted");
		assertThat(result.getRetryDisposition()).isEqualTo(MailRetryDisposition.DUPLICATE_RISK);
	}

	@Test
	void refusalToStartDataKeepsCaseSensitiveRecipientsInTheirOwnGroups() throws Exception {
		final InternetAddress unsent = new InternetAddress("mailbox@example.org");
		final InternetAddress invalid = new InternetAddress("MAILBOX@example.org");
		final SMTPAddressSucceededException reply = new SMTPAddressSucceededException(unsent, "RCPT TO:<mailbox@example.org>", 250, "250 OK");
		reply.setNextException(new SMTPAddressFailedException(invalid, "RCPT TO:<MAILBOX@example.org>", 550, "550 no mailbox"));
		final SMTPSendFailedException failure = new SMTPSendFailedException("DATA", 450, "450 unavailable", reply,
				null, new Address[]{unsent}, new Address[]{invalid});
		final MailTransportResult result = AngusSubmissionResult.fromFailure(failure, new Address[]{unsent, invalid}, null);
		assertThat(result.getRecipientResults()).extracting(MailRecipientResult::getDisposition)
				.containsExactly(MailRecipientDisposition.VALID_UNSENT, MailRecipientDisposition.INVALID);
		assertThat(result.getRetryDisposition()).isEqualTo(MailRetryDisposition.SAFE_TO_RETRY_UNACCEPTED);
	}

	@Test
	void abortedDuplicateRecipientDoesNotInventWhichOccurrenceWasAttempted() throws Exception {
		final InternetAddress repeated = new InternetAddress("same@example.org");
		final SMTPAddressFailedException failure = new SMTPAddressFailedException(repeated, "RCPT TO:<same@example.org>", 500, "500 rejected");
		final MailTransportResult result = AngusSubmissionResult.fromFailure(failure,
				new Address[]{repeated, repeated, new InternetAddress("after@example.org")}, null);
		assertThat(result.getFailure()).containsSame(failure);
		assertThat(result.getRetryDisposition()).isEqualTo(MailRetryDisposition.CALLER_POLICY_REQUIRED);
		assertThat(result.getRecipientResults()).allSatisfy(recipient -> {
			assertThat(recipient.getDisposition()).isEqualTo(MailRecipientDisposition.VALID_UNSENT);
			assertThat(recipient.getRcptAttempted()).isEmpty();
			assertThat(recipient.getRcptResponse()).isEmpty();
		});
	}

	@Test
	void retryAdviceRecognizesTheSameMailboxWithDifferentDomainCase() throws Exception {
		final InternetAddress accepted = new InternetAddress("same@EXAMPLE.ORG");
		final InternetAddress unaccepted = new InternetAddress("same@example.org");
		final SMTPAddressSucceededException reply = new SMTPAddressSucceededException(accepted, "RCPT TO:<same@EXAMPLE.ORG>", 250, "250 OK");
		reply.setNextException(new SMTPAddressFailedException(unaccepted, "RCPT TO:<same@example.org>", 450, "450 try later"));
		final SMTPSendFailedException failure = new SMTPSendFailedException(".", 250, "250 queued", reply,
				new Address[]{accepted}, new Address[]{unaccepted}, null);
		final MailTransportResult result = AngusSubmissionResult.fromFailure(failure, new Address[]{accepted, unaccepted}, null);
		assertThat(result.getRetryDisposition()).isEqualTo(MailRetryDisposition.DUPLICATE_RISK);
	}

	@Test
	void anOuterFailureIsNotSwallowedBecauseANestedTransactionWasAccepted() throws Exception {
		final InternetAddress address = new InternetAddress("recipient@example.org");
		final SMTPSendFailedException reporting = new SMTPSendFailedException(".", 250, "250 queued", null,
				new Address[]{address}, null, null);
		final MessagingException failure = new MessagingException("wrapper failed", reporting);
		assertThat(AngusSubmissionResult.fromFailure(failure, new Address[]{address}, null).getFailure()).containsSame(failure);
	}

	@Test
	void fullyAcceptedAddressGroupsAreReportedInExpandedEnvelopeOrder() throws Exception {
		final Session session = Session.getInstance(new Properties());
		final InternetAddress first = new InternetAddress("first@example.org");
		final InternetAddress second = new InternetAddress("second@example.org");
		final SMTPAddressSucceededException reply = new SMTPAddressSucceededException(first, "RCPT TO:<first@example.org>", 250, "250 first");
		reply.setNextException(new SMTPAddressSucceededException(second, "RCPT TO:<second@example.org>", 250, "250 second"));
		final ProbeTransport transport = new ProbeTransport(session);
		transport.checkedFailure = new SMTPSendFailedException(".", 250, "250 queued", reply, new Address[]{first, second}, null, null);
		final PreparedMail mail = new PreparedMail(new MimeMessage(session),
				new Address[]{new InternetAddress("Group: first@example.org, second@example.org;")}, new DeliveryEnvelope(null, null), ContentRequirement.NORMAL);
		final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport, mail);
		assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
		assertThat(result.getFailure()).isEmpty();
		assertThat(result.getRecipientResults()).extracting(MailRecipientResult::getOriginalAddress)
				.containsExactly("first@example.org", "second@example.org");
		assertThat(result.getRecipientResults()).allSatisfy(recipient -> assertThat(recipient.getRcptResponse()).isPresent());
	}

	@Test
	void finalReplyFromExceptionRemainsAvailableWhenIdenticalToPreviousTransportState() throws Exception {
		final InternetAddress address = new InternetAddress("same@example.org");
		final SMTPSendFailedException failure = new SMTPSendFailedException(".", 450, "450 try later", null, null,
				new Address[]{address}, null);
		final MailTransportResult result = AngusSubmissionResult.fromFailure(failure, new Address[]{address}, null);
		assertThat(result.getSmtpResponse()).hasValueSatisfying(reply -> assertThat(reply.getReturnCode()).isEqualTo(450));
		assertThat(result.getRetryDisposition()).isEqualTo(MailRetryDisposition.SAFE_TO_RETRY_ALL);
		assertThat(result.getRecipientResults().get(0).getRcptResponse()).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void reportingPreferenceIsRestoredAfterSuccessAndBothKindsOfFailure(final boolean originalPreference) throws Exception {
		final Session session = Session.getInstance(new Properties());
		final InternetAddress address = new InternetAddress("recipient@example.org");
		final PreparedMail mail = new PreparedMail(new MimeMessage(session), new Address[]{address},
				new DeliveryEnvelope(null, null), ContentRequirement.NORMAL);
		final ProbeTransport transport = new ProbeTransport(session);
		transport.setReportSuccess(originalPreference);
		final AngusMailTransportAdapter adapter = new AngusMailTransportAdapter();

		adapter.sendMessage(transport, mail);
		assertThat(transport.getReportSuccess()).isEqualTo(originalPreference);
		transport.checkedFailure = new MessagingException("failed this attempt");
		assertThat(adapter.sendMessage(transport, mail).getFailure()).containsSame(transport.checkedFailure);
		assertThat(transport.getReportSuccess()).isEqualTo(originalPreference);
		transport.checkedFailure = null;
		transport.uncheckedFailure = new IllegalStateException("unchecked");
		assertThatThrownBy(() -> adapter.sendMessage(transport, mail)).isSameAs(transport.uncheckedFailure);
		assertThat(transport.getReportSuccess()).isEqualTo(originalPreference);
		assertThat(session.getProperties()).doesNotContainKey("mail.smtp.reportsuccess");
	}

	private static final class ProbeTransport extends SMTPTransport {
		private MessagingException checkedFailure;
		private RuntimeException uncheckedFailure;

		private ProbeTransport(final Session session) {
			super(session, new URLName("smtp", null, -1, null, null, null));
		}

		@Override
		public synchronized void sendMessage(final Message message, final Address[] addresses) throws MessagingException {
			assertThat(getReportSuccess()).isTrue();
			if (checkedFailure != null) {
				throw checkedFailure;
			}
			if (uncheckedFailure != null) {
				throw uncheckedFailure;
			}
		}
	}
}
