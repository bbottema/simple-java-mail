package org.simplejavamail.api.email.config;

import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.IRecipientBuilder;
import java.util.Set;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import static org.simplejavamail.internal.util.DsnNotifyOptions.copyOf;
import java.io.Serializable;

import static java.util.Objects.requireNonNull;

/**
 * Immutable SMTP-envelope choices shared by composed and exact Emails. These settings never rewrite authoritative exact EML bytes.
 * This value records choices without reading configuration sources or message content.
 * Ordinary composed Emails can inherit Email defaults and overrides; exact Emails bypass those templates and use their supplied envelope choices
 * and provider fallback. Mandatory locks remain binding for both. Recipient-specific NOTIFY preferences stay on recipients and win over shared NOTIFY.
 * @see EmailPopulatingBuilder#withSmtpDeliveryOptions(SmtpDeliveryOptions)
 */
@Value
public class SmtpDeliveryOptions implements Serializable {
    private static final long serialVersionUID = 1L;
    @Nullable DeliveryStatusNotification deliveryStatusNotification;
    boolean tlsRequiredForOnwardDelivery;
    @Nullable Boolean sendingToAcceptedRecipients;
    private SmtpDeliveryOptions(final Builder builder) {
        deliveryStatusNotification = builder.getDeliveryStatusNotification();
        tlsRequiredForOnwardDelivery = builder.tlsRequiredForOnwardDelivery;
        sendingToAcceptedRecipients = builder.sendingToAcceptedRecipients;
    }
    /** @return A builder with no SMTP-envelope choices; normal governance or provider behavior can apply. */
    @NotNull public static Builder builder() { return new Builder(); }
    /** @return An independent builder retaining these supplied choices. */
    @NotNull public Builder toBuilder() { return new Builder(this); }
    /**
     * @return The supplied shared DSN request, or {@code null} when no shared preference or fixed identifier remains; no identifier is generated here.
     * @see Builder#withDeliveryStatusNotification(DeliveryStatusNotification)
     */
    @Nullable public DeliveryStatusNotification getDeliveryStatusNotification() { return deliveryStatusNotification; }
    /** @see Builder#withTlsRequiredForOnwardDelivery() */
    public boolean isTlsRequiredForOnwardDelivery() { return tlsRequiredForOnwardDelivery; }
    /**
     * @return The supplied recipient-rejection choice, or {@code null} when unset; this does not resolve templates or provider settings.
     * @see Builder#withSendingToAcceptedRecipients(boolean)
     */
    @Nullable public Boolean getSendingToAcceptedRecipients() { return sendingToAcceptedRecipients; }

    /** Collects transaction-wide preferences; recipient-specific NOTIFY preferences stay on recipients. */
    public static final class Builder {
        private Set<DeliveryStatusNotification.NotifyOption> notificationEvents = Collections.emptySet();
        private DeliveryStatusNotification.ReturnOption notificationReturnOption;
        private String envelopeId;
        private boolean notificationOptionsConfigured;
        private boolean tlsRequiredForOnwardDelivery;
        private Boolean sendingToAcceptedRecipients;
        private Builder() { }
        private Builder(final SmtpDeliveryOptions options) {
            withDeliveryStatusNotification(options.deliveryStatusNotification);
            tlsRequiredForOnwardDelivery = options.tlsRequiredForOnwardDelivery;
            sendingToAcceptedRecipients = options.sendingToAcceptedRecipients;
        }
        /**
         * Sets the SMTP Delivery Status Notification (DSN) options for this email.
         * <p>
         * These options are passed to the SMTP transport as NOTIFY, RET and optionally ENVID parameters, never as message headers.
         * NOTIFY/RET-only requests are best-effort when the server does not support DSN. Supplying an envelope identifier requires DSN support:
         * the bundled Angus adapter refuses the send before MAIL FROM if support is unavailable. Calling this method replaces the complete DSN value.
         * The NOTIFY set is a fallback: explicit recipient/group preferences win, including when this Email value comes from Mailer overrides.
         * Clearing this value does not clear recipient preferences. Those require managed-provider support and usable DSN, unlike this best-effort fallback.
         * @see IRecipientBuilder#withDeliveryStatusNotificationNotifyOptions(DeliveryStatusNotification.NotifyOption...)
         *
         * @param deliveryStatusNotification DSN options, or {@code null} to clear them.
         *
         * @see #withDeliveryStatusNotification(DeliveryStatusNotification.ReturnOption, DeliveryStatusNotification.NotifyOption...)
         * @see #withDeliveryStatusNotificationNotifyOptions(DeliveryStatusNotification.NotifyOption...)
         * @see #withDeliveryStatusNotificationReturnOption(DeliveryStatusNotification.ReturnOption)
         */
        @NotNull public Builder withDeliveryStatusNotification(@Nullable DeliveryStatusNotification deliveryStatusNotification) { if (deliveryStatusNotification == null) return clearDeliveryStatusNotification(); notificationEvents = deliveryStatusNotification.getNotifyOptions(); notificationReturnOption = deliveryStatusNotification.getReturnOption(); envelopeId = deliveryStatusNotification.getEnvelopeId(); notificationOptionsConfigured = hasSharedNotificationPreference(); return this; }

        /**
         * Replaces the complete shared DSN request with the supplied return preference and events, removing any previous fixed envelope identifier.
         * This overload builds and validates that request immediately; at least one return preference or event must be supplied.
         * {@link DeliveryStatusNotification.NotifyOption#NEVER} must stand alone. The transport and recipient-precedence contract is described by
         * {@link #withDeliveryStatusNotification(DeliveryStatusNotification)}.
         *
         * @param returnOption How much original content a failure notification may return, or {@code null} for no supplied return preference.
         * @param notifyOptions Requested events, or an empty array when a return preference is supplied.
         * @throws IllegalArgumentException If no DSN option is supplied or NEVER is combined with other events.
         *
         * @see #withDeliveryStatusNotification(DeliveryStatusNotification)
         */
        @NotNull public Builder withDeliveryStatusNotification(@Nullable DeliveryStatusNotification.ReturnOption returnOption,
			@NotNull DeliveryStatusNotification.NotifyOption @NotNull ...notifyOptions) { return withDeliveryStatusNotification(DeliveryStatusNotification.of(returnOption, notifyOptions)); }

        /**
         * Replaces only the requested notification events, retaining any return preference and fixed envelope identifier.
         * {@link DeliveryStatusNotification.NotifyOption#NEVER} requests no notifications and must stand alone.
         * An empty array removes the events but requires another DSN component before the complete request is inspected or built;
         * use {@link #clearDeliveryStatusNotificationNotifyOptions()} to remove the last shared preference entirely.
         *
         * @param notifyOptions See {@link DeliveryStatusNotification.NotifyOption}.
         *
         * @see DeliveryStatusNotification.NotifyOption
         */
        @NotNull public Builder withDeliveryStatusNotification(@NotNull DeliveryStatusNotification.NotifyOption @NotNull ...notifyOptions) { return withDeliveryStatusNotificationNotifyOptions(notifyOptions); }

        /**
         * Chooses which delivery notifications to request, leaving any already configured return option and envelope identifier untouched.
         * These are later delivery notifications, not read receipts or the immediate send result. Server support does not guarantee a notification will arrive.
         * An empty array removes the events; at least one other DSN component must then remain when the complete request is inspected or built.
         * {@link #clearDeliveryStatusNotificationNotifyOptions()} instead allows the complete shared request to become absent.
         *
         * @param notifyOptions Requested events: {@code SUCCESS}, {@code FAILURE}, or {@code DELAY}, or {@code NEVER} alone.
         *                      The CLI accepts comma/semicolon-separated names, ignoring case.
         *
         * @see DeliveryStatusNotification.NotifyOption
         */
        @NotNull public Builder withDeliveryStatusNotificationNotifyOptions(@NotNull DeliveryStatusNotification.NotifyOption @NotNull ...notifyOptions) { notificationEvents = copyOf(Arrays.asList(requireNonNull(notifyOptions, "notifyOptions"))); notificationOptionsConfigured = true; return this; }

        /**
         * Chooses how much of the original email a delivery-failure notification may return, leaving notification events and the envelope identifier untouched.
         *
         * @param returnOption {@code FULL_MESSAGE} or {@code HEADERS_ONLY}, or {@code null} to clear the return preference in Java.
         *                     The CLI requires a value and also accepts {@code FULL} and {@code HDRS}, ignoring case.
         *
         * @see DeliveryStatusNotification.ReturnOption
         */
        @NotNull public Builder withDeliveryStatusNotificationReturnOption(@Nullable DeliveryStatusNotification.ReturnOption returnOption) { notificationReturnOption = returnOption; notificationOptionsConfigured = hasSharedNotificationPreference(); return this; }

        /**
         * Fixes the SMTP transaction identifier (ENVID), retaining the notification events and return option. A later DSN can return this value as
         * Original-Envelope-ID so the application can match it to this send. It is not the MIME Message-ID and does not change message bytes.
         * <p>
         * By default, the bundled Angus adapter generates a fresh UUID for each send on a DSN-capable connection, without modifying the Email.
         * If DSN is unavailable, it sends normally without ENVID. The effective identifier is available through
         * {@link org.simplejavamail.api.mailer.MailSubmissionReceipt#getEnvelopeId()}, including the receipt supplied to a mail-send observer.
         * <p>
         * Supply an unencoded, non-empty printable ASCII value. Spaces, plus signs and equals signs are encoded automatically; the encoded value must
         * fit in 94 characters (100 including {@code ENVID=}). The value is preserved exactly, without trimming or generating a fallback.
         * Copying or reusing an Email deliberately retains a fixed identifier; choose a distinct value for sends you need to distinguish, including retries.
         * <p>
         * With an identifier configured, the transport adapter must support ENVID and the actual send connection must advertise DSN.
         * Otherwise submission fails before MAIL FROM; the identifier is never silently omitted. This does not guarantee a later notification or final
         * mailbox delivery. Logging-only mode does not contact a server. A CustomMailer receives this value on the Email and owns its enforcement.
         * There is no global configuration-property default for a per-send identifier.
         *
         * The identifier is checked when this group is built or the complete DSN value is inspected. Empty, non-printable, non-ASCII or oversized encoded
         * identifiers cause {@link IllegalArgumentException} at that boundary, rather than when this individual choice is assigned.
         *
         * @param envelopeId The unencoded fixed identifier, or {@code null} to resume automatic generation while retaining other DSN options.
         * @return This builder.
         * @see #getDeliveryStatusNotification()
         * @see #withDeliveryStatusNotification(DeliveryStatusNotification)
         */
        @NotNull public Builder fixingEnvelopeId(@Nullable String envelopeId) { this.envelopeId = envelopeId; notificationOptionsConfigured = hasSharedNotificationPreference(); return this; }

        /**
         * Requires supporting mail servers to keep using TLS for this email's onward delivery. Normally, TLS protects only the connection from your
         * application to its SMTP server; onward delivery may still continue without TLS. With this requirement, a supporting server
         * must return the message as undeliverable rather than send it over an unencrypted connection.
         * <p>
         * The bundled Angus adapter applies the RFC 8689 {@code REQUIRETLS} option. It submits the email only when the current SMTP connection uses
         * authenticated TLS and the server confirms support; otherwise sending fails before the email is submitted. This protects transport between
         * cooperating mail servers, not message content stored on those servers, and cannot prove that every later server complied.
         * <p>
         * For composed Emails, defaults and overrides may configure this value. Clearing removes a value set directly on this builder and resumes that fallback; use
         * {@link EmailTemplateOptions.Builder#dontApplyDefaultValueFor(EmailProperty...)} or
         * {@link EmailTemplateOptions.Builder#dontApplyOverrideValueFor(EmailProperty...)} with {@link EmailProperty#TLS_REQUIRED_FOR_ONWARD_DELIVERY} to opt out of a template.
         * Exact Emails bypass ordinary templates. Applicable mandatory locks can still require onward TLS in either case.
         *
         * @return This builder.
         * @see org.simplejavamail.api.mailer.MailSubmissionReceipt#isRequireTlsUsed()
         */
        @NotNull public Builder withTlsRequiredForOnwardDelivery() { tlsRequiredForOnwardDelivery = true; return this; }

        /**
         * Chooses what to do when the SMTP server rejects one of this email's recipients. For example, if Alice and Bob are accepted but Carol is
         * rejected, {@code true} allows the content to be submitted for Alice and Bob; {@code false} withholds the content for everyone.
         * <p>
         * Continuing does not make the whole send successful: partial acceptance still throws a
         * {@link org.simplejavamail.api.mailer.MailSubmissionException}, or completes the async send exceptionally, with its receipt identifying who
         * was accepted and who was not. Inspect that receipt before deciding what to retry. If every recipient is rejected, no content is submitted.
         * Stopping after a known rejection is not an atomic-delivery guarantee and cannot prevent later bounces.
         * This setting never changes visible recipient headers, splits messages, retries or weakens validation or security requirements.
         * <p>
         * Calling this method again replaces the value set on this builder. When no value is set here, configured Email defaults/overrides (for composed Emails)
         * and existing provider settings apply. Exact Emails bypass ordinary templates. {@link #clearSendingToAcceptedRecipients()} removes this builder's
         * value so the applicable fallback can apply again. Template suppression uses
         * {@link EmailProperty#SENDING_TO_ACCEPTED_RECIPIENTS}; settings fixed by {@code simplejavamail.locked.*} properties cannot be suppressed.
         *
         * @param sendingToAcceptedRecipients {@code true} to continue for accepted recipients, or {@code false} to withhold content after a known rejection.
         * @return This builder.
         */
        @NotNull public Builder withSendingToAcceptedRecipients(boolean sendingToAcceptedRecipients) { this.sendingToAcceptedRecipients = sendingToAcceptedRecipients; return this; }

        /**
         * Removes the shared DSN value, including its fixed identifier; recipient-specific preferences remain independent.
         * @see #withDeliveryStatusNotification(DeliveryStatusNotification)
         */
        @NotNull public Builder clearDeliveryStatusNotification() { notificationEvents = Collections.emptySet(); notificationReturnOption = null; envelopeId = null; notificationOptionsConfigured = false; return this; }

        /**
         * Removes the shared notification-event preference while retaining the return option and fixed envelope identifier.
         * Recipient-specific preferences are unchanged. This restores absence rather than requesting NEVER.
         * If no shared DSN component remains, the request becomes absent and ordinary composed-Email templates can apply; exact Emails bypass them.
         * @return This builder.
         * @see #withDeliveryStatusNotificationNotifyOptions(DeliveryStatusNotification.NotifyOption...)
         */
        @NotNull public Builder clearDeliveryStatusNotificationNotifyOptions() { notificationEvents = Collections.emptySet(); notificationOptionsConfigured = hasSharedNotificationPreference(); return this; }

        /**
         * Removes only the return preference, retaining notification events and the fixed envelope identifier.
         * If no shared DSN component remains, the request becomes absent and ordinary composed-Email templates can apply; exact Emails bypass them.
         * Server or provider return preferences can still apply.
         * @return This builder.
         * @see #withDeliveryStatusNotificationReturnOption(DeliveryStatusNotification.ReturnOption)
         */
        @NotNull public Builder clearDeliveryStatusNotificationReturnOption() { notificationReturnOption = null; notificationOptionsConfigured = hasSharedNotificationPreference(); return this; }

        /**
         * Removes the fixed identifier, allowing automatic per-send generation where supported. Other notification options remain unchanged.
         * @return This builder.
         * @see #fixingEnvelopeId(String)
         */
        @NotNull public Builder clearEnvelopeId() { envelopeId = null; notificationOptionsConfigured = hasSharedNotificationPreference(); return this; }

        /**
         * Stops this builder from directly requiring TLS for this email's onward delivery. For composed Emails, configured defaults and overrides can still supply the
         * requirement; suppress the corresponding {@link EmailProperty#TLS_REQUIRED_FOR_ONWARD_DELIVERY} source to opt out of one of those templates.
         * Exact Emails bypass those ordinary templates; applicable mandatory locks still apply in both cases.
         *
         * @return This builder.
         * @see #withTlsRequiredForOnwardDelivery()
         */
        @NotNull public Builder clearTlsRequiredForOnwardDelivery() { tlsRequiredForOnwardDelivery = false; return this; }

        /**
         * Removes the value set by {@link #withSendingToAcceptedRecipients(boolean)}, allowing ordinary composed-Email templates and provider settings to apply.
         * Exact Emails bypass ordinary templates and retain provider fallback.
         * This is not an explicit {@code false} and cannot remove a setting fixed by {@code simplejavamail.locked.*} properties.
         *
         * @return This builder.
         * @see #withSendingToAcceptedRecipients(boolean)
         */
        @NotNull public Builder clearSendingToAcceptedRecipients() { sendingToAcceptedRecipients = null; return this; }

        /**
         * @return The complete shared request, or {@code null} when none remains; unlike component getters, this inspection validates the request and fixed identifier.
         * @throws IllegalArgumentException If a configured request has no remaining DSN component, or its fixed identifier is invalid.
         * @see #withDeliveryStatusNotification(DeliveryStatusNotification)
         */
        @Nullable public DeliveryStatusNotification getDeliveryStatusNotification() {
            if (!notificationOptionsConfigured && envelopeId == null) return null;
            return DeliveryStatusNotification.builder().notifyOptions(notificationEvents.toArray(new DeliveryStatusNotification.NotifyOption[0]))
                    .returnOption(notificationReturnOption).envelopeId(envelopeId).build();
        }
        /** @see #withDeliveryStatusNotificationNotifyOptions(DeliveryStatusNotification.NotifyOption...) */
        @NotNull public Set<DeliveryStatusNotification.NotifyOption> getDeliveryStatusNotificationNotifyOptions() {
            return new LinkedHashSet<>(notificationEvents);
        }
        /** @see #withDeliveryStatusNotificationReturnOption(DeliveryStatusNotification.ReturnOption) */
        @Nullable public DeliveryStatusNotification.ReturnOption getDeliveryStatusNotificationReturnOption() { return notificationReturnOption; }
        /** @see #fixingEnvelopeId(String) */
        @Nullable public String getEnvelopeId() { return envelopeId; }
        /** @see #withTlsRequiredForOnwardDelivery() */
        public boolean isTlsRequiredForOnwardDelivery() { return tlsRequiredForOnwardDelivery; }
        /**
         * @return The supplied recipient-rejection choice, or {@code null} when unset; templates and provider settings remain unresolved.
         * @see #withSendingToAcceptedRecipients(boolean)
         */
        @Nullable public Boolean getSendingToAcceptedRecipients() { return sendingToAcceptedRecipients; }
        private boolean hasSharedNotificationPreference() { return notificationReturnOption != null || !notificationEvents.isEmpty(); }
        /**
         * @return An immutable snapshot; a configured shared DSN request and any fixed ENVID are validated without contacting a server.
         * @throws IllegalArgumentException If a configured request has no remaining DSN component, or its fixed identifier is invalid.
         */
        @NotNull public SmtpDeliveryOptions build() { return new SmtpDeliveryOptions(this); }
    }
}
