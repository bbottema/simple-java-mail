package org.simplejavamail.api.internal.clisupport;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption;
import org.simplejavamail.api.internal.clisupport.model.Cli;

import static org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType.EMAIL;

/** Internal scalar adapter preserving existing CLI options while Java callers use grouped configuration. */
@Cli.BuilderApiNode(builderApiType = EMAIL)
public interface CliExactEmailConfigurationBuilder {

	/**
	 * Sets the SMTP Delivery Status Notification (DSN) options for this exact email. These transport-level options do not change the authoritative EML
	 * bytes. Shared NOTIFY/RET-only requests are best-effort; a fixed envelope identifier or explicit recipient NOTIFY requires DSN support
	 * on the actual send connection. Recipient preferences supplied through {@link ExactEmailBuilder#withEnvelopeRecipients(Recipient...)} win over shared NOTIFY.
	 * Calling this method again replaces the complete previous DSN value.
	 *
	 * @param deliveryStatusNotification Delivery-status notification options for SMTP submission.
	 * @return This builder.
	 *
	 * @see CliEmailConfigurationBuilder#withDeliveryStatusNotification(DeliveryStatusNotification)
	 */
	@Cli.ExcludeApi(reason = "The typed NOTIFY and return-option methods expose these settings to the CLI")
	ExactEmailBuilder withDeliveryStatusNotification(@NotNull DeliveryStatusNotification deliveryStatusNotification);

	/**
	 * Removes the shared notification request, including its fixed envelope identifier. Recipient-specific preferences are retained.
	 * Configured locks still apply when the Email is prepared; this does not change its preserved bytes.
	 * @return This builder.
	 * @see #withDeliveryStatusNotification(DeliveryStatusNotification)
	 */
	@Cli.ExcludeApi(reason = "A fresh exact-email CLI builder has no shared notification request to clear")
	ExactEmailBuilder clearDeliveryStatusNotification();

	/**
	 * Returns the configured shared request, or {@code null} when no shared option or fixed identifier remains.
	 * @see #withDeliveryStatusNotification(DeliveryStatusNotification)
	 */
	@Nullable
	DeliveryStatusNotification getDeliveryStatusNotification();

	/**
	 * Chooses which delivery notifications to request, replacing the previous shared preference while retaining the return option and envelope identifier.
	 * Explicit recipient preferences still win. This does not change the exact EML bytes or guarantee that a notification will arrive.
	 * {@link NotifyOption#NEVER} requests no notifications and cannot be combined with other events. An empty array removes the shared preference;
	 * at least one DSN option or fixed envelope identifier must remain when building the Email.
	 *
	 * @param notifyOptions Requested events: {@code SUCCESS}, {@code FAILURE}, or {@code DELAY}, or {@code NEVER} alone.
	 *                      The CLI accepts comma/semicolon-separated names, ignoring case.
	 * @return This builder.
	 * @see CliEmailConfigurationBuilder#withDeliveryStatusNotificationNotifyOptions(NotifyOption...)
	 */
	@Cli.OptionNameOverride("withEnvelopeDsnNotifyOptions")
	ExactEmailBuilder withDeliveryStatusNotificationNotifyOptions(@NotNull NotifyOption @NotNull ...notifyOptions);

	/**
	 * Removes the shared notification-event preference, retaining the return option, fixed identifier and recipient preferences.
	 * If no shared DSN component remains, the request becomes absent rather than requesting NEVER.
	 * @return This builder.
	 * @see #withDeliveryStatusNotificationNotifyOptions(NotifyOption...)
	 */
	@Cli.ExcludeApi(reason = "A fresh exact-email CLI builder has no shared notification events to clear")
	ExactEmailBuilder clearDeliveryStatusNotificationNotifyOptions();

	/**
	 * Chooses how much of the original email a delivery-failure notification may return, retaining notification events and the envelope identifier.
	 * This does not change the exact EML bytes. The request is best-effort and depends on server support.
	 *
	 * @param returnOption {@link ReturnOption#FULL_MESSAGE} for the complete email or {@link ReturnOption#HEADERS_ONLY} for headers without content.
	 *                     The CLI also accepts {@code FULL} and {@code HDRS}, ignoring case.
	 * @return This builder.
	 * @see CliEmailConfigurationBuilder#withDeliveryStatusNotificationReturnOption(ReturnOption)
	 */
	@Cli.OptionNameOverride("withEnvelopeDsnReturnOption")
	ExactEmailBuilder withDeliveryStatusNotificationReturnOption(@NotNull ReturnOption returnOption);

	/**
	 * Removes the return preference while retaining shared notification events and the fixed identifier.
	 * If no shared DSN component remains, the request becomes absent. Server or provider defaults may still apply.
	 * @return This builder.
	 * @see #withDeliveryStatusNotificationReturnOption(ReturnOption)
	 */
	@Cli.ExcludeApi(reason = "A fresh exact-email CLI builder has no notification return preference to clear")
	ExactEmailBuilder clearDeliveryStatusNotificationReturnOption();

	/**
	 * Fixes the unencoded SMTP transaction identifier (ENVID) without changing the exact EML bytes or other DSN options.
	 * The value follows the validation, correlation and required-server-support contract of
	 * {@link CliEmailConfigurationBuilder#fixingEnvelopeId(String)}.
	 * A supplied identifier is validated when inspecting {@link #getDeliveryStatusNotification()} or when {@link ExactEmailBuilder#buildEmail()} creates the Email;
	 * {@code null} resumes automatic generation on each send.
	 *
	 * @param envelopeId A fixed printable ASCII identifier, at most 94 characters after SMTP encoding (100 including {@code ENVID=}), or {@code null}.
	 * @return This builder.
	 * @see CliEmailConfigurationBuilder#fixingEnvelopeId(String)
	 */
	@Cli.OptionNameOverride("fixingExactEnvelopeId")
	ExactEmailBuilder fixingEnvelopeId(@Nullable @Cli.Optional String envelopeId);

	/**
	 * Removes the fixed identifier, allowing automatic per-send generation where supported. Other notification options remain unchanged.
	 * @return This builder.
	 * @see #fixingEnvelopeId(String)
	 */
	@Cli.ExcludeApi(reason = "A fresh exact-email CLI builder has no fixed envelope identifier to clear")
	ExactEmailBuilder clearEnvelopeId();

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
	 * @see CliEmailConfigurationBuilder#withTlsRequiredForOnwardDelivery()
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
	 * Returns whether this builder requests TLS for onward delivery; locks may additionally require it when sending.
	 * @see #withTlsRequiredForOnwardDelivery()
	 */
	boolean isTlsRequiredForOnwardDelivery();

	/**
	 * Chooses whether this exact email may still be submitted for accepted recipients when another envelope recipient is rejected.
	 * For Alice and Bob accepted and Carol rejected, {@code true} permits submission for Alice and Bob; {@code false} withholds content for everyone.
	 * Partial acceptance still fails the overall send and supplies its submission receipt. This changes envelope behavior only, never the EML bytes.
	 * <p>
	 * Calling this method again replaces the value set on this builder. Exact Emails bypass ordinary defaults/overrides, but applicable
	 * {@code simplejavamail.locked.*} settings still apply.
	 *
	 * @param sendingToAcceptedRecipients {@code true} to continue for accepted recipients, or {@code false} to withhold content after a known rejection.
	 * @return This builder.
	 * @see CliEmailConfigurationBuilder#withSendingToAcceptedRecipients(boolean)
	 */
	@Cli.OptionNameOverride("withExactSendingToAcceptedRecipients")
	ExactEmailBuilder withSendingToAcceptedRecipients(boolean sendingToAcceptedRecipients);

	/**
	 * Removes the value set by {@link #withSendingToAcceptedRecipients(boolean)}. Exact Emails bypass ordinary templates;
	 * existing provider behavior and applicable locks still apply.
	 * This is not an explicit {@code false}.
	 * @return This builder.
	 * @see #withSendingToAcceptedRecipients(boolean)
	 */
	@Cli.ExcludeApi(reason = "A fresh exact-email CLI builder has no recipient-rejection choice to clear")
	ExactEmailBuilder clearSendingToAcceptedRecipients();

	/**
	 * @return The recipient-rejection setting on this builder, or {@code null} when unset.
	 * @see #withSendingToAcceptedRecipients(boolean)
	 */
	@Nullable
	Boolean getSendingToAcceptedRecipients();
}
