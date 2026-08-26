package org.simplejavamail.mailer.internal.util;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.mailer.internal.util.MailboxAddressMapper.requireMailboxAddresses;
import static org.simplejavamail.mailer.internal.util.MailboxAddressMapper.toMailboxAddresses;

class MailboxAddressMapperTest {

	@Test
	void mapsInternetAddressToMailboxAndFallsBackToCustomRendering() throws Exception {
		final Address[] addresses = {
				new InternetAddress("receiver@example.com", "Named recipient"),
				new CustomAddress("custom-recipient")
		};

		final List<String> mailboxAddresses = requireMailboxAddresses(addresses);

		assertThat(mailboxAddresses).containsExactly("receiver@example.com", "custom-recipient");
	}

	@Test
	void receiptMappingOmitsProviderEntriesWithoutAMailboxRepresentation() throws Exception {
		final Address[] addresses = {
				new InternetAddress("receiver@example.com"),
				null,
				new InternetAddress()
		};

		final List<String> mailboxAddresses = toMailboxAddresses(addresses);

		assertThat(mailboxAddresses).containsExactly("receiver@example.com");
	}

	@Test
	void rehearsalMappingRejectsAnIncompleteEnvelopeSnapshot() throws Exception {
		final Address[] addresses = {new InternetAddress("receiver@example.com"), null};

		assertThatThrownBy(() -> requireMailboxAddresses(addresses))
				.isInstanceOf(MessagingException.class)
				.hasMessage("Unable to resolve an envelope recipient address");
	}

	private static final class CustomAddress extends Address {

		private static final long serialVersionUID = 1L;

		private final String renderedAddress;

		private CustomAddress(final String renderedAddress) {
			this.renderedAddress = renderedAddress;
		}

		@Override
		public String getType() {
			return "custom";
		}

		@Override
		public boolean equals(final Object other) {
			return other instanceof CustomAddress && renderedAddress.equals(((CustomAddress) other).renderedAddress);
		}

		@Override
		public int hashCode() {
			return renderedAddress.hashCode();
		}

		@Override
		public String toString() {
			return renderedAddress;
		}
	}
}
