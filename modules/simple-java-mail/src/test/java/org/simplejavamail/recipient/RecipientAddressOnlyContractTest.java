package org.simplejavamail.recipient;

import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Recipient;
import testutil.ConfigLoaderTestHelper;

import static org.assertj.core.api.Assertions.assertThat;

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
}
