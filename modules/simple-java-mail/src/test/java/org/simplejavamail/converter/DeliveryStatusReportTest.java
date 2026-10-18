package org.simplejavamail.converter;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.DeliveryAction;
import org.simplejavamail.api.email.DeliveryStatusReport;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.RecipientDeliveryReport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryStatusReportTest {

	private static final String MESSAGE_FIELDS = "Reporting-MTA: dns; relay.example.test\r\nOriginal-Envelope-Id: order-123\r\n";
	private static final String FAILED_RECIPIENT = "Final-Recipient: rfc822; Reader@Example.test\r\nAction: failed\r\nStatus: 5.1.1\r\n";

	@Test
	void extractsMixedRecipientFactsThroughTheExistingEmailGateway() {
		final Email email = convert(report(MESSAGE_FIELDS + "\r\n"
				+ "Original-Recipient: rfc822; Before@Example.test\r\n" + FAILED_RECIPIENT
				+ "Diagnostic-Code: smtp; 550 no such mailbox\r\n"
				+ "Last-Attempt-Date: Fri, 16 Oct 2026 10:20:30 +0200\r\n\r\n"
				+ "Final-Recipient: rfc822; delivered@example.test\r\nAction: delivered\r\nStatus: 2.0.0\r\n\r\n"
				+ "Final-Recipient: rfc822; delayed@example.test\r\nAction: delayed\r\nStatus: 4.4.1\r\n"
				+ "Will-Retry-Until: Sat, 17 Oct 2026 10:20:30 +0200\r\n"));
		final DeliveryStatusReport status = email.getDeliveryStatusReport();

		assertThat(status).isNotNull();
		assertThat(status.getEnvelopeId()).isEqualTo("order-123");
		assertThat(status.getReportingServer().getType()).isEqualTo("dns");
		assertThat(status.getReportingServer().getName()).isEqualTo("relay.example.test");
		assertThat(status.getWarnings()).isEmpty();
		assertThat(status.getRecipients()).extracting(RecipientDeliveryReport::getAction)
				.containsExactly(DeliveryAction.FAILED, DeliveryAction.DELIVERED, DeliveryAction.DELAYED);
		final RecipientDeliveryReport recipient = status.getRecipients().get(0);
		assertThat(recipient.getOriginalRecipient().getAddress()).isEqualTo("Before@Example.test");
		assertThat(recipient.getOriginalRecipient().getType()).isEqualTo("rfc822");
		assertThat(recipient.getFinalRecipient().getAddress()).isEqualTo("Reader@Example.test");
		assertThat(recipient.getFinalRecipient().getType()).isEqualTo("rfc822");
		assertThat(recipient.getFinalRecipient().getMailbox().getAddress()).isEqualTo("Reader@Example.test");
		assertThat(recipient.getFinalRecipient().getMailbox().getName()).isNull();
		assertThat(recipient.getDiagnostic().getType()).isEqualTo("smtp");
		assertThat(recipient.getDiagnostic().getText()).isEqualTo("550 no such mailbox");
		assertThat(recipient.getDiagnostic().getSmtpResponse().getReturnCode()).isEqualTo(550);
		assertThat(recipient.getLastAttemptAt()).isEqualTo(Instant.parse("2026-10-16T08:20:30Z"));
		assertThat(status.getRecipients().get(2).getRetryUntil()).isEqualTo(Instant.parse("2026-10-17T08:20:30Z"));
		assertThat(email.getPlainText()).contains("Delivery feedback");
		assertThat(email.getAttachments()).hasSize(1);
		assertThat(status.toString()).isEqualTo("DeliveryStatusReport{recipients=3, delivered=1, delayed=1, failed=1, warnings=0}");
		assertThat(recipient.toString()).isEqualTo("RecipientDeliveryReport{action=FAILED, status=5.1.1}");
	}

	@Test
	void preservesForeignAddressTypesFoldedDiagnosticsAndDuplicateRecipients() {
		final DeliveryStatusReport status = convert(report(MESSAGE_FIELDS + "\r\n"
				+ "Final-Recipient: x400; /C=US/ADMD=Example/\r\nAction: relayed\r\nStatus: 2.0.0\r\n"
				+ "Diagnostic-Code: X400; passed onward;\r\n\tmore detail\r\n\r\n"
				+ FAILED_RECIPIENT + "\r\n" + FAILED_RECIPIENT)).getDeliveryStatusReport();

		assertThat(status.getRecipients()).hasSize(3);
		assertThat(status.getRecipients().get(0).getFinalRecipient().getType()).isEqualTo("x400");
		assertThat(status.getRecipients().get(0).getFinalRecipient().getAddress()).isEqualTo("/C=US/ADMD=Example/");
		assertThat(status.getRecipients().get(0).getFinalRecipient().getMailbox()).isNull();
		assertThat(status.getRecipients().get(0).getDiagnostic().getText()).isEqualTo("passed onward;\tmore detail");
		assertThat(status.getRecipients().get(0).getDiagnostic().getSmtpResponse()).isNull();
		assertThat(status.getRecipients().get(1)).isEqualTo(status.getRecipients().get(2));
	}

	@Test
	void readsInternationalizedTransferEncodedStatusWithoutSendingSessionConfiguration() {
		final String international = MESSAGE_FIELDS + "\r\nFinal-Recipient: utf-8; josé@例え.test\r\n"
				+ "Action: failed\r\nStatus: 5.1.1\r\nDiagnostic-Code: smtp; 550 boîte inconnue\r\n";
		final String message = report(international)
				.replace("message/delivery-status\r\n\r\n" + international,
						"message/global-delivery-status\r\nContent-Transfer-Encoding: base64\r\n\r\n"
								+ Base64.getMimeEncoder().encodeToString(international.getBytes(UTF_8)));
		final DeliveryStatusReport status = convert(message).getDeliveryStatusReport();

		assertThat(status.getWarnings()).isEmpty();
		assertThat(status.getRecipients().get(0).getFinalRecipient().getAddress()).isEqualTo("josé@例え.test");
		assertThat(status.getRecipients().get(0).getFinalRecipient().getMailbox().getAddress()).isEqualTo("josé@例え.test");
		assertThat(status.getRecipients().get(0).getDiagnostic().getText()).isEqualTo("550 boîte inconnue");
	}

	@Test
	void rawInternationalReportAndIdenticalDuplicateFieldsRemainUsable() {
		final DeliveryStatusReport status = convert(report(MESSAGE_FIELDS + "Original-Envelope-Id: order-123\r\n\r\n"
				+ "Final-Recipient: utf-8; josé@example.test\r\nAction: expanded\r\nStatus: 2.0.0\r\n")
				.replace("message/delivery-status", "message/global-delivery-status")).getDeliveryStatusReport();
		assertThat(status.getEnvelopeId()).isEqualTo("order-123");
		assertThat(status.getRecipients().get(0).getFinalRecipient().getAddress()).isEqualTo("josé@example.test");
		assertThat(status.getRecipients().get(0).getAction()).isEqualTo(DeliveryAction.EXPANDED);
		assertThat(status.getWarnings()).hasSize(1);
	}

	@Test
	void usesMailHeaderTokenizationForCommentsWithoutInventingStatusFacts() {
		final DeliveryStatusReport report = convert(report("Reporting-MTA: dns (a comment; with punctuation); relay.example.test\r\n\r\n"
				+ FAILED_RECIPIENT.replace("5.1.1", "5.1.1 (No such mailbox)"))).getDeliveryStatusReport();
		assertThat(report.getWarnings()).isEmpty();
		assertThat(report.getReportingServer().getName()).isEqualTo("relay.example.test");
		assertThat(report.getRecipients().get(0).getStatusCode().toString()).isEqualTo("5.1.1");
		final DeliveryStatusReport invalid = convert(report(MESSAGE_FIELDS + "\r\n"
				+ FAILED_RECIPIENT.replace("Action: failed", "Action: unknown").replace("5.1.1", "5.01.1"))).getDeliveryStatusReport();
		assertThat(invalid.getRecipients().get(0).getStatusCode().toString()).isEqualTo("5.1.1");
		assertThat(invalid.getWarnings()).hasSize(2);
	}

	@Test
	void exposesBoundedNumericStatusesWithoutClosingTheSubjectAndDetailRegistry() {
		for (final int statusClass : new int[]{2, 4, 5}) {
			final String recipientFields = FAILED_RECIPIENT.replace("5.1.1", statusClass + ".999.998")
					.replace("Action: failed", statusClass == 2 ? "Action: delivered" : "Action: failed");
			final var status = convert(report(MESSAGE_FIELDS + "\r\n" + recipientFields))
					.getDeliveryStatusReport().getRecipients().get(0).getStatusCode();
			assertThat(status.getStatusClass()).isEqualTo(statusClass);
			assertThat(status.getSubject()).isEqualTo(999);
			assertThat(status.getDetail()).isEqualTo(998);
			assertThat(status.isSuccess()).isEqualTo(statusClass == 2);
			assertThat(status.isTemporaryFailure()).isEqualTo(statusClass == 4);
			assertThat(status.isPermanentFailure()).isEqualTo(statusClass == 5);
			assertThat(status.toString()).isEqualTo(statusClass + ".999.998");
		}
		for (final String invalid : new String[]{"3.1.1", "5.1000.1", "5.1.1000", "5.123456789012345.1", "5.-1.1", "5. 1.1", "5.1.1 trailing"}) {
			final var recipient = convert(report(MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT.replace("5.1.1", invalid)))
					.getDeliveryStatusReport().getRecipients().get(0);
			assertThat(recipient.getStatusCode()).as(invalid).isNull();
			assertThat(recipient.getAction()).isEqualTo(DeliveryAction.FAILED);
		}
	}

	@Test
	void recoversZeroPaddedStatusesWithoutRewritingExactSourceBytes() throws Exception {
		final byte[] bytes = report(MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT.replace("5.1.1", "5.01.001")).getBytes(UTF_8);
		final Email email = SimpleJavaMail.fromDefaults().emailBuilder().startingFromExactEml(bytes)
				.withEnvelopeRecipients("archive@example.test").buildEmail();
		assertThat(email.getDeliveryStatusReport().getRecipients().get(0).getStatusCode().toString()).isEqualTo("5.1.1");
		assertThat(email.getDeliveryStatusReport().getWarnings()).singleElement().asString().contains("zero-padded");
		assertThat(EmailConverter.mimeMessageToEMLByteArray(EmailConverter.emailToMimeMessage(email))).containsExactly(bytes);
		final DeliveryStatusReport longPadding = convert(report(MESSAGE_FIELDS + "\r\n"
				+ FAILED_RECIPIENT.replace("5.1.1", "5.000000000000000001.000000000000000001"))).getDeliveryStatusReport();
		assertThat(longPadding.getRecipients().get(0).getStatusCode().toString()).isEqualTo("5.1.1");
		assertThat(longPadding.getWarnings()).singleElement().asString().contains("zero-padded");
	}

	@Test
	void recoversExplicitIsoDatesWithoutRollingOverImpossibleDatesOrGuessingTimezones() {
		final DeliveryStatusReport recovered = convert(report(MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT
				+ "Last-Attempt-Date: 2026-10-16T10:20:30+02:00\r\n")).getDeliveryStatusReport();
		assertThat(recovered.getRecipients().get(0).getLastAttemptAt()).isEqualTo(Instant.parse("2026-10-16T08:20:30Z"));
		assertThat(recovered.getWarnings()).singleElement().asString().contains("ISO date");
		for (final String invalid : new String[]{"2026-02-31T10:20:30+02:00", "2026-10-16T10:20:30", "Tue, 31 Feb 2026 10:20:30 +0200"}) {
			final DeliveryStatusReport report = convert(report(MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT
					+ "Last-Attempt-Date: " + invalid + "\r\n")).getDeliveryStatusReport();
			assertThat(report.getRecipients().get(0).getLastAttemptAt()).as(invalid).isNull();
			assertThat(report.getRecipients().get(0).getStatusCode().toString()).isEqualTo("5.1.1");
			assertThat(report.getWarnings()).singleElement().asString().contains("invalid Last-Attempt-Date");
		}
	}

	@Test
	void mailboxViewsKeepReportedNamesWithoutInventingOutgoingRecipientSettings() {
		final String address = "\"Reader, Example\" <Reader@Example.test>";
		final var recipient = convert(report(MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT.replace("Reader@Example.test", address)))
				.getDeliveryStatusReport().getRecipients().get(0).getFinalRecipient();
		assertThat(recipient.getAddress()).isEqualTo(address);
		assertThat(recipient.getMailbox().getAddress()).isEqualTo("Reader@Example.test");
		assertThat(recipient.getMailbox().getName()).isEqualTo("Reader, Example");
		assertThat(recipient.getMailbox().getType()).isNull();
		assertThat(recipient.getMailbox().getSmimeCertificate()).isNull();
		assertThat(recipient.getMailbox().getDeliveryStatusNotificationNotifyOptions()).isEmpty();
		assertThat(recipient.getMailbox()).isSameAs(recipient.getMailbox());
	}

	@Test
	void unusableAndUnfamiliarMailboxesRemainReportedAddressesRatherThanDisappearing() {
		for (final String address : new String[]{"not a mailbox", "one@example.test,two@example.test", "team:one@example.test;"}) {
			final var recipient = convert(report(MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT.replace("Reader@Example.test", address)))
					.getDeliveryStatusReport().getRecipients().get(0).getFinalRecipient();
			assertThat(recipient.getAddress()).isEqualTo(address);
			assertThat(recipient.getMailbox()).isNull();
		}
		final var report = convert(report("Reporting-MTA: x-local; opaque-server\r\n\r\n"
				+ FAILED_RECIPIENT.replace("rfc822", "x-future"))).getDeliveryStatusReport();
		assertThat(report.getReportingServer().getType()).isEqualTo("x-local");
		assertThat(report.getReportingServer().getName()).isEqualTo("opaque-server");
		assertThat(report.getRecipients().get(0).getFinalRecipient().getAddress()).isEqualTo("Reader@Example.test");
		assertThat(report.getRecipients().get(0).getFinalRecipient().getMailbox()).isNull();
	}

	@Test
	void smtpDiagnosticViewsUseOnlyRecognizableRepliesAndRetainTheirOriginalText() {
		final var diagnostic = convert(report(MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT
				+ "Diagnostic-Code: SMtP; 550 5.1.1 No such mailbox\r\n")).getDeliveryStatusReport().getRecipients().get(0).getDiagnostic();
		assertThat(diagnostic.getType()).isEqualTo("SMtP");
		assertThat(diagnostic.getText()).isEqualTo("550 5.1.1 No such mailbox");
		assertThat(diagnostic.getSmtpResponse().getReturnCode()).isEqualTo(550);
		assertThat(diagnostic.getSmtpResponse().getEnhancedStatusCode()).contains("5.1.1");
		assertThat(diagnostic.getSmtpResponse().getResponse()).isEqualTo(diagnostic.getText());
		for (final String supplied : new String[]{"smtp; remote said 550 unavailable", "smtp; 5500 invalid", "smtp; 600 invalid",
				"x400; 550 foreign reply"}) {
			final var preserved = convert(report(MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT + "Diagnostic-Code: " + supplied + "\r\n"))
					.getDeliveryStatusReport().getRecipients().get(0).getDiagnostic();
			assertThat(preserved.getSmtpResponse()).as(supplied).isNull();
			assertThat(preserved.getText()).isEqualTo(supplied.substring(supplied.indexOf(';') + 2));
		}
	}

	@Test
	void strongerFactsDoNotExposeIdentitiesOrProseInAutomaticRendering() {
		final var report = convert(report(MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT
				+ "Diagnostic-Code: smtp; 550 private explanation\r\n")).getDeliveryStatusReport();
		final var recipient = report.getRecipients().get(0);
		assertThat(report.toString() + recipient + recipient.getFinalRecipient() + report.getReportingServer() + recipient.getDiagnostic())
				.doesNotContain("Reader", "Example.test", "relay.example.test", "order-123", "private explanation");
	}

	@Test
	void smimeProtectionIsProcessedBeforeInterpretingTheOuterNotification() throws Exception {
		final org.simplejavamail.api.mailer.config.Pkcs12Config keys = org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore();
		final org.simplejavamail.api.email.config.SmimeSigningConfig signing =
				org.simplejavamail.api.email.config.SmimeSigningConfig.builder().pkcs12Config(keys).build();
		final MimeMessage clear = message(report(MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT.replace("5.1.1", "5.01.001")));
		final Email identity = SimpleJavaMail.fromDefaults().emailBuilder().startingBlank().from("sender@example.test").buildEmail();
		final MimeMessage signed = org.simplejavamail.internal.moduleloader.ModuleLoader.loadSmimeModule()
				.signMessageWithSmime(clear.getSession(), identity, clear, signing);
		final byte[] protectedBytes = EmailConverter.mimeMessageToEMLByteArray(signed);
		// Model received wire bytes, rather than a mutable, freshly generated provider object whose cached headers may still change.
		final MimeMessage received = new MimeMessage(clear.getSession(), new ByteArrayInputStream(protectedBytes));
		final Email parsed = SimpleJavaMail.fromDefaults().converter().mimeMessageToEmailBuilder(received).buildEmail();
		assertThat(parsed.getDeliveryStatusReport().getEnvelopeId()).isEqualTo("order-123");
		assertThat(parsed.getDeliveryStatusReport().getRecipients().get(0).getStatusCode().toString()).isEqualTo("5.1.1");
		assertThat(parsed.getDeliveryStatusReport().getWarnings()).singleElement().asString().contains("zero-padded");
		assertThat(parsed.getOriginalSmimeDetails().getVerificationStatus())
				.isEqualTo(org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.VALID);
		assertThat(parsed.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(protectedBytes);
		assertThat(EmailConverter.mimeMessageToEMLByteArray(received)).containsExactly(protectedBytes);
	}

	@Test
	void malformedRecognizedReportsRetainHonestFactsAndAggregateRepeatedWarnings() {
		final String invalidRecipient = "Final-Recipient: missing separator\r\nAction: delivered\r\nAction: failed\r\n"
				+ "Status: nonsense\r\nLast-Attempt-Date: not a date\r\n\r\n";
		final DeliveryStatusReport status = convert(report("Original-Envelope-Id: first\r\nOriginal-Envelope-Id: second\r\n\r\n"
				+ invalidRecipient.repeat(40))).getDeliveryStatusReport();

		assertThat(status).isNotNull();
		assertThat(status.getEnvelopeId()).isNull();
		assertThat(status.getReportingServer()).isNull();
		assertThat(status.getRecipients()).hasSize(40).allSatisfy(recipient -> {
			assertThat(recipient.getAction()).isEqualTo(DeliveryAction.UNKNOWN);
			assertThat(recipient.getStatusCode()).isNull();
			assertThat(recipient.getFinalRecipient()).isNull();
			assertThat(recipient.getLastAttemptAt()).isNull();
		});
		assertThat(status.getWarnings()).hasSize(6);
		assertThat(status.getWarnings()).allSatisfy(warning -> assertThat(warning).doesNotContain("nonsense", "first", "second"));
	}

	@Test
	void missingStatusPartsAndUnknownActionsRemainRecognized() {
		final Email incomplete = convert("From: postmaster@example.test\r\nMIME-Version: 1.0\r\n"
				+ "Content-Type: multipart/report; report-type=delivery-status; boundary=b\r\n\r\n"
				+ "--b\r\nContent-Type: text/plain\r\n\r\nIncomplete notification\r\n--b--\r\n");
		assertThat(incomplete.getDeliveryStatusReport().getWarnings()).hasSize(1);
		assertThat(incomplete.getDeliveryStatusReport().getRecipients()).isEmpty();
		final DeliveryStatusReport unknown = convert(report(MESSAGE_FIELDS + "\r\n"
				+ FAILED_RECIPIENT.replace("Action: failed", "Action: some-future-action"))).getDeliveryStatusReport();
		assertThat(unknown.getRecipients().get(0).getAction()).isEqualTo(DeliveryAction.UNKNOWN);
	}

	@Test
	void normalMailAndAttachedOrForwardedReportsDoNotBecomeOuterReportMetadata() throws Exception {
		final Email notification = convert(report(MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT));
		assertThat(convert("From: sender@example.test\r\n\r\nNormal mail").getDeliveryStatusReport()).isNull();
		assertThat(SimpleJavaMail.fromDefaults().emailBuilder().replyingTo(notification).buildEmail().getDeliveryStatusReport()).isNull();
		final Email forwarded = SimpleJavaMail.fromDefaults().emailBuilder().forwarding(
				message(report(MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT))).from("sender@example.test")
				.withRecipients(new org.simplejavamail.api.email.Recipient(null, "reader@example.test", jakarta.mail.Message.RecipientType.TO, null)).buildEmail();
		assertThat(forwarded.getDeliveryStatusReport()).isNull();
		assertThat(EmailConverter.mimeMessageToEmail(EmailConverter.emailToMimeMessage(forwarded)).getDeliveryStatusReport()).isNull();

		final String nested = "From: sender@example.test\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=outer\r\n\r\n"
				+ "--outer\r\nContent-Type: message/rfc822\r\nContent-Disposition: attachment\r\n\r\n"
				+ report(MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT) + "\r\n--outer--\r\n";
		assertThat(convert(nested).getDeliveryStatusReport()).isNull();
		final String nestedContainer = nested.replace("Content-Type: message/rfc822\r\nContent-Disposition: attachment\r\n\r\n", "");
		assertThat(convert(nestedContainer).getDeliveryStatusReport()).isNull();
	}

	@Test
	void recoversTheOnlyDirectStatusPartWhenItsPositionIsNonstandard() throws Exception {
		for (final int position : new int[]{0, 2}) {
			final MimeBodyPart text = new MimeBodyPart();
			text.setText("Readable explanation");
			final MimeBodyPart extra = new MimeBodyPart();
			extra.setText("Additional explanation");
			final MimeBodyPart status = part(source("message/delivery-status", MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT, new AtomicInteger()));
			final MimeMessage message = position == 0 ? notificationWithParts(status, text, extra) : notificationWithParts(text, extra, status);
			final Email notification = SimpleJavaMail.fromDefaults().converter().mimeMessageToEmailBuilder(message).buildEmail();
			assertThat(notification.getDeliveryStatusReport().getEnvelopeId()).isEqualTo("order-123");
			assertThat(notification.getDeliveryStatusReport().getRecipients()).hasSize(1);
			assertThat(notification.getDeliveryStatusReport().getWarnings()).singleElement().asString().contains("out of order");
			assertThat(notification.getPlainText()).contains("Readable explanation", "Additional explanation");
			assertThat(notification.getAttachments()).hasSize(1);
		}
	}

	@Test
	void doesNotChooseBetweenMultipleStatusPartsEvenWhenOneHasTheExpectedPosition() throws Exception {
		final MimeBodyPart text = new MimeBodyPart();
		text.setText("Readable explanation");
		final MimeBodyPart first = part(source("message/delivery-status", MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT, new AtomicInteger()));
		final MimeBodyPart second = part(source("message/delivery-status", MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT, new AtomicInteger()));
		final Email notification = SimpleJavaMail.fromDefaults().converter().mimeMessageToEmailBuilder(notificationWithParts(text, first, second)).buildEmail();
		assertThat(notification.getDeliveryStatusReport().getRecipients()).isEmpty();
		assertThat(notification.getDeliveryStatusReport().getEnvelopeId()).isNull();
		assertThat(notification.getDeliveryStatusReport().getWarnings()).singleElement().asString().contains("multiple delivery-status parts");
		assertThat(notification.getPlainText()).contains("Readable explanation");
		assertThat(notification.getAttachments()).hasSize(2);
	}

	@Test
	void copiesAndSerializationRetainSourceFactsWithoutCreatingOutboundReportMime() throws Exception {
		final Email notification = convert(report(MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT + "Diagnostic-Code: smtp; 550 5.1.1 unknown\r\n"));
		final Email copied = SimpleJavaMail.fromDefaults().emailBuilder().copying(notification).buildEmail();
		assertThat(copied).isEqualTo(notification);
		assertThat(copied.getDeliveryStatusReport()).isSameAs(notification.getDeliveryStatusReport());
		final ByteArrayOutputStream serialized = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(serialized)) {
			output.writeObject(notification);
		}
		final Email restored;
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serialized.toByteArray()))) {
			restored = (Email) input.readObject();
		}
		assertThat(restored.getDeliveryStatusReport()).isEqualTo(notification.getDeliveryStatusReport());
		assertThat(restored.getDeliveryStatusReport().hashCode()).isEqualTo(notification.getDeliveryStatusReport().hashCode());
		final var restoredRecipient = restored.getDeliveryStatusReport().getRecipients().get(0);
		assertThat(restoredRecipient.getFinalRecipient().getMailbox().getAddress()).isEqualTo("Reader@Example.test");
		assertThat(restoredRecipient.getStatusCode().isPermanentFailure()).isTrue();
		assertThat(restoredRecipient.getDiagnostic().getSmtpResponse().getReturnCode()).isEqualTo(550);
		assertThatThrownBy(() -> restored.getDeliveryStatusReport().getRecipients().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> restored.getDeliveryStatusReport().getWarnings().add("changed")).isInstanceOf(UnsupportedOperationException.class);
		assertThat(EmailConverter.emailToMimeMessage(copied).isMimeType("multipart/report")).isFalse();
	}

	@Test
	void governanceRetainsTheSuppliedSnapshotButNeverInheritsItFromTemplates() {
		final Email notification = convert(report(MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT));
		final Email ordinary = SimpleJavaMail.fromDefaults().emailBuilder().startingBlank().withPlainText("Ordinary content").buildEmail();
		final org.simplejavamail.mailer.internal.EmailGovernanceImpl governance =
				new org.simplejavamail.mailer.internal.EmailGovernanceImpl(null, notification, notification, null);
		assertThat(governance.produceEmailApplyingDefaultsAndOverrides(notification).getDeliveryStatusReport()).isSameAs(notification.getDeliveryStatusReport());
		assertThat(governance.produceEmailApplyingDefaultsAndOverrides(ordinary).getDeliveryStatusReport()).isNull();
		assertThat(governance.produceEmailApplyingDefaultsAndOverrides(null).getDeliveryStatusReport()).isNull();
	}

	@Test
	void exactEmlInspectionDoesNotChangeAuthoritativeBytes() throws Exception {
		final byte[] bytes = report(MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT).getBytes(UTF_8);
		final org.simplejavamail.api.email.ExactEmailBuilder builder = SimpleJavaMail.fromDefaults().emailBuilder()
				.startingFromExactEml(bytes).withEnvelopeRecipients("archive@example.test");
		assertThat(builder.getDeliveryStatusReport().getEnvelopeId()).isEqualTo("order-123");
		final Email exact = builder.buildEmail();
		assertThat(exact.getDeliveryStatusReport().getEnvelopeId()).isEqualTo("order-123");
		assertThat(EmailConverter.mimeMessageToEMLByteArray(EmailConverter.emailToMimeMessage(exact))).containsExactly(bytes);
	}

	@Test
	void reusesCachedStatusBytesAndDoesNotReadUnrelatedLazyAttachments() throws Exception {
		final AtomicInteger statusReads = new AtomicInteger();
		final AtomicInteger unrelatedReads = new AtomicInteger();
		final MimeBodyPart text = new MimeBodyPart();
		text.setText("Delivery feedback");
		final MimeBodyPart status = part(source("message/delivery-status", MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT, statusReads));
		final MimeBodyPart unrelated = part(source("application/octet-stream", "large unrelated attachment", unrelatedReads));
		final MimeMultipart parts = new MimeMultipart("report; report-type=delivery-status");
		parts.addBodyPart(text);
		parts.addBodyPart(status);
		parts.addBodyPart(unrelated);
		final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		message.setContent(parts);
		message.saveChanges();
		statusReads.set(0);
		unrelatedReads.set(0);
		// The shared MIME pass must not consume unrelated lazy attachments. Optional protection preprocessing has its own established reads.
		final org.simplejavamail.converter.internal.mimemessage.MimeMessageParser.ParsedMimeMessageComponents parsed =
				org.simplejavamail.converter.internal.mimemessage.MimeMessageParser.parseMimeMessage(message, false);
		assertThat(parsed.getDeliveryStatusReport()).isNotNull();
		assertThat(statusReads).hasValue(1);
		assertThat(unrelatedReads).hasValue(0);
	}

	@Test
	void optionalStatusReadingFailureKeepsTheEmailButEagerContentReadingStillFailsWithItsCause() throws Exception {
		final IOException failure = new IOException("fixture read failure");
		final DataSource broken = new ByteArrayDataSource(new byte[0], "message/delivery-status") {
			@Override public InputStream getInputStream() throws IOException { throw failure; }
		};
		final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		message.setDataHandler(new DataHandler(broken));
		message.setSubject("Still readable");
		final Email recovered = SimpleJavaMail.fromDefaults().converter().mimeMessageToEmailBuilder(message, null, null, false).buildEmail();
		assertThat(recovered.getSubject()).isEqualTo("Still readable");
		assertThat(recovered.getDeliveryStatusReport().getRecipients()).isEmpty();
		assertThat(recovered.getDeliveryStatusReport().getWarnings()).anyMatch(warning -> warning.contains("may be incomplete"));
		assertThat(recovered.getDeliveryStatusReport().getWarnings()).allSatisfy(warning -> assertThat(warning).doesNotContain("fixture read failure"));
		assertThatThrownBy(() -> SimpleJavaMail.fromDefaults().converter().mimeMessageToEmailBuilder(message, null, null, true))
				.hasRootCause(failure);
	}

	@Test
	void interruptedStatusReadingRetainsCompletedBlocksButNotTheInterruptedRecipient() throws Exception {
		final AtomicInteger closes = new AtomicInteger();
		final String readablePrefix = MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT + "\r\nFinal-Recipient: rfc822; second@example.test\r\nAction: fail";
		final DataSource interrupted = new ByteArrayDataSource(new byte[0], "message/delivery-status") {
			@Override
			public InputStream getInputStream() {
				final ByteArrayInputStream prefix = new ByteArrayInputStream(readablePrefix.getBytes(UTF_8));
				return new InputStream() {
					@Override
					public int read() throws IOException {
						final int next = prefix.read();
						if (next == -1) {
							throw new IOException("fixture interrupted read");
						}
						return next;
					}

					@Override
					public void close() {
						closes.incrementAndGet();
					}
				};
			}
		};
		final MimeBodyPart text = new MimeBodyPart();
		text.setText("Readable explanation");
		final MimeMessage notification = notificationWithParts(text, part(interrupted));
		// Saving a constructed message can inspect its DataSource too; count resource cleanup for this parsing attempt only.
		closes.set(0);
		final org.simplejavamail.converter.internal.mimemessage.MimeMessageParser.ParsedMimeMessageComponents parsed =
				org.simplejavamail.converter.internal.mimemessage.MimeMessageParser.parseMimeMessage(notification, false);
		assertThat(parsed.getDeliveryStatusReport().getEnvelopeId()).isEqualTo("order-123");
		assertThat(parsed.getDeliveryStatusReport().getReportingServer().getName()).isEqualTo("relay.example.test");
		assertThat(parsed.getDeliveryStatusReport().getRecipients()).hasSize(1);
		assertThat(parsed.getDeliveryStatusReport().getRecipients().get(0).getAction()).isEqualTo(DeliveryAction.FAILED);
		assertThat(parsed.getDeliveryStatusReport().getWarnings()).anyMatch(warning -> warning.contains("may be incomplete"));
		assertThat(parsed.getPlainContent()).contains("Readable explanation");
		assertThat(closes).hasValue(1);
	}

	@Test
	void optionalInterpretationDoesNotHideUnexpectedRuntimeFailures() throws Exception {
		final IllegalStateException failure = new IllegalStateException("fixture application failure");
		final DataSource broken = new ByteArrayDataSource(new byte[0], "message/delivery-status") {
			@Override
			public InputStream getInputStream() {
				throw failure;
			}
		};
		final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		message.setDataHandler(new DataHandler(broken));
		assertThatThrownBy(() -> SimpleJavaMail.fromDefaults().converter().mimeMessageToEmailBuilder(message, null, null, false)).isSameAs(failure);
	}

	@Test
	void optionalReadRecoveryDoesNotHideThreadInterruption() throws Exception {
		final IOException failure = new IOException("fixture interrupted operation");
		final DataSource broken = new ByteArrayDataSource(new byte[0], "message/delivery-status") {
			@Override
			public InputStream getInputStream() throws IOException {
				throw failure;
			}
		};
		final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		message.setDataHandler(new DataHandler(broken));
		final org.simplejavamail.api.converter.ConfiguredEmailConverter converter = SimpleJavaMail.fromDefaults().converter();
		Thread.currentThread().interrupt();
		try {
			assertThatThrownBy(() -> converter.mimeMessageToEmailBuilder(message, null, null, false)).hasRootCause(failure);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void closeFailurePreservesAllReadFactsAndAddsACaution() throws Exception {
		final DataSource closeFailure = new ByteArrayDataSource(new byte[0], "message/delivery-status") {
			@Override
			public InputStream getInputStream() {
				return new ByteArrayInputStream((MESSAGE_FIELDS + "\r\n" + FAILED_RECIPIENT).getBytes(UTF_8)) {
					@Override
					public void close() throws IOException {
						throw new IOException("fixture close failure");
					}
				};
			}
		};
		final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		message.setDataHandler(new DataHandler(closeFailure));
		final DeliveryStatusReport report = SimpleJavaMail.fromDefaults().converter().mimeMessageToEmailBuilder(message, null, null, false)
				.buildEmail().getDeliveryStatusReport();
		assertThat(report.getEnvelopeId()).isEqualTo("order-123");
		assertThat(report.getRecipients()).hasSize(1);
		assertThat(report.getRecipients().get(0).getStatusCode().toString()).isEqualTo("5.1.1");
		assertThat(report.getWarnings()).singleElement().asString().contains("may be incomplete");
	}

	private static MimeMessage notificationWithParts(final MimeBodyPart... parts) throws Exception {
		final MimeMultipart multipart = new MimeMultipart("report; report-type=delivery-status");
		for (final MimeBodyPart part : parts) {
			multipart.addBodyPart(part);
		}
		final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		message.setContent(multipart);
		message.saveChanges();
		return message;
	}

	private static MimeBodyPart part(final DataSource source) throws Exception {
		final MimeBodyPart part = new MimeBodyPart();
		part.setDataHandler(new DataHandler(source));
		return part;
	}

	private static DataSource source(final String type, final String body, final AtomicInteger reads) {
		return new ByteArrayDataSource(body.getBytes(UTF_8), type) {
			@Override public InputStream getInputStream() throws IOException {
				reads.incrementAndGet();
				return super.getInputStream();
			}
		};
	}

	private static Email convert(final String eml) {
		return SimpleJavaMail.fromDefaults().converter().emlToEmailBuilder(new ByteArrayInputStream(eml.getBytes(UTF_8))).buildEmail();
	}

	private static MimeMessage message(final String eml) throws Exception {
		return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml.getBytes(UTF_8)));
	}

	private static String report(final String status) {
		return "From: postmaster@example.test\r\nTo: sender@example.test\r\nSubject: Delivery report\r\nMIME-Version: 1.0\r\n"
				+ "Content-Type: multipart/report; report-type=delivery-status; boundary=report\r\n\r\n"
				+ "--report\r\nContent-Type: text/plain\r\n\r\nDelivery feedback\r\n"
				+ "--report\r\nContent-Type: message/delivery-status\r\n\r\n" + status + "\r\n--report--\r\n";
	}
}
