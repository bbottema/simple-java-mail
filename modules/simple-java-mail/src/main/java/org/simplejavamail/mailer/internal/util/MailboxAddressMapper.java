package org.simplejavamail.mailer.internal.util;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Turns the addresses passed to a transport into strings for inspection. A named group remains one entry;
 * this mapper does not expand it into its members.
 * <p>
 * Rehearsals use {@link #requireMailboxAddresses(Address[])} so every supplied address has an entry in the result.
 */
public final class MailboxAddressMapper {

	private MailboxAddressMapper() {
	}

	/**
	 * @return The address strings in input order. Null addresses and null address values are omitted; groups remain one entry.
	 */
	@NotNull
	public static List<String> toMailboxAddresses(@NotNull final Address[] addresses) {
		final List<String> mailboxAddresses = new ArrayList<>(requireNonNull(addresses, "addresses").length);
		for (Address address : addresses) {
			final String mailboxAddress = toMailboxAddress(address);
			if (mailboxAddress != null) {
				mailboxAddresses.add(mailboxAddress);
			}
		}
		return mailboxAddresses;
	}

	/**
	 * @return Every address string in input order, with each group kept as one entry.
	 * @throws MessagingException When an input address or its string value is null.
	 */
	@NotNull
	public static List<String> requireMailboxAddresses(@NotNull final Address[] addresses) throws MessagingException {
		final List<String> mailboxAddresses = toMailboxAddresses(addresses);
		if (mailboxAddresses.size() != addresses.length) {
			throw new MessagingException("Unable to resolve an envelope recipient address");
		}
		return mailboxAddresses;
	}

	@Nullable
	private static String toMailboxAddress(@Nullable final Address address) {
		if (address instanceof InternetAddress) {
			return ((InternetAddress) address).getAddress();
		}
		return address == null ? null : address.toString();
	}
}
