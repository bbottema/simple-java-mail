package org.simplejavamail.api.email;

import jakarta.mail.Message.RecipientType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An immutable recipient object, with a name, emailaddress and recipient type (eg {@link RecipientType#BCC}),
 * and optionally an S/MIME certificate for encrypting messages on a per-user basis.
 *
 * @see IRecipientBuilder
 */
public final class Recipients implements Serializable {

	private static final long serialVersionUID = 1234567L;

	@NotNull
	private final List<Recipient> recipients = new ArrayList<>();
	@Nullable
	private final String defaultName;
	@Nullable
	private final String overridingName;

	/**
	 * Constructor; initializes this recipient object.
	 *
	 * @param defaultName Optional explicit name of the recipient, otherwise taken from inside the address (if provided) (for example "Joe Sixpack &lt;
	 * @param overridingName The email address of the recipient, can contain a name, but is ignored if a name was seperately provided.
	 * @see IRecipientBuilder
	 */
	public Recipients(@Nullable final String defaultName, @Nullable final String overridingName, @NotNull final Recipient... recipients) {
		this.recipients.addAll(Arrays.asList(recipients));
		this.defaultName = defaultName;
		this.overridingName = overridingName;
	}
}