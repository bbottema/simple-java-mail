package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption;
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
	 * Appends recipients, retaining their per-recipient NOTIFY preferences. Each address must be one valid mailbox. Names, recipient types and
	 * S/MIME certificates do not change the exact MIME bytes. Duplicate addresses retain their individual preferences and argument order.
	 * @see IRecipientBuilder#withDeliveryStatusNotificationNotifyOptions(org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption...)
	 */
	@Cli.ExcludeApi(reason = "Recipient-specific policies use the Java recipient builders; the CLI supports the shared Email NOTIFY preference")
	ExactEmailBuilder withEnvelopeRecipients(@NotNull Recipient @NotNull ... recipients);

	/**
	 * Sets the optional SMTP-envelope sender, which must contain exactly one valid mailbox. Calling this method again replaces the previous value.
	 *
	 * @param senderAddress Mailbox address to use as the SMTP envelope sender.
	 * @return This builder.
	 */
	ExactEmailBuilder withEnvelopeSender(@NotNull String senderAddress);

	/**
	 * Sets the SMTP Delivery Status Notification (DSN) options for this exact email. These transport-level options do not change the authoritative EML
	 * bytes. Shared NOTIFY/RET-only requests are best-effort; a fixed envelope identifier or explicit recipient NOTIFY requires DSN support
	 * on the actual send connection. Recipient preferences supplied through {@link #withEnvelopeRecipients(Recipient...)} win over shared NOTIFY.
	 * Calling this method again replaces the complete previous DSN value.
	 *
	 * @param deliveryStatusNotification Delivery-status notification options for SMTP submission.
	 * @return This builder.
	 *
	 * @see EmailPopulatingBuilder#withDeliveryStatusNotification(DeliveryStatusNotification)
	 */
	@Cli.ExcludeApi(reason = "The typed NOTIFY and return-option methods expose these settings to the CLI")
	ExactEmailBuilder withDeliveryStatusNotification(@NotNull DeliveryStatusNotification deliveryStatusNotification);

	/**
	 * Chooses which delivery notifications to request, replacing the previous shared preference while retaining the return option and envelope identifier.
	 * Explicit recipient preferences still win. This does not change the exact EML bytes or guarantee that a notification will arrive.
	 * {@link NotifyOption#NEVER} requests no notifications and cannot be combined with other events. An empty array removes the shared preference;
	 * at least one DSN option or fixed envelope identifier must remain when building the Email.
	 *
	 * @param notifyOptions Requested events: {@code SUCCESS}, {@code FAILURE}, or {@code DELAY}, or {@code NEVER} alone.
	 *                      The CLI accepts comma/semicolon-separated names, ignoring case.
	 * @return This builder.
	 * @see EmailPopulatingBuilder#withDeliveryStatusNotificationNotifyOptions(NotifyOption...)
	 */
	@Cli.OptionNameOverride("withEnvelopeDsnNotifyOptions")
	ExactEmailBuilder withDeliveryStatusNotificationNotifyOptions(@NotNull NotifyOption @NotNull ...notifyOptions);

	/**
	 * Chooses how much of the original email a delivery-failure notification may return, retaining notification events and the envelope identifier.
	 * This does not change the exact EML bytes. The request is best-effort and depends on server support.
	 *
	 * @param returnOption {@link ReturnOption#FULL_MESSAGE} for the complete email or {@link ReturnOption#HEADERS_ONLY} for headers without content.
	 *                     The CLI also accepts {@code FULL} and {@code HDRS}, ignoring case.
	 * @return This builder.
	 * @see EmailPopulatingBuilder#withDeliveryStatusNotificationReturnOption(ReturnOption)
	 */
	@Cli.OptionNameOverride("withEnvelopeDsnReturnOption")
	ExactEmailBuilder withDeliveryStatusNotificationReturnOption(@NotNull ReturnOption returnOption);

	/**
	 * Fixes the unencoded SMTP transaction identifier (ENVID) without changing the exact EML bytes or other DSN options.
	 * The value follows the validation, correlation and required-server-support contract of
	 * {@link EmailPopulatingBuilder#fixingEnvelopeId(String)}.
	 * A supplied identifier is validated when {@link #buildEmail()} creates the Email; {@code null} resumes automatic generation on each send.
	 *
	 * @param envelopeId A fixed printable ASCII identifier, at most 94 characters after SMTP encoding (100 including {@code ENVID=}), or {@code null}.
	 * @return This builder.
	 * @see EmailPopulatingBuilder#fixingEnvelopeId(String)
	 */
	@Cli.OptionNameOverride("fixingExactEnvelopeId")
	ExactEmailBuilder fixingEnvelopeId(@Nullable @Cli.Optional String envelopeId);

	/**
	 * Requires supporting mail servers to keep using TLS for this exact email's onward delivery, without changing its EML bytes. Normally, TLS protects
	 * only the connection to your SMTP server; onward delivery may still continue without TLS. With this requirement, a supporting server
	 * must return the message as undeliverable rather than send it over an unencrypted connection.
	 * <p>
	 * The bundled Angus adapter applies RFC 8689 {@code REQUIRETLS} and refuses the send before submission unless the current SMTP connection uses
	 * authenticated TLS and the server confirms support. This protects transport between cooperating mail servers, not stored message content, and
	 * cannot prove that every later server complied.
	 *
	 * @return This builder.
	 * @see EmailPopulatingBuilder#withTlsRequiredForOnwardDelivery()
	 */
	@Cli.OptionNameOverride("withExactTlsRequiredForOnwardDelivery")
	ExactEmailBuilder withTlsRequiredForOnwardDelivery();

	/**
	 * Stops this builder from requiring TLS for this exact email's onward delivery. Exact emails bypass configured Email defaults and overrides.
	 *
	 * @return This builder.
	 * @see #withTlsRequiredForOnwardDelivery()
	 */
	@Cli.ExcludeApi(reason = "A fresh exact-email CLI builder has no REQUIRETLS value to clear")
	ExactEmailBuilder clearTlsRequiredForOnwardDelivery();

	/**
	 * Builds an {@link Email} whose existing getters expose the parsed EML fields while the original bytes remain authoritative for conversion,
	 * rehearsal, and sending. At least one explicit envelope recipient is required.
	 *
	 * @return The canonical Email carrying the exact source and explicit SMTP envelope.
	 */
	@Cli.ExcludeApi(reason = "The CLI builds the email after applying its options")
	Email buildEmail();
}
