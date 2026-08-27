package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;

import java.util.Collection;

/**
 * Constrained builder for an {@link Email} whose complete, existing EML representation must be submitted without rebuilding its MIME content.
 * <p>
 * The resulting object remains an ordinary {@code Email}: all getters expose a parsed view for inspection, and every existing Mailer send API accepts it.
 * Those parsed values are not a second editable source, however; the original EML bytes remain authoritative. Copying the result through
 * {@link EmailStartingBuilder#copying(Email)} intentionally creates an ordinary composed email and gives up exact-byte preservation.
 * <p>
 * Only SMTP-envelope recipients, an optional envelope sender, and optional delivery-status notification settings can be supplied here. Header recipients
 * such as {@code To}, {@code Cc}, and {@code Bcc} never determine the delivery envelope. Conversely, exact submission retains every supplied header,
 * including {@code Bcc}, {@code Resent-Bcc}, and {@code Content-Length}; callers must provide an already safe outbound representation.
 * <p>
 * Mailer defaults, overrides, content validation, embedded-image resolution, MIME rebuilding, and DKIM/S/MIME/OpenPGP processing are bypassed because
 * any such transformation would contradict exact submission. A transport adapter must explicitly support full-byte preservation; the bundled Angus Mail
 * adapter does.
 */
@Cli.BuilderApiNode(builderApiType = CliBuilderApiType.EMAIL)
public interface ExactEmailBuilder {

	/**
	 * Appends one or more SMTP-envelope recipients in argument order, without deduplication. Every value must contain exactly one valid mailbox.
	 *
	 * @param recipientAddresses Mailbox addresses to append to the SMTP envelope.
	 * @return This builder.
	 */
	ExactEmailBuilder withEnvelopeRecipients(@NotNull String @NotNull ... recipientAddresses);

	/**
	 * Appends SMTP-envelope recipients in iteration order, without deduplication.
	 *
	 * @param recipientAddresses Mailbox addresses to append to the SMTP envelope.
	 * @return This builder.
	 */
	@Cli.ExcludeApi(reason = "The String array overload is used by the CLI")
	ExactEmailBuilder withEnvelopeRecipients(@NotNull Collection<String> recipientAddresses);

	/**
	 * Sets the optional SMTP-envelope sender, which must contain exactly one valid mailbox. Calling this method again replaces the previous value.
	 *
	 * @param senderAddress Mailbox address to use as the SMTP envelope sender.
	 * @return This builder.
	 */
	ExactEmailBuilder withEnvelopeSender(@NotNull String senderAddress);

	/**
	 * Sets the SMTP Delivery Status Notification (DSN) options for this exact email. These transport-level options do not change the authoritative EML
	 * bytes and only take effect when the receiving SMTP server supports DSN. Calling this method again replaces the complete previous DSN value.
	 *
	 * @param deliveryStatusNotification Delivery-status notification options for SMTP submission.
	 * @return This builder.
	 *
	 * @see EmailPopulatingBuilder#withDeliveryStatusNotification(DeliveryStatusNotification)
	 */
	@Cli.ExcludeApi(reason = "The String overloads are used by the CLI")
	ExactEmailBuilder withDeliveryStatusNotification(@NotNull DeliveryStatusNotification deliveryStatusNotification);

	/**
	 * Sets the SMTP DSN notification events for this exact email, leaving any already configured return option untouched.
	 *
	 * @param notifyOptions String representation of one or more DSN notification events, for example {@code "FAILURE,DELAY"}.
	 * @return This builder.
	 *
	 * @see DeliveryStatusNotification#parseNotifyOptions(String)
	 * @see EmailPopulatingBuilder#withDeliveryStatusNotificationNotifyOptions(String)
	 */
	@Cli.OptionNameOverride("withEnvelopeDsnNotifyOptions")
	ExactEmailBuilder withDeliveryStatusNotificationNotifyOptions(@NotNull String notifyOptions);

	/**
	 * Sets the SMTP DSN return option for this exact email, leaving any already configured notification events untouched.
	 *
	 * @param returnOption String representation of the DSN return option.
	 * @return This builder.
	 *
	 * @see DeliveryStatusNotification#parseReturnOption(String)
	 * @see EmailPopulatingBuilder#withDeliveryStatusNotificationReturnOption(String)
	 */
	@Cli.OptionNameOverride("withEnvelopeDsnReturnOption")
	ExactEmailBuilder withDeliveryStatusNotificationReturnOption(@NotNull String returnOption);

	/**
	 * Builds an {@link Email} whose existing getters expose the parsed EML fields while the original bytes remain authoritative for conversion,
	 * rehearsal, and sending. At least one explicit envelope recipient is required.
	 *
	 * @return The canonical Email carrying the exact source and explicit SMTP envelope.
	 */
	@Cli.ExcludeApi(reason = "The CLI builds the email after applying its options")
	Email buildEmail();
}
