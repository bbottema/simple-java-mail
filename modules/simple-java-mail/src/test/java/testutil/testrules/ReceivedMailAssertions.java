package testutil.testrules;

import jakarta.mail.MessagingException;
import org.simplejavamail.api.email.Email;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared assertions about received mail, independent of Wiser or a real MTA's delivery hook. */
public final class ReceivedMailAssertions {

	private ReceivedMailAssertions() {
	}

	public static void assertEnvelopeMatches(final MimeMessageAndEnvelope received, final Email original) throws MessagingException {
		assertThat(received.getMimeMessage().getMessageID()).isEqualTo(original.getId());
		assertThat(received.getEnvelopeReceiver()).isEqualTo(original.getOverrideReceivers().isEmpty()
				? original.getRecipients().get(0).getAddress() : original.getOverrideReceivers().get(0).getAddress());
		assertThat(received.getEnvelopeSender()).isEqualTo(original.getBounceToRecipient() == null
				? original.getFromRecipient().getAddress() : original.getBounceToRecipient().getAddress());
	}
}
