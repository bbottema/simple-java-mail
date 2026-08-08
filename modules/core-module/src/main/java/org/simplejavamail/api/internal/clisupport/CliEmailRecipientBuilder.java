package org.simplejavamail.api.internal.clisupport;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.IRecipientsBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.internal.clisupport.model.Cli;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType.EMAIL;

/**
 * Internal facade for recipient methods required by the generated CLI and property-backed configuration without exposing that plumbing on
 * {@link EmailPopulatingBuilder}.
 */
@Cli.BuilderApiNode(builderApiType = EMAIL)
public interface CliEmailRecipientBuilder {

	/**
	 * Adds one or more recipients by parsing one or more RFC2822 address strings and applying the given recipient type to every parsed recipient.
	 * <p>
	 * For Java code that already has recipient data, use {@link EmailPopulatingBuilder#withRecipients(Recipient...)} or {@link IRecipientsBuilder}.
	 *
	 * @param name Optional explicit name. If {@code fixedName} is {@code true}, it overwrites names found in the address strings; otherwise it is only
	 *             used when an address string does not contain a name.
	 * @param fixedName Indicates whether the provided name should be applied to all addresses, or only to addresses where a name is missing.
	 * @param recipientType Recipient type to apply, for example {@link jakarta.mail.Message.RecipientType#TO},
	 *                      {@link jakarta.mail.Message.RecipientType#CC} or {@link jakarta.mail.Message.RecipientType#BCC}.
	 * @param oneOrMoreAddressesEach One or more entries where each entry may be a single RFC2822 address or a delimited list of RFC2822 addresses.
	 *                               Examples:
	 *                               <ul>
	 *                               <li>lolly.pop@pretzelfun.com</li>
	 *                               <li>Moonpie &lt;moonpie@pies.com&gt;;Daisy &lt;daisy@pies.com&gt;</li>
	 *                               <li>a1@b1.c1,a2@b2.c2,a3@b3.c3</li>
	 *                               </ul>
	 */
	@NotNull
	EmailPopulatingBuilder withRecipients(@Nullable @Cli.Optional String name, boolean fixedName,
			@Nullable @Cli.Optional jakarta.mail.Message.RecipientType recipientType, @NotNull String @NotNull ... oneOrMoreAddressesEach);

	/**
	 * Adds one or more TO recipients.
	 *
	 * @param name Optional name applied to every parsed address. Names contained in the address string are retained when this is omitted.
	 * @param oneOrMoreAddresses A single RFC2822 address or a comma- or semicolon-delimited list of addresses.
	 */
	@NotNull
	default CliEmailRecipientBuilder to(@Nullable @Cli.Optional String name, @NotNull String oneOrMoreAddresses) {
		withRecipients(name, true, TO, oneOrMoreAddresses);
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
		withRecipients(name, true, CC, oneOrMoreAddresses);
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
		withRecipients(name, true, BCC, oneOrMoreAddresses);
		return this;
	}
}
