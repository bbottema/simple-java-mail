package org.simplejavamail.email.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.converter.EmailConverter;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExactEmailBuilderTest {

	private static final byte[] EXACT_EML = ("Message-ID: <exact-713@simplejavamail.org>\r\n"
			+ "From: visible-sender@example.org\r\n"
			+ "Return-Path: <historical-envelope-sender@example.org>\r\n"
			+ "To: visible@example.org\r\n"
			+ "Bcc: visible-hidden@example.org\r\n"
			+ "Subject: Exact EML\r\n"
			+ "X-Folded: first\r\n"
			+ " second\r\n"
			+ "Content-Length: 6\r\n"
			+ "Content-Type: text/plain; charset=us-ascii\r\n"
			+ "\r\n"
			+ "body\r\n").getBytes(StandardCharsets.US_ASCII);

	private SimpleJavaMail simpleJavaMail;

	@BeforeEach
	void setUp() {
		simpleJavaMail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
	}

	@Test
	void exactEmlRemainsAuthoritativeWhileNormalEmailGettersRemainAvailable() {
		final Email email = exactEmail(EXACT_EML);

		assertThat(email.getId()).isEqualTo("<exact-713@simplejavamail.org>");
		assertThat(email.getSubject()).isEqualTo("Exact EML");
		assertThat(email.getFromRecipient()).extracting(Recipient::getAddress)
				.isEqualTo("visible-sender@example.org");
		assertThat(email.getRecipients()).extracting(Recipient::getAddress)
				.containsExactly("visible@example.org", "visible-hidden@example.org");
		assertThat(email.getOverrideReceivers()).extracting(Recipient::getAddress)
				.containsExactly("first@example.org", "second@example.org");
		assertThat(email.getBounceToRecipient()).isNull();
		assertThat(EmailConverter.emailToEMLByteArray(email)).containsExactly(EXACT_EML);
		assertThat(((InternalEmail) email).isExactEml()).isTrue();
	}

	@Test
	void inputBytesAreCopiedBeforeTheBuilderIsReturned() {
		final byte[] mutableInput = EXACT_EML.clone();
		final ExactEmailBuilder builder = simpleJavaMail.emailBuilder()
				.startingFromExactEml(mutableInput)
				.withEnvelopeRecipients("recipient@example.org");

		Arrays.fill(mutableInput, (byte) 'x');

		assertThat(EmailConverter.emailToEMLByteArray(builder.buildEmail())).containsExactly(EXACT_EML);
	}

	@Test
	void inputStreamIsConsumedImmediatelyWithoutBeingClosed() {
		final OwnershipTrackingInputStream input = new OwnershipTrackingInputStream(EXACT_EML);
		final ExactEmailBuilder builder = simpleJavaMail.emailBuilder()
				.startingFromExactEml(input);

		assertThat(input.available()).isZero();
		assertThat(input.closed).isFalse();
		assertThat(EmailConverter.emailToEMLByteArray(
				builder.withEnvelopeRecipients("recipient@example.org").buildEmail())).containsExactly(EXACT_EML);
	}

	@Test
	void envelopeConfigurationAppendsRecipientsAndReplacesSender() {
		final Email email = simpleJavaMail.emailBuilder().startingFromExactEml(EXACT_EML)
				.withEnvelopeRecipients("First <first@example.org>")
				.withEnvelopeRecipients(Arrays.asList("second@example.org", "first@example.org", "Third <third@example.org>"))
				.withEnvelopeSender("old-bounce@example.org")
				.withEnvelopeSender("New Bounce <new-bounce@example.org>")
				.withDeliveryStatusNotificationNotifyOptions("failure,delay")
				.withDeliveryStatusNotificationReturnOption("headers_only")
				.buildEmail();

		assertThat(email.getOverrideReceivers()).extracting(Recipient::getAddress)
				.containsExactly("first@example.org", "second@example.org", "first@example.org", "third@example.org");
		assertThat(email.getBounceToRecipient().getAddress()).isEqualTo("new-bounce@example.org");
		assertThat(email.getDeliveryStatusNotification().getNotifyOptions())
				.containsExactly(DeliveryStatusNotification.NotifyOption.FAILURE, DeliveryStatusNotification.NotifyOption.DELAY);
		assertThat(email.getDeliveryStatusNotification().getReturnOption())
				.isEqualTo(DeliveryStatusNotification.ReturnOption.HEADERS_ONLY);
	}

	@Test
	void exactEmlRejectsMissingInputBeforeReturningABuilder() {
		assertThatThrownBy(() -> simpleJavaMail.emailBuilder().startingFromExactEml((byte[]) null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("emlBytes");
		assertThatThrownBy(() -> simpleJavaMail.emailBuilder().startingFromExactEml(new byte[0]))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("emlBytes");
		assertThatThrownBy(() -> simpleJavaMail.emailBuilder().startingFromExactEml((InputStream) null))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("emlInputStream");
	}

	@Test
	void exactEmlRequiresExplicitSingleMailboxEnvelopeRecipients() {
		assertThatThrownBy(() -> simpleJavaMail.emailBuilder().startingFromExactEml(EXACT_EML).buildEmail())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("envelopeRecipients");
		assertThatThrownBy(() -> simpleJavaMail.emailBuilder().startingFromExactEml(EXACT_EML)
				.withEnvelopeRecipients("one@example.org, two@example.org"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("exactly one mailbox");
		assertThatThrownBy(() -> simpleJavaMail.emailBuilder().startingFromExactEml(EXACT_EML)
				.withEnvelopeRecipients("recipient@example.org")
				.withEnvelopeSender("one@example.org, two@example.org"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("exactly one mailbox");
	}

	@Test
	void exactEmlRequiresCanonicalCrlfAndTerminalCrlf() {
		assertThatThrownBy(() -> simpleJavaMail.emailBuilder().startingFromExactEml(
				"Subject: bare LF\n\nbody\n".getBytes(StandardCharsets.US_ASCII)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("bare LF");
		assertThatThrownBy(() -> simpleJavaMail.emailBuilder().startingFromExactEml(
				"Subject: no terminal CRLF\r\n\r\nbody".getBytes(StandardCharsets.US_ASCII)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("end with CRLF");
	}

	@Test
	void exactEmlMustBeParseableBeforeTheBuilderIsReturned() {
		final byte[] malformedMultipart = ("Content-Type: multipart/mixed; boundary=missing\r\n"
				+ "\r\n"
				+ "body without the declared MIME boundary\r\n").getBytes(StandardCharsets.US_ASCII);

		assertThatThrownBy(() -> simpleJavaMail.emailBuilder().startingFromExactEml(malformedMultipart))
				.hasMessage("Unable to parse exact EML");
	}

	@Test
	void absentMessageIdRemainsAbsent() throws Exception {
		final byte[] emlWithoutMessageId = "Subject: no identifier\r\n\r\nbody\r\n"
				.getBytes(StandardCharsets.US_ASCII);
		final Email email = exactEmail(emlWithoutMessageId);

		assertThat(email.getId()).isNull();
		assertThat(EmailConverter.emailToMimeMessage(email).getMessageID()).isNull();
		assertThat(EmailConverter.emailToEMLByteArray(email)).containsExactly(emlWithoutMessageId);
	}

	@Test
	void copyingAnExactEmailIntentionallyCreatesAComposedEmail() {
		final Email exactEmail = exactEmail(EXACT_EML);
		final Email copiedEmail = simpleJavaMail.emailBuilder().copying(exactEmail).buildEmail();

		assertThat(((InternalEmail) copiedEmail).isExactEml()).isFalse();
		assertThat(EmailConverter.emailToEMLByteArray(copiedEmail)).isNotEqualTo(EXACT_EML);
	}

	@Test
	void exactSourceParticipatesInSerializationAndEquality() throws Exception {
		final Email first = exactEmail(EXACT_EML);
		final Email same = exactEmail(EXACT_EML);
		final byte[] differentlyFolded = new String(EXACT_EML, StandardCharsets.US_ASCII)
				.replace("X-Folded: first\r\n second", "X-Folded: first second")
				.getBytes(StandardCharsets.US_ASCII);
		final Email different = exactEmail(differentlyFolded);

		assertThat(first).isEqualTo(same).isNotEqualTo(different);
		assertThat(roundTrip(first)).isEqualTo(first);
		assertThat(EmailConverter.emailToEMLByteArray(roundTrip(first))).containsExactly(EXACT_EML);
	}

	private Email exactEmail(final byte[] emlBytes) {
		return simpleJavaMail.emailBuilder().startingFromExactEml(emlBytes)
				.withEnvelopeRecipients("first@example.org", "second@example.org")
				.buildEmail();
	}

	private static Email roundTrip(final Email email) throws Exception {
		final ByteArrayOutputStream serialized = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(serialized)) {
			output.writeObject(email);
		}
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serialized.toByteArray()))) {
			return (Email) input.readObject();
		}
	}

	private static final class OwnershipTrackingInputStream extends ByteArrayInputStream {
		private boolean closed;

		private OwnershipTrackingInputStream(final byte[] bytes) {
			super(bytes);
		}

		@Override
		public void close() throws IOException {
			closed = true;
			super.close();
		}
	}
}
