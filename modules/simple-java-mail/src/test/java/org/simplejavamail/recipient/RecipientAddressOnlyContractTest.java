package org.simplejavamail.recipient;

import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.IRecipientBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.mailer.MailInvalidAddressException;
import testutil.ConfigLoaderTestHelper;

import java.security.cert.X509Certificate;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.TO;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;

class RecipientAddressOnlyContractTest {

	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

	@Test
	void stringAndTypedAddressOnlyOverloadsKeepTheConfiguredName() throws Exception {
		final String source = "Embedded <alice@example.org>";
		final Recipient stringRecipient = mail.recipientBuilder().withName("Chosen").withAddressOnlyFrom(source).build();
		final Recipient typedRecipient = mail.recipientBuilder().withName("Chosen").withAddressOnlyFrom(new InternetAddress(source)).build();
		assertThat(stringRecipient).isEqualTo(typedRecipient);
		assertThat(stringRecipient.getName()).isEqualTo("Chosen");
		assertThat(stringRecipient.getAddress()).isEqualTo("alice@example.org");
	}

	@Test
	void addressOnlyDoesNotIntroduceAnEmbeddedNameWhenNoneWasConfigured() {
		final Recipient recipient = mail.recipientBuilder().withAddressOnlyFrom("Embedded <alice@example.org>").build();
		assertThat(recipient.getName()).isNull();
		assertThat(recipient.getAddress()).isEqualTo("alice@example.org");
	}

	@Test
	void malformedAddressStillUsesTheLenientFallbackWithoutChangingTheName() {
		final String malformed = "broken <address";
		final Recipient recipient = mail.recipientBuilder().withName("Chosen").withAddressOnlyFrom(malformed).build();
		assertThat(recipient.getName()).isEqualTo("Chosen");
		assertThat(recipient.getAddress()).isEqualTo(malformed);
	}

	@ParameterizedTest
	@ValueSource(strings = {"(comment only)", "   ", " \t "})
	void addressOnlyRetainsTheOriginalInputAndRecipientMetadataWhenNoMailboxIsParsed(final String source) throws Exception {
		assertThat(InternetAddress.parse(source, false)).isEmpty();
		final X509Certificate certificate = mock(X509Certificate.class);
		final Recipient recipient = mail.recipientBuilder()
				.withName("Chosen")
				.withType(BCC)
				.withSmimeCertificate(certificate)
				.withDeliveryStatusNotificationNotifyOptions(FAILURE)
				.withAddressOnlyFrom(source)
				.build();

		assertThat(recipient.getAddress()).isEqualTo(source);
		assertThat(recipient.getName()).isEqualTo("Chosen");
		assertThat(recipient.getType()).isEqualTo(BCC);
		assertThat(recipient.getSmimeCertificate()).isSameAs(certificate);
		assertThat(recipient.getDeliveryStatusNotificationNotifyOptions()).containsExactly(FAILURE);
	}

	@ParameterizedTest
	@ValueSource(strings = {"(comment only)", "   ", " \t "})
	void nameAndAddressHelpersRetainTheSuppliedNameWhenNoMailboxIsParsed(final String source) throws Exception {
		assertThat(InternetAddress.parse(source, false)).isEmpty();
		final Recipient defaultName = mail.recipientBuilder().withAddressAndNameOrDefault(source, "Default").build();
		final Recipient fixedName = mail.recipientBuilder().withAddressAndFixedNameOrProvided(source, "Fixed").build();
		final Recipient noDefaultName = mail.recipientBuilder().withAddressAndNameOrDefault(source, null).build();
		final Recipient noFixedName = mail.recipientBuilder().withAddressAndFixedNameOrProvided(source, null).build();

		assertThat(defaultName.getAddress()).isEqualTo(source);
		assertThat(defaultName.getName()).isEqualTo("Default");
		assertThat(fixedName.getAddress()).isEqualTo(source);
		assertThat(fixedName.getName()).isEqualTo("Fixed");
		assertThat(noDefaultName.getAddress()).isEqualTo(source);
		assertThat(noDefaultName.getName()).isNull();
		assertThat(noFixedName.getAddress()).isEqualTo(source);
		assertThat(noFixedName.getName()).isNull();
	}

	@Test
	void malformedNameAndAddressInputKeepsTheExistingSuppliedNameFallback() {
		final String source = "broken <address";
		final Recipient defaultName = mail.recipientBuilder().withAddressAndNameOrDefault(source, "Default").build();
		final Recipient fixedName = mail.recipientBuilder().withAddressAndFixedNameOrProvided(source, "Fixed").build();

		assertThat(defaultName.getAddress()).isEqualTo(source);
		assertThat(defaultName.getName()).isEqualTo("Default");
		assertThat(fixedName.getAddress()).isEqualTo(source);
		assertThat(fixedName.getName()).isEqualTo("Fixed");
	}

	@Test
    void addressHelpersRetainPluralSourceWithoutSelectingTheFirstMailbox() {
		final String source = "Embedded <alice@example.org>, Bob <bob@example.org>";
		final Recipient addressOnly = mail.recipientBuilder().withName("Chosen").withAddressOnlyFrom(source).build();
		final Recipient defaultName = mail.recipientBuilder().withAddressAndNameOrDefault(source, "Default").build();
		final Recipient fixedName = mail.recipientBuilder().withAddressAndFixedNameOrProvided(source, "Fixed").build();
		final Recipient noFixedName = mail.recipientBuilder().withAddressAndFixedNameOrProvided(source, null).build();

		assertThat(addressOnly.getAddress()).isEqualTo(source);
		assertThat(addressOnly.getName()).isEqualTo("Chosen");
		assertThat(defaultName.getAddress()).isEqualTo(source);
		assertThat(defaultName.getName()).isEqualTo("Default");
		assertThat(fixedName.getAddress()).isEqualTo(source);
		assertThat(fixedName.getName()).isEqualTo("Fixed");
		assertThat(noFixedName.getAddress()).isEqualTo(source);
		assertThat(noFixedName.getName()).isNull();
	}

	@ParameterizedTest
	@NullAndEmptySource
	void addressHelpersStillRejectMissingInput(final String source) {
		final IRecipientBuilder builder = mail.recipientBuilder();
		assertThatThrownBy(() -> builder.withAddressOnlyFrom(source))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("address is required");
		assertThatThrownBy(() -> builder.withAddressAndNameOrDefault(source, "Default"))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("address is required");
		assertThatThrownBy(() -> builder.withAddressAndFixedNameOrProvided(source, "Fixed"))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("address is required");
	}

	@ParameterizedTest
	@ValueSource(strings = {"(comment only)", "   ", " \t "})
	void recoveredInvalidAddressesAreRejectedBeforeTheSendingCallback(final String source) throws Exception {
		final Recipient recipient = mail.recipientBuilder().withType(TO).withAddressOnlyFrom(source).build();
		final Email email = mail.emailBuilder().startingBlank()
				.from("sender@example.org")
				.withRecipients(recipient)
				.withPlainText("No content should reach the sending callback")
				.buildEmail();
		final CustomMailer customMailer = mock(CustomMailer.class);

		try (Mailer mailer = mail.mailerBuilder().withSMTPServer("unreachable.example.invalid", 25).withCustomMailer(customMailer).buildMailer()) {
			assertThatThrownBy(() -> mailer.sync().sendMail(email))
					.isInstanceOf(MailInvalidAddressException.class)
					.hasMessage("Invalid TO address: " + source);
		}
		verifyNoInteractions(customMailer);
	}

	@ParameterizedTest
	@ValueSource(strings = {"(comment only)", "   ", " \t "})
	void emailAddressHelpersRetainSourcesWithoutAMailbox(final String source) {
		final Email email = validEmailBuilder()
				.from("Sender", source)
				.withReplyTo("Reply", source)
				.withBounceTo("Bounce", source)
				.withDispositionNotificationTo("Read notification", source)
				.withReturnReceiptTo("Return receipt", source)
				.buildEmail();

		assertThat(email.getFromRecipient().getAddress()).isEqualTo(source);
		assertThat(email.getFromRecipient().getName()).isEqualTo("Sender");
		assertThat(email.getReplyToRecipients()).singleElement().satisfies(recipient -> {
			assertThat(recipient.getAddress()).isEqualTo(source);
			assertThat(recipient.getName()).isEqualTo("Reply");
		});
		assertThat(email.getBounceToRecipient().getAddress()).isEqualTo(source);
		assertThat(email.getBounceToRecipient().getName()).isEqualTo("Bounce");
		assertThat(email.getDispositionNotificationToRecipients().get(0).getAddress()).isEqualTo(source);
		assertThat(email.getDispositionNotificationToRecipients().get(0).getName()).isEqualTo("Read notification");
		assertThat(email.getReturnReceiptToRecipients().get(0).getAddress()).isEqualTo(source);
		assertThat(email.getReturnReceiptToRecipients().get(0).getName()).isEqualTo("Return receipt");
	}

	@ParameterizedTest
	@ValueSource(strings = {"(comment only)", "   ", " \t "})
	void recoveredEmailAddressFieldsStillFailValidationBeforeSubmission(final String source) throws Exception {
		final CustomMailer customMailer = mock(CustomMailer.class);
		try (Mailer mailer = mail.mailerBuilder().withSMTPServer("unreachable.example.invalid", 25).withCustomMailer(customMailer).buildMailer()) {
			for (final Email email : asList(
					validEmailBuilder().from("Sender", source).buildEmail(),
					validEmailBuilder().withReplyTo("Reply", source).buildEmail(),
					validEmailBuilder().withBounceTo("Bounce", source).buildEmail(),
					validEmailBuilder().withDispositionNotificationTo("Read notification", source).buildEmail(),
					validEmailBuilder().withReturnReceiptTo("Return receipt", source).buildEmail())) {
				assertThatThrownBy(() -> mailer.sync().sendMail(email))
						.isInstanceOf(MailInvalidAddressException.class)
						.hasMessageContaining(source);
			}
		}
		verifyNoInteractions(customMailer);
	}

	private EmailPopulatingBuilder validEmailBuilder() {
		return mail.emailBuilder().startingBlank()
				.from("sender@example.org")
				.withRecipients(mail.recipientBuilder().to("Recipient", "recipient@example.org"))
				.withPlainText("No content should reach the sending callback");
	}
}
