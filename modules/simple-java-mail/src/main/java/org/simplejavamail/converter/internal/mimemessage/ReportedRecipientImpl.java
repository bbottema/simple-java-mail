package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.ReportedRecipient;

import static java.util.Objects.requireNonNull;

/** Keep the original address alongside a detached, optional Internet-mailbox view. */
@Value
class ReportedRecipientImpl implements ReportedRecipient {

	private static final long serialVersionUID = 1L;

	String type;
	String address;
	// Source identity is the reported type/address, not the convenience view derived from it.
	@EqualsAndHashCode.Exclude
	@Nullable Recipient mailbox;

	@Builder
	private ReportedRecipientImpl(final String type, final String address) {
		this.type = requireNonNull(type, "type");
		this.address = requireNonNull(address, "address");
		mailbox = parseInternetMailbox(type, address);
	}

	@Nullable
	private static Recipient parseInternetMailbox(final String type, final String address) {
		if (!"rfc822".equalsIgnoreCase(type) && !"utf-8".equalsIgnoreCase(type)) {
			return null;
		}
		try {
			final InternetAddress parsed = new InternetAddress(address, true);
			if (parsed.isGroup()) {
				return null;
			}
			parsed.validate();
			return new Recipient(parsed.getPersonal(), parsed.getAddress(), null, null);
		} catch (final AddressException malformedMailbox) {
			// A report can still preserve a foreign or malformed address without claiming it is a usable Internet mailbox.
			return null;
		}
	}

	@Override
	public String toString() {
		return "ReportedRecipient{mailboxAvailable=" + (mailbox != null) + "}";
	}
}
