package org.simplejavamail.api.email;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.Message.RecipientType;
import java.util.Objects;

import static java.lang.String.format;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * An immutable recipient object, with a name, emailaddress and recipient type (eg {@link RecipientType#BCC}).
 */
public final class Recipient {

	@Nullable
	private final String name;
	@Nonnull
	private final String address;
	@Nullable
	private final RecipientType type;

	/**
	 * Constructor; initializes this recipient object.
	 * 
	 * @param name The name of the recipient, optional in which just the address is shown.
	 * @param address The email address of the recipient.
	 * @param type The recipient type (eg. {@link RecipientType#TO}), optional for {@code from} and {@code replyTo} fields.
	 * @see RecipientType
	 */
	public Recipient(@Nullable final String name, @Nonnull final String address, @Nullable final RecipientType type) {
		this.name = name;
		this.address = checkNonEmptyArgument(address, "address");
		this.type = type;
	}

	public String asStandardString() {
		return valueNullOrEmpty(getName()) ? getAddress() : format("%s <%s>", getName(), getAddress());
	}

	@Override
	public boolean equals(final Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		final Recipient recipient = (Recipient) o;
		return Objects.equals(name, recipient.name) &&
				Objects.equals(address, recipient.address) &&
				Objects.equals(type, recipient.type);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name, address, type);
	}

	@Nonnull
	@Override public String toString() {
		return "Recipient{" +
				"name='" + name + '\'' +
				", address='" + address + '\'' +
				", type=" + type +
				'}';
	}

	/**
	 * Bean getter for {@link #name};
	 */
	@Nullable
	public String getName() {
		return name;
	}

	/**
	 * Bean getter for {@link #address};
	 */
	@Nonnull
	public String getAddress() {
		return address;
	}

	/**
	 * Bean getter for {@link #type};
	 */
	@Nullable
	public RecipientType getType() {
		return type;
	}
}