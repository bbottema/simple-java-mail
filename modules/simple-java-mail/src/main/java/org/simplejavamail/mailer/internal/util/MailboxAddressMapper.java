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
 * Maps Jakarta Mail address objects to the mailbox-only representation exposed by provider-neutral APIs.
 * <p>
 * Submission receipts omit unresolved provider facts so a malformed address supplied by an adapter cannot mask the original submission failure.
 * Rehearsals use {@link #requireMailboxAddresses(Address[])} because their envelope-recipient snapshot must be complete.
 */
public final class MailboxAddressMapper {

	private MailboxAddressMapper() {
	}

	/**
	 * @return Resolved mailbox addresses in input order, omitting null or otherwise unresolved entries.
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
	 * @return Every resolved mailbox address in input order.
	 * @throws MessagingException When any input entry has no mailbox representation.
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
