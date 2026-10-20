package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.SmtpDeliveryOptions;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;

import java.util.Collection;
import java.util.List;

/**
 * Constrained builder for an {@link Email} whose complete, existing EML representation must be submitted without rebuilding its MIME content.
 * <p>
 * The resulting object remains an ordinary {@code Email}: all getters expose a parsed view for inspection, and every existing Mailer send API accepts it.
 * Those parsed values are not a second editable source, however; the original EML bytes remain authoritative. Copying the result through
 * {@link EmailStartingBuilder#copying(Email)} intentionally creates an ordinary composed email and gives up exact-byte preservation.
 * That copy also makes no guarantee that an already applied signature remains valid; copying does not automatically remove or re-sign it.
 * Reuse the original exact Email for unchanged resubmission, or start another exact builder from the authoritative finalized input when
 * supplying a new explicit envelope.
 * <p>
 * Only SMTP-envelope recipients, an optional envelope sender, and optional delivery-status notification settings can be supplied here. Header recipients
 * such as {@code To}, {@code Cc}, and {@code Bcc} never determine the delivery envelope. Conversely, exact submission retains every supplied header,
 * including {@code Bcc}, {@code Resent-Bcc}, and {@code Content-Length}; callers must provide an already safe outbound representation.
 * <p>
 * Mailer defaults, overrides, content validation, embedded-image resolution, MIME rebuilding, and DKIM/S/MIME/OpenPGP processing are bypassed because
 * any such transformation would contradict exact submission. A transport adapter must explicitly support full-byte preservation; the bundled Angus Mail
 * adapter does.
 * <p>
 * Factory-scoped locked configuration is not an ordinary template and is not bypassed. Compatible envelope requirements, such as an archive recipient,
 * still apply. Matching original headers can satisfy readable-header locks, but duplicate Subject, From, or Reply-To fields prevent confirming a lock
 * on that field. A single Reply-To field can still contain multiple addresses. Requirements that cannot be satisfied without changing preserved content
 * are rejected instead of rewriting these bytes.
 */
@Cli.BuilderApiNode(builderApiType = CliBuilderApiType.EMAIL)
public interface ExactEmailBuilder {

    /**
     * Replaces all supplied SMTP-delivery choices without changing the authoritative EML bytes.
     * Earlier group choices are discarded. Exact Emails bypass ordinary defaults and overrides;
     * applicable mandatory locks and provider fallback still apply. Recipient-specific NOTIFY remains on envelope recipients.
     * @param config Immutable SMTP envelope choices.
     * @return This builder.
     */
    @Cli.ExcludeApi(reason = "The CLI uses its internal exact-envelope adapter")
    ExactEmailBuilder withSmtpDeliveryOptions(@NotNull SmtpDeliveryOptions config);
    /** Returns only the supplied envelope group.
     * @see #withSmtpDeliveryOptions(SmtpDeliveryOptions) */
    @NotNull SmtpDeliveryOptions getSmtpDeliveryOptions();
    /** Removes the supplied group; preserved bytes and envelope recipients remain.
     * @see #withSmtpDeliveryOptions(SmtpDeliveryOptions) */
    @Cli.ExcludeApi(reason = "A fresh exact CLI builder has no SMTP-delivery choices")
    ExactEmailBuilder useConfiguredSmtpDeliveryOptions();

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
	 * Removes the configured envelope recipients without changing any recipient headers in the preserved EML.
	 * Supply at least one new envelope recipient before building another Email. Previously built Emails remain unchanged.
	 * @return This builder.
	 * @see #withEnvelopeRecipients(String...)
	 */
	@Cli.ExcludeApi(reason = "A fresh exact-email CLI builder has no envelope recipients to clear")
	ExactEmailBuilder clearEnvelopeRecipients();

	/**
	 * Returns a detached, modifiable copy of the configured envelope recipients, including their preferences, order and duplicates.
	 * Changing this list does not configure the builder or change previously built Emails.
	 * @see #withEnvelopeRecipients(Recipient...)
	 */
	@NotNull
	List<Recipient> getEnvelopeRecipients();

	/**
	 * Sets the optional SMTP-envelope sender, which must contain exactly one valid mailbox. Calling this method again replaces the previous value.
	 *
	 * @param senderAddress Mailbox address to use as the SMTP envelope sender.
	 * @return This builder.
	 */
	ExactEmailBuilder withEnvelopeSender(@NotNull String senderAddress);

	/**
	 * Removes the explicit envelope sender so the transport can use its normal Session or message-sender fallback.
	 * This does not request an empty SMTP sender and does not restore a historical Return-Path header as an explicit sender.
	 * @return This builder.
	 * @see #withEnvelopeSender(String)
	 */
	@Cli.ExcludeApi(reason = "A fresh exact-email CLI builder has no explicit envelope sender to clear")
	ExactEmailBuilder clearEnvelopeSender();

	/**
	 * Returns the explicit envelope sender, or {@code null} when the transport will choose its fallback.
	 * @see #withEnvelopeSender(String)
	 */
	@Nullable
	Recipient getEnvelopeSender();

	/**
	 * Returns any clear delivery report parsed from the authoritative EML. This does not decrypt or rewrite the original message.
	 * @see Email#getDeliveryStatusReport()
	 */
	@Nullable
	DeliveryStatusReport getDeliveryStatusReport();

	/**
	 * Builds an {@link Email} whose existing getters expose the parsed EML fields while the original bytes remain authoritative for conversion,
	 * rehearsal, and sending. At least one explicit envelope recipient is required.
	 *
	 * @return The canonical Email carrying the exact source and explicit SMTP envelope.
	 */
	@Cli.ExcludeApi(reason = "The CLI builds the email after applying its options")
	Email buildEmail();
}
