package org.simplejavamail.api.internal.clisupport;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.internal.clisupport.model.Cli;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType.EMAIL;

/**
 * Internal facade that keeps dedicated recipient options available to the generated CLI without adding the old recipient overloads
 * back to {@link EmailPopulatingBuilder}.
 */
@Cli.BuilderApiNode(builderApiType = EMAIL)
public interface CliEmailRecipientBuilder {

	/**
	 * Adds one or more TO recipients.
	 *
	 * @param name Optional name applied to every parsed address. Names contained in the address string are retained when this is omitted.
	 * @param oneOrMoreAddresses A single RFC2822 address or a comma- or semicolon-delimited list of addresses.
	 */
	@NotNull
	default CliEmailRecipientBuilder to(@Nullable @Cli.Optional String name, @NotNull String oneOrMoreAddresses) {
		((EmailPopulatingBuilder) this).withRecipients(name, true, TO, oneOrMoreAddresses);
		return this;
	}

	/**
	 * Adds one or more CC recipients.
	 *
	 * @param name Optional name applied to every parsed address. Names contained in the address string are retained when this is omitted.
	 * @param oneOrMoreAddresses A single RFC2822 address or a comma- or semicolon-delimited list of addresses.
	 */
	@NotNull
	default CliEmailRecipientBuilder cc(@Nullable @Cli.Optional String name, @NotNull String oneOrMoreAddresses) {
		((EmailPopulatingBuilder) this).withRecipients(name, true, CC, oneOrMoreAddresses);
		return this;
	}

	/**
	 * Adds one or more BCC recipients.
	 *
	 * @param name Optional name applied to every parsed address. Names contained in the address string are retained when this is omitted.
	 * @param oneOrMoreAddresses A single RFC2822 address or a comma- or semicolon-delimited list of addresses.
	 */
	@NotNull
	default CliEmailRecipientBuilder bcc(@Nullable @Cli.Optional String name, @NotNull String oneOrMoreAddresses) {
		((EmailPopulatingBuilder) this).withRecipients(name, true, BCC, oneOrMoreAddresses);
		return this;
	}
}
