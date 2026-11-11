package org.simplejavamail.api.internal.clisupport;

import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.internal.clisupport.model.Cli;

import static org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType.EMAIL;

/** Scalar command-line adapter for deliberate reply and forward operation options. */
@Cli.BuilderApiNode(builderApiType = EMAIL)
public interface CliEmailHelperBuilder {
	/**
	 * Reads the supplied EML/MSG and prepares a reply using its interpreted logical destinations and content.
	 * Quoting decrypted confidential material requires outgoing encryption unless disclosure is explicitly allowed.
	 * The permission waives only that helper requirement and leaves configured encryption binding.
	 *
	 * @param message Source EML or MSG file.
	 * @param replyToAll Whether to include the selected original To/Cc recipients in the reply.
	 * @param quotationTemplate HTML quotation markup with the normal reply placeholders.
	 * @param allowUnencryptedContent Whether this reply may include automatically reused confidential content without encryption.
	 * @return The new reply builder.
	 * @see org.simplejavamail.api.email.EmailStartingBuilder#replyingTo(MimeMessage, org.simplejavamail.api.email.ReplyOptions)
	 */
	@Cli.OptionNameOverride("replyingWithOptions")
	EmailPopulatingBuilder replyingTo(@NotNull MimeMessage message, boolean replyToAll,
			@NotNull String quotationTemplate, boolean allowUnencryptedContent);

	/**
	 * Prepares a forward of the supplied MIME representation with explicit disclosure permission.
	 * This is interpreted forwarding; retained original-input forwarding is a separate Java operation.
	 * Permission leaves explicit/mandatory outgoing encryption binding and uses a safe outer subject.
	 *
	 * @param message Source EML or MSG file.
	 * @param allowUnencryptedContent Whether this forward may include automatically reused confidential content without encryption.
	 * @return The new forward builder.
	 * @see org.simplejavamail.api.email.EmailStartingBuilder#forwarding(MimeMessage, org.simplejavamail.api.email.ForwardOptions)
	 */
	@Cli.OptionNameOverride("forwardingWithOptions")
	EmailPopulatingBuilder forwarding(@NotNull MimeMessage message, boolean allowUnencryptedContent);
}
