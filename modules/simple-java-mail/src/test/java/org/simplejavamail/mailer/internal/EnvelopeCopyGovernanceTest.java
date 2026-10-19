package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.internal.InternalEmail;
import testutil.ConfigLoaderTestHelper;

import java.util.Map;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

class EnvelopeCopyGovernanceTest {

	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

	@Test
	void applyingTraversedLocksCopiesTheExistingEnvelopeOnlyOnce() {
		final Recipient first = mail.recipientBuilder().to("First test inbox", "first@example.org");
		final Recipient second = mail.recipientBuilder().cc("Second test inbox", "second@example.org");
		final Email original = mail.emailBuilder().startingBlank()
				.from("sender@example.org")
				.withRecipients(mail.recipientBuilder().to("Visible recipient", "visible@example.org"))
				.withOverrideReceivers(first, second, first)
				.withSubject("Original")
				.withPlainText("body")
				.buildEmail();

		final Email prepared = governanceWithUnrelatedLock().applyLocksToPreparedEmail(original);

		assertThat(prepared.getOverrideReceivers()).containsExactly(first, second, first);
		assertThat(original.getOverrideReceivers()).containsExactly(first, second, first);
		assertThat(prepared.getRecipients()).containsExactlyElementsOf(original.getRecipients());
		assertThat(prepared.getSubject()).isEqualTo(original.getSubject());
		assertThat(InternalEmail.requireInternalEmail(prepared).isExactEml()).isFalse();
	}

	@Test
	void applyingExactEmailLocksPreservesEnvelopeOccurrencesAndAuthoritativeBytes() {
		final byte[] bytes = ("From: sender@example.org\r\nTo: visible@example.org\r\nCc: copied@example.org\r\n"
				+ "Subject: Original\r\nX-Folded: first\r\n second\r\n\r\nbody\r\n").getBytes(US_ASCII);
		final Email original = mail.emailBuilder().startingFromExactEml(bytes)
				.withEnvelopeRecipients("first@example.org", "second@example.org", "first@example.org")
				.buildEmail();

		final Email prepared = governanceWithUnrelatedLock().prepareExactEmail(original);

		assertThat(prepared.getOverrideReceivers()).extracting(Recipient::getAddress)
				.containsExactly("first@example.org", "second@example.org", "first@example.org");
		assertThat(original.getOverrideReceivers()).extracting(Recipient::getAddress)
				.containsExactly("first@example.org", "second@example.org", "first@example.org");
		assertThat(prepared.getRecipients()).containsExactlyElementsOf(original.getRecipients());
		assertThat(InternalEmail.requireInternalEmail(prepared).isExactEml()).isTrue();
		assertThat(EmailConverter.emailToEMLByteArray(prepared)).containsExactly(bytes);
		assertThat(EmailConverter.emailToEMLByteArray(original)).containsExactly(bytes);
	}

	private static EmailGovernanceImpl governanceWithUnrelatedLock() {
		return (EmailGovernanceImpl) EmailGovernanceImpl.withConfig(ConfigLoader.builder()
				.withMap(Map.of("simplejavamail.locked.smtp.host", "relay.example.org"))
				.load());
	}
}
