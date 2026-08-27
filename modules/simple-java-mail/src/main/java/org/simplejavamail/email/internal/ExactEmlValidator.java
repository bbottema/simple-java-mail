package org.simplejavamail.email.internal;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

public final class ExactEmlValidator {

	private ExactEmlValidator() {
	}

	public static byte @NotNull [] copyAndValidateEml(final byte @NotNull [] emlBytes) {
		final byte[] copiedBytes = checkNonEmptyArgument(emlBytes, "emlBytes").clone();
		validateCanonicalLineEndings(copiedBytes);
		return copiedBytes;
	}

	static void validateEnvelope(@NotNull final Email email) {
		checkNonEmptyArgument(email.getOverrideReceivers(), "envelopeRecipients");
		for (final Recipient recipient : email.getOverrideReceivers()) {
			parseMailbox(recipient.getAddress(), "envelopeRecipient");
		}
		if (email.getBounceToRecipient() != null) {
			parseMailbox(email.getBounceToRecipient().getAddress(), "envelopeSender");
		}
	}

	@NotNull
	public static Recipient parseMailbox(@NotNull final String address, @NotNull final String parameterName) {
		try {
			final InternetAddress[] parsedAddresses = InternetAddress.parse(checkNonEmptyArgument(address, parameterName), true);
			if (parsedAddresses.length != 1 || parsedAddresses[0].isGroup()) {
				throw new IllegalArgumentException(parameterName + " must contain exactly one mailbox: " + address);
			}
			parsedAddresses[0].validate();
			return new Recipient(parsedAddresses[0].getPersonal(), parsedAddresses[0].getAddress(), null, null);
		} catch (final AddressException invalidAddress) {
			throw new IllegalArgumentException(parameterName + " must contain exactly one valid mailbox: " + address, invalidAddress);
		}
	}

	private static void validateCanonicalLineEndings(final byte[] emlBytes) {
		for (int index = 0; index < emlBytes.length; index++) {
			if (emlBytes[index] == '\r' && (index + 1 == emlBytes.length || emlBytes[index + 1] != '\n')) {
				throw new IllegalArgumentException("Exact EML must use canonical CRLF line endings; found a bare CR");
			}
			if (emlBytes[index] == '\n' && (index == 0 || emlBytes[index - 1] != '\r')) {
				throw new IllegalArgumentException("Exact EML must use canonical CRLF line endings; found a bare LF");
			}
		}
		if (emlBytes.length < 2 || emlBytes[emlBytes.length - 2] != '\r' || emlBytes[emlBytes.length - 1] != '\n') {
			throw new IllegalArgumentException("Exact EML must end with CRLF");
		}
	}
}
