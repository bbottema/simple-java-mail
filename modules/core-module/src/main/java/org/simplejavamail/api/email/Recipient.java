package org.simplejavamail.api.email;

import jakarta.mail.Message;
import jakarta.mail.Message.RecipientType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.security.cert.X509Certificate;
import java.util.Objects;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * An immutable recipient object, with a name, emailaddress and recipient type (eg {@link Message.RecipientType#BCC}),
 * and optionally an S/MIME certificate for encrypting messages on a per-user basis.
 *
 * @see IRecipientBuilder
 */
public final class Recipient implements Serializable {

	private static final long serialVersionUID = 1234567L;

	@Nullable
	private final String name;
	@NotNull
	private final String address;
	@Nullable
	private final RecipientType type;
	@Nullable
	private final X509Certificate smimeCertificate;

	/**
	 * Constructor; initializes this recipient object.
	 * 
	 * @param name Optional explicit name of the recipient, otherwise taken from inside the address (if provided) (for example "Joe Sixpack &lt;joesixpack@beerme.com&gt;").
	 * @param address The email address of the recipient, can contain a name, but is ignored if a name was seperately provided.
	 * @param type The recipient type (e.g. {@link RecipientType#TO}), optional for {@code from} and {@code replyTo} fields.
	 * @param smimeCertificate Optional S/MIME certificate for this recipient, used for encrypting messages on a per-user basis. Overrides certificate provided
	 *                         on {@link Email} level and {@link org.simplejavamail.api.mailer.Mailer} level (if provided).
	 * @see IRecipientBuilder
	 */
	public Recipient(@Nullable final String name, @NotNull final String address, @Nullable final RecipientType type, @Nullable final X509Certificate smimeCertificate) {
		this.name = name;
		this.address = checkNonEmptyArgument(address, "address");
		this.type = type;
		this.smimeCertificate = smimeCertificate;
	}

	/**
	 * Checks equality based on {@link #name}, {@link #address} and {@link #type}.
	 */
	@Override
	public boolean equals(@Nullable final Object o) {
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

	@NotNull
	@Override public String toString() {
		return "Recipient{" +
				"name='" + name + '\'' +
				", address='" + address + '\'' +
				", type=" + type +
				", smimeCertificate=" + smimeCertificate +
				'}';
	}

	/**
	 * @see IRecipientBuilder#withName(String)
	 */
	@Nullable
	public String getName() {
		return name;
	}

	/**
	 * @see IRecipientBuilder#withAddress(String)
	 */
	@NotNull
	public String getAddress() {
		return address;
	}

	/**
	 * @see IRecipientBuilder#withType(RecipientType)
	 */
	@Nullable
	public RecipientType getType() {
		return type;
	}

	/**
	 * @see IRecipientBuilder#withSmimeCertificate(X509Certificate)
	 */
	@Nullable
	public X509Certificate getSmimeCertificate() {
		return smimeCertificate;
	}
}