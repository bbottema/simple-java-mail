package org.simplejavamail.api.internal.clisupport;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.IRecipientBuilder;
import org.simplejavamail.api.email.IRecipientsBuilder;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.internal.clisupport.model.Cli;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.Set;

import static org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType.EMAIL;

/** Internal scalar adapter preserving existing CLI options while Java callers use grouped configuration. */
@Cli.BuilderApiNode(builderApiType = EMAIL)
public interface CliEmailConfigurationBuilder {

	/**
	 * Configures this email to ignore all default values when governance is applied.
	 *
	 * @return This builder.
	 * @see #ignoringDefaults(boolean)
	 * @see #dontApplyDefaultValueFor(EmailProperty...)
	 */
	@Cli.ExcludeApi(reason = "Convenience overload; CLI uses ignoringDefaults(boolean)")
	EmailPopulatingBuilder ignoringDefaults();

	/**
	 * Sets whether this email should ignore all default values when governance is applied.
	 *
	 * @param ignoreDefaults Whether to ignore all default values or not for this email.
	 *
	 * @see #dontApplyDefaultValueFor(EmailProperty...)
	 * @see org.simplejavamail.api.mailer.MailerRegularBuilder#withEmailDefaults(Email)
	 */
	@Cli.OptionNameOverride("ignoringDefaultsYesNo")
	EmailPopulatingBuilder ignoringDefaults(boolean ignoreDefaults);

	/**
	 * Configures this email to ignore all override values when governance is applied.
	 *
	 * @return This builder.
	 * @see #ignoringOverrides(boolean)
	 * @see #dontApplyOverrideValueFor(EmailProperty...)
	 */
	@Cli.ExcludeApi(reason = "Convenience overload; CLI uses ignoringOverrides(boolean)")
	EmailPopulatingBuilder ignoringOverrides();

	/**
	 * Sets whether this email should ignore all override values when governance is applied.
	 *
	 * @param ignoreOverrides Whether to ignore all override values or not for this email.
	 *
	 * @see #dontApplyOverrideValueFor(EmailProperty...)
	 * @see org.simplejavamail.api.mailer.MailerRegularBuilder#withEmailOverrides(Email)
	 */
	@Cli.OptionNameOverride("ignoringOverridesYesNo")
	EmailPopulatingBuilder ignoringOverrides(boolean ignoreOverrides);

	/**
	 * Allows you to prevent a property to be configured with default values. This might be useful if you have defined defaults (either through (system) properties,
	 * config files, or on mailer level on the {@link org.simplejavamail.api.mailer.config.EmailGovernance}), but for a specific case or under certain conditions
	 * you might want to have this email untouched by the defaults.
	 * Repeated calls add to the existing local exclusions; an empty call changes nothing. Use {@link #allowDefaultValueFor(EmailProperty...)}
	 * to remove individual exclusions or {@link #clearDefaultValueSuppressions()} to remove them all. These operations do not change
	 * {@link #ignoringDefaults(boolean)} or configuration locks.
	 * <br>
	 * <strong>Note:</strong> This is irrelevant for Email instances used to set on {@link org.simplejavamail.api.mailer.config.EmailGovernance}
	 * as defaults or overrides reference.
	 *
	 * @param configProperties The properties that should not be configured with default values, if any, when sending the email.
	 * @see #ignoringDefaults()
	 * @see #ignoringDefaults(boolean)
	 */
	EmailPopulatingBuilder dontApplyDefaultValueFor(@NotNull EmailProperty @NotNull ...configProperties);

	/**
	 * Allows you to prevent a property to be configured with override values. This might be useful if you have defined overrides on mailer level on the
	 * {@link org.simplejavamail.api.mailer.config.EmailGovernance}), but for a specific case or under certain conditions you might want
	 * to have this email untouched by the overrides.
	 * Repeated calls add to the existing local exclusions; an empty call changes nothing. Use {@link #allowOverrideValueFor(EmailProperty...)}
	 * to remove individual exclusions or {@link #clearOverrideValueSuppressions()} to remove them all. These operations do not change
	 * {@link #ignoringOverrides(boolean)} or configuration locks.
	 *
	 * @param configProperties The properties that should not be overridden when sending the email.
	 */
	EmailPopulatingBuilder dontApplyOverrideValueFor(@NotNull EmailProperty @NotNull ...configProperties);

	/**
	 * Removes the supplied properties from this builder's local default exclusions. Missing properties and an empty call change nothing.
	 * Global default suppression and configuration locks remain unchanged.
	 * @see #dontApplyDefaultValueFor(EmailProperty...)
	 */
	EmailPopulatingBuilder allowDefaultValueFor(@NotNull EmailProperty @NotNull ...configProperties);

	/**
	 * Removes the supplied properties from this builder's local override exclusions. Missing properties and an empty call change nothing.
	 * Global override suppression and configuration locks remain unchanged.
	 * @see #dontApplyOverrideValueFor(EmailProperty...)
	 */
	EmailPopulatingBuilder allowOverrideValueFor(@NotNull EmailProperty @NotNull ...configProperties);

	/**
	 * Removes all local default exclusions without changing global default suppression or configuration locks.
	 * @see #dontApplyDefaultValueFor(EmailProperty...)
	 */
	EmailPopulatingBuilder clearDefaultValueSuppressions();

	/**
	 * Removes all local override exclusions without changing global override suppression or configuration locks.
	 * @see #dontApplyOverrideValueFor(EmailProperty...)
	 */
	EmailPopulatingBuilder clearOverrideValueSuppressions();

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
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	EmailPopulatingBuilder withDeliveryStatusNotification(@Nullable @Cli.Optional DeliveryStatusNotification deliveryStatusNotification);

	/**
	 * Delegates to {@link #withDeliveryStatusNotification(DeliveryStatusNotification)}.
	 *
	 * @param returnOption See {@link DeliveryStatusNotification.ReturnOption}.
	 * @param notifyOptions See {@link DeliveryStatusNotification.NotifyOption}.
	 *
	 * @see #withDeliveryStatusNotification(DeliveryStatusNotification)
	 */
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	EmailPopulatingBuilder withDeliveryStatusNotification(@Nullable @Cli.Optional DeliveryStatusNotification.ReturnOption returnOption,
			@NotNull DeliveryStatusNotification.NotifyOption @NotNull ...notifyOptions);

	/**
	 * Delegates to {@link #withDeliveryStatusNotificationNotifyOptions(DeliveryStatusNotification.NotifyOption...)}.
	 *
	 * @param notifyOptions See {@link DeliveryStatusNotification.NotifyOption}.
	 *
	 * @see DeliveryStatusNotification.NotifyOption
	 */
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	EmailPopulatingBuilder withDeliveryStatusNotification(@NotNull DeliveryStatusNotification.NotifyOption @NotNull ...notifyOptions);

	/**
	 * Chooses which delivery notifications to request, leaving any already configured return option and envelope identifier untouched.
	 * These are later delivery notifications, not read receipts or the immediate send result. Server support does not guarantee a notification will arrive.
	 *
	 * @param notifyOptions Requested events: {@code SUCCESS}, {@code FAILURE}, or {@code DELAY}, or {@code NEVER} alone.
	 *                      The CLI accepts comma/semicolon-separated names, ignoring case.
	 *
	 * @see DeliveryStatusNotification.NotifyOption
	 */
	EmailPopulatingBuilder withDeliveryStatusNotificationNotifyOptions(@NotNull DeliveryStatusNotification.NotifyOption @NotNull ...notifyOptions);

	/**
	 * Chooses how much of the original email a delivery-failure notification may return, leaving notification events and the envelope identifier untouched.
	 *
	 * @param returnOption {@code FULL_MESSAGE} or {@code HEADERS_ONLY}, or {@code null} to clear the return preference in Java.
	 *                     The CLI requires a value and also accepts {@code FULL} and {@code HDRS}, ignoring case.
	 *
	 * @see DeliveryStatusNotification.ReturnOption
	 */
	EmailPopulatingBuilder withDeliveryStatusNotificationReturnOption(@Nullable DeliveryStatusNotification.ReturnOption returnOption);

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
	 * @param envelopeId The unencoded fixed identifier, or {@code null} to resume automatic generation while retaining other DSN options.
	 * @return This builder.
	 * @throws IllegalArgumentException If the identifier is empty, contains non-printable/non-ASCII characters, or exceeds the encoded limit.
	 * @see #withDeliveryStatusNotification(DeliveryStatusNotification)
	 */
	EmailPopulatingBuilder fixingEnvelopeId(@Nullable @Cli.Optional String envelopeId);

	/**
	 * Requires supporting mail servers to keep using TLS for this email's onward delivery. Normally, TLS protects only the connection from your
	 * application to its SMTP server; onward delivery may still continue without TLS. With this requirement, a supporting server
	 * must return the message as undeliverable rather than send it over an unencrypted connection.
	 * <p>
	 * The bundled Angus adapter applies the RFC 8689 {@code REQUIRETLS} option. It submits the email only when the current SMTP connection uses
	 * authenticated TLS and the server confirms support; otherwise sending fails before the email is submitted. This protects transport between
	 * cooperating mail servers, not message content stored on those servers, and cannot prove that every later server complied.
	 * <p>
	 * Email defaults and overrides may configure this value. Clearing removes a value set directly on this builder and resumes that fallback; use
	 * {@link #dontApplyDefaultValueFor(EmailProperty...)} or
	 * {@link #dontApplyOverrideValueFor(EmailProperty...)} with {@link EmailProperty#TLS_REQUIRED_FOR_ONWARD_DELIVERY} to opt out of a template.
	 *
	 * @return This builder.
	 * @see org.simplejavamail.api.mailer.MailSubmissionReceipt#isRequireTlsUsed()
	 */
	EmailPopulatingBuilder withTlsRequiredForOnwardDelivery();

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
	 * Calling this method again replaces the value set on this builder. When no value is set here, configured Email defaults/overrides and existing
	 * provider settings apply. {@link #clearSendingToAcceptedRecipients()} removes this builder's value so they can apply again. Template suppression uses
	 * {@link EmailProperty#SENDING_TO_ACCEPTED_RECIPIENTS}; settings fixed by {@code simplejavamail.locked.*} properties cannot be suppressed.
	 *
	 * @param sendingToAcceptedRecipients {@code true} to continue for accepted recipients, or {@code false} to withhold content after a known rejection.
	 * @return This builder.
	 */
	EmailPopulatingBuilder withSendingToAcceptedRecipients(boolean sendingToAcceptedRecipients);

	/**
	 * Determines what encoding is applied to text/html/iCalendar content in the MimeMessage/EML. Default is {@link ContentTransferEncoding#QUOTED_PRINTABLE}, which basically means plain
	 * text, so you can just read the content of the EML (if not encrypted). This acts as the shared fallback for all body parts unless a body-specific content transfer encoding is set.
	 * <p>
	 * However, you can choose another encoding as supported by Jakarta Mail. The list is quite extensive, but the most common alternative is base64. This might be useful for example for obfuscating
	 * the content to some extent.
	 *
	 * @param contentTransferEncoding The encoder to use for the text/html/iCalendar content.
	 * @see #withPlainTextContentTransferEncoding(ContentTransferEncoding)
	 * @see #withHTMLTextContentTransferEncoding(ContentTransferEncoding)
	 * @see #withCalendarTextContentTransferEncoding(ContentTransferEncoding)
	 */
	EmailPopulatingBuilder withContentTransferEncoding(@NotNull ContentTransferEncoding contentTransferEncoding);

	/**
	 * Determines what encoding is applied to the plain text body part in the MimeMessage/EML. If unset,
	 * {@link #withContentTransferEncoding(ContentTransferEncoding)} is used as fallback.
	 *
	 * @param contentTransferEncoding The encoder to use for the plain text content.
	 */
	EmailPopulatingBuilder withPlainTextContentTransferEncoding(@NotNull ContentTransferEncoding contentTransferEncoding);

	/**
	 * Determines what encoding is applied to the HTML body part in the MimeMessage/EML. If unset,
	 * {@link #withContentTransferEncoding(ContentTransferEncoding)} is used as fallback.
	 *
	 * @param contentTransferEncoding The encoder to use for the HTML content.
	 */
	EmailPopulatingBuilder withHTMLTextContentTransferEncoding(@NotNull ContentTransferEncoding contentTransferEncoding);

	/**
	 * Determines what encoding is applied to the iCalendar body part in the MimeMessage/EML. If unset,
	 * {@link #withContentTransferEncoding(ContentTransferEncoding)} is used as fallback.
	 *
	 * @param contentTransferEncoding The encoder to use for the iCalendar content.
	 */
	EmailPopulatingBuilder withCalendarTextContentTransferEncoding(@NotNull ContentTransferEncoding contentTransferEncoding);

	/**
	 * Enables auto resolution of file datasources for embedded images.
	 * <p>
	 * Normally, you would manually mark up your HTML with images using {@code cid:<some_id>} and then add an embedded image
	 * resource with the same name ({@code emailBuilder.withEmbeddedImage(..)}). With auto-file-resolution, you can just
	 * refer to the file instead and the data will be included dynamically with a generated <em>cid</em>.
	 * <p>
	 * This option is disabled by default. Enabling it lets HTML image references read from the filesystem. When the HTML is not fully trusted, set
	 * {@link #withEmbeddedImageBaseDir(String)} and leave {@link #allowingEmbeddedImageOutsideBaseDir(boolean)} disabled.
	 *
	 * @param embeddedImageAutoResolutionForFiles Enables auto resolution of file datasources for embedded images.
	 *
	 * @see #withEmbeddedImageBaseDir(String)
	 * @see #allowingEmbeddedImageOutsideBaseDir(boolean)
	 */
	EmailPopulatingBuilder withEmbeddedImageAutoResolutionForFiles(final boolean embeddedImageAutoResolutionForFiles);

	/**
	 * Enables auto resolution of classpath datasources for embedded images.
	 * <p>
	 * Normally, you would manually mark up your HTML with images using {@code cid:<some_id>} and then add an embedded image
	 * resource with the same name ({@code emailBuilder.withEmbeddedImage(..)}). With auto-classpath-resolution, you can just
	 * refer to the resource on the classpath instead and the data will be included dynamically with a generated <em>cid</em>.
	 * <p>
	 * This option is disabled by default. Enabling it lets HTML image references read classpath resources. When the HTML is not fully trusted, set
	 * {@link #withEmbeddedImageBaseClassPath(String)} and leave {@link #allowingEmbeddedImageOutsideBaseClassPath(boolean)} disabled.
	 *
	 * @param embeddedImageAutoResolutionForClassPathResources Enables auto resolution of classpath datasources for embedded images.
	 *
	 * @see #withEmbeddedImageBaseClassPath(String)
	 * @see #allowingEmbeddedImageOutsideBaseClassPath(boolean)
	 */
	EmailPopulatingBuilder withEmbeddedImageAutoResolutionForClassPathResources(final boolean embeddedImageAutoResolutionForClassPathResources);

	/**
	 * Enables auto resolution of URL's for embedded images.
	 * <p>
	 * Normally, you would manually mark up your HTML with images using {@code cid:<some_id>} and then add an embedded image
	 * resource with the same name ({@code emailBuilder.withEmbeddedImage(..)}). With auto-URL-resolution, you can just
	 * refer to the hosted image instead and the data will be downloaded and included dynamically with a generated <em>cid</em>.
	 * <p>
	 * This option is disabled by default. Enabling it lets HTML image references make network requests. When the HTML is not fully trusted, set
	 * {@link #withEmbeddedImageBaseUrl(URL)} and leave {@link #allowingEmbeddedImageOutsideBaseUrl(boolean)} disabled.
	 *
	 * @param embeddedImageAutoResolutionForURLs Enables auto resolution of URL's for embedded images.
	 *
	 * @see #withEmbeddedImageBaseUrl(String)
	 * @see #withEmbeddedImageBaseUrl(URL)
	 * @see #allowingEmbeddedImageOutsideBaseUrl(boolean)
	 */
	EmailPopulatingBuilder withEmbeddedImageAutoResolutionForURLs(final boolean embeddedImageAutoResolutionForURLs);

	/**
	 * Sets the base folder used when resolving image sources in HTML text.
	 * <p>
	 * By default, a resolved file must remain below this directory after normalizing the path and following symbolic links. Without a base directory,
	 * file resolution is unrestricted and may use absolute paths or paths relative to the working directory.
	 *
	 * @param embeddedImageBaseDir The base folder used when resolving images sources in HTML text.
	 *
	 * @see #withEmbeddedImageAutoResolutionForFiles(boolean)
	 * @see #allowingEmbeddedImageOutsideBaseDir(boolean)
	 */
	EmailPopulatingBuilder withEmbeddedImageBaseDir(@NotNull final String embeddedImageBaseDir);

	/**
	 * Sets the classpath base used when resolving image sources in HTML text.
	 * <p>
	 * By default, normalized resource paths must remain below this classpath location. Without a classpath base, classpath resolution accepts absolute
	 * classpath resource names without a containment boundary.
	 *
	 * @param embeddedImageBaseClassPath The classpath base used when resolving images sources in HTML text.
	 *
	 * @see #withEmbeddedImageAutoResolutionForClassPathResources(boolean)
	 * @see #allowingEmbeddedImageOutsideBaseClassPath(boolean)
	 */
	EmailPopulatingBuilder withEmbeddedImageBaseClassPath(@NotNull final String embeddedImageBaseClassPath);

	/**
	 * Delegates to {@link #withEmbeddedImageBaseUrl(URL)}.
	 *
	 * @param embeddedImageBaseUrl The base URL used when resolving images sources in HTML text.
	 *
	 * @see #withEmbeddedImageAutoResolutionForURLs(boolean)
	 * @see #allowingEmbeddedImageOutsideBaseUrl(boolean)
	 */
	EmailPopulatingBuilder withEmbeddedImageBaseUrl(@NotNull final String embeddedImageBaseUrl);

	/**
	 * Sets the base URL used when resolving image sources in HTML text.
	 * <p>
	 * By default, resolved URLs and every HTTP redirect must use the same scheme, host and effective port as this URL, and their normalized path must
	 * remain below its path. Without a base URL, URL resolution accepts absolute URLs without a containment boundary.
	 *
	 * @param embeddedImageBaseUrl The base URL used when resolving images sources in HTML text.
	 *
	 * @see #withEmbeddedImageAutoResolutionForURLs(boolean)
	 * @see #allowingEmbeddedImageOutsideBaseUrl(boolean)
	 */
	@Cli.ExcludeApi(reason = "delegated method is an identical api from CLI point of view")
	EmailPopulatingBuilder withEmbeddedImageBaseUrl(@NotNull final URL embeddedImageBaseUrl);

	/**
	 * Allows embedded-image file resolution outside the configured base directory. Disabled by default.
	 * <p>
	 * Enabling this deliberately removes the filesystem containment boundary and is unsafe for untrusted HTML.
	 *
	 * @param allowEmbeddedImageOutsideBaseDir Whether files should be resolved that reside outside the baseDir (if set)
	 *
	 * @see #withEmbeddedImageAutoResolutionForFiles(boolean)
	 * @see #withEmbeddedImageBaseDir(String)
	 */
	EmailPopulatingBuilder allowingEmbeddedImageOutsideBaseDir(final boolean allowEmbeddedImageOutsideBaseDir);

	/**
	 * Allows embedded-image classpath resolution outside the configured classpath base. Disabled by default.
	 * <p>
	 * Enabling this deliberately removes the classpath containment boundary and is unsafe for untrusted HTML.
	 *
	 * @param allowEmbeddedImageOutsideBaseClassPath Whether image sources should be resolved that reside outside the baseClassPath (if set)
	 *
	 * @see #withEmbeddedImageAutoResolutionForClassPathResources(boolean)
	 * @see #withEmbeddedImageBaseClassPath(String)
	 */
	EmailPopulatingBuilder allowingEmbeddedImageOutsideBaseClassPath(final boolean allowEmbeddedImageOutsideBaseClassPath);

	/**
	 * Allows embedded-image URL resolution outside the configured base URL. Disabled by default.
	 * <p>
	 * Enabling this deliberately removes the URL origin and path boundary and is unsafe for untrusted HTML.
	 *
	 * @param allowEmbeddedImageOutsideBaseUrl Whether url's should be resolved that reside outside the baseUrl (if set)
	 *
	 * @see #withEmbeddedImageAutoResolutionForURLs(boolean)
	 * @see #withEmbeddedImageBaseUrl(String)
	 * @see #withEmbeddedImageBaseUrl(URL)
	 */
	EmailPopulatingBuilder allowingEmbeddedImageOutsideBaseUrl(final boolean allowEmbeddedImageOutsideBaseUrl);

	/**
	 * When embedded image auto resolution is enabled, this option will make sure unresolved images sources result in an exception.
	 * <p>
	 * Not using this option effectively means a more lenient approach to image sources.
	 * References blocked by a configured base boundary count as unresolved.
	 * <p>
	 * Note: It also allows you to work with URL's as image sources that can't be resolved at time of sending, but that makes sense
	 * when viewing the email in some client (e.g. relative url's).
	 *
	 * @param embeddedImageAutoResolutionMustBeSuccesful Whether auto resolution is enforced and bubbles up failure to do so.
	 */
	EmailPopulatingBuilder embeddedImageAutoResolutionMustBeSuccesful(final boolean embeddedImageAutoResolutionMustBeSuccesful);

	/**
	 * Resets <em>deliveryStatusNotification</em> to empty.
	 */
	@SuppressWarnings("unused")
	EmailPopulatingBuilder clearDeliveryStatusNotification();

	/**
	 * Removes the shared notification-event preference while retaining the return option and fixed envelope identifier.
	 * Recipient-specific preferences are unchanged. If no shared DSN component remains, the request becomes absent and ordinary templates can apply.
	 * @return This builder.
	 * @see #withDeliveryStatusNotificationNotifyOptions(DeliveryStatusNotification.NotifyOption...)
	 */
	@Cli.ExcludeApi(reason = "A fresh CLI builder has no shared notification preference to clear")
	EmailPopulatingBuilder clearDeliveryStatusNotificationNotifyOptions();

	/**
	 * Removes only the return preference, retaining notification events and the fixed envelope identifier.
	 * If no shared DSN component remains, the request becomes absent and ordinary templates can apply.
	 * @return This builder.
	 * @see #withDeliveryStatusNotificationReturnOption(DeliveryStatusNotification.ReturnOption)
	 */
	@Cli.ExcludeApi(reason = "A fresh CLI builder has no notification return preference to clear")
	EmailPopulatingBuilder clearDeliveryStatusNotificationReturnOption();

	/**
	 * Removes the fixed identifier, allowing automatic per-send generation where supported. Other notification options remain unchanged.
	 * @return This builder.
	 * @see #fixingEnvelopeId(String)
	 */
	@Cli.ExcludeApi(reason = "A fresh CLI builder has no fixed envelope identifier to clear")
	EmailPopulatingBuilder clearEnvelopeId();

	/**
	 * Stops this builder from directly requiring TLS for this email's onward delivery. Configured Email defaults and overrides can still supply the
	 * requirement; suppress the corresponding {@link EmailProperty#TLS_REQUIRED_FOR_ONWARD_DELIVERY} source to opt out of one of those templates.
	 *
	 * @return This builder.
	 * @see #withTlsRequiredForOnwardDelivery()
	 */
	EmailPopulatingBuilder clearTlsRequiredForOnwardDelivery();

	/**
	 * Removes the value set by {@link #withSendingToAcceptedRecipients(boolean)}, allowing configured Email defaults/overrides and provider settings to apply.
	 * This is not an explicit {@code false} and cannot remove a setting fixed by {@code simplejavamail.locked.*} properties.
	 *
	 * @return This builder.
	 * @see #withSendingToAcceptedRecipients(boolean)
	 */
	@Cli.ExcludeApi(reason = "A fresh CLI Email builder has no recipient-rejection choice to clear")
	EmailPopulatingBuilder clearSendingToAcceptedRecipients();

	/**
	 * Resets <em>contentTransferEncoding</em> to {@link ContentTransferEncoding#QUOTED_PRINTABLE}.
	 */
	EmailPopulatingBuilder clearContentTransferEncoding();

	/**
	 * Resets <em>plainTextContentTransferEncoding</em> to empty, falling back to <em>contentTransferEncoding</em>.
	 */
	EmailPopulatingBuilder clearPlainTextContentTransferEncoding();

	/**
	 * Resets <em>htmlTextContentTransferEncoding</em> to empty, falling back to <em>contentTransferEncoding</em>.
	 */
	EmailPopulatingBuilder clearHTMLTextContentTransferEncoding();

	/**
	 * Resets <em>calendarTextContentTransferEncoding</em> to empty, falling back to <em>contentTransferEncoding</em>.
	 */
	EmailPopulatingBuilder clearCalendarTextContentTransferEncoding();

	/**
	 * Resets base dir for embedded images to {@code null}.
	 */
	EmailPopulatingBuilder clearEmbeddedImageBaseDir();

	/**
	 * Resets classpath base for embedded images to {@code null}.
	 */
	EmailPopulatingBuilder clearEmbeddedImageBaseClassPath();

	/**
	 * Resets base URL for embedded images to {@code null}.
	 */
	EmailPopulatingBuilder clearEmbeddedImageBaseUrl();

	/**
	 * @see #ignoringDefaults(boolean)
	 */
	boolean isIgnoreDefaults();

	/**
	 * @see #ignoringOverrides(boolean)
	 */
	boolean isIgnoreOverrides();

	/**
	 * @see #dontApplyDefaultValueFor(EmailProperty...)
	 */
	@Nullable
	Set<EmailProperty> getPropertiesNotToApplyDefaultValueFor();

	/**
	 * @see #dontApplyOverrideValueFor(EmailProperty...)
	 */
	@Nullable
	Set<EmailProperty> getPropertiesNotToApplyOverrideValueFor();

	/**
	 * @see #withDeliveryStatusNotification(DeliveryStatusNotification)
	 */
	@Nullable
	DeliveryStatusNotification getDeliveryStatusNotification();

	/**
	 * @return Whether this builder requires RFC 8689 REQUIRETLS for onward delivery.
	 * @see #withTlsRequiredForOnwardDelivery()
	 */
	boolean isTlsRequiredForOnwardDelivery();

	/**
	 * @return The recipient-rejection setting on this builder, or {@code null} when unset; defaults and overrides are resolved during preparation.
	 * @see #withSendingToAcceptedRecipients(boolean)
	 */
	@Nullable
	Boolean getSendingToAcceptedRecipients();

	/**
	 * @see #withContentTransferEncoding(ContentTransferEncoding)
	 * @see #clearContentTransferEncoding()
	 */
	@Nullable
	ContentTransferEncoding getContentTransferEncoding();

	/**
	 * @see #withPlainTextContentTransferEncoding(ContentTransferEncoding)
	 * @see #clearPlainTextContentTransferEncoding()
	 */
	@Nullable
	ContentTransferEncoding getPlainTextContentTransferEncoding();

	/**
	 * @see #withHTMLTextContentTransferEncoding(ContentTransferEncoding)
	 * @see #clearHTMLTextContentTransferEncoding()
	 */
	@Nullable
	ContentTransferEncoding getHTMLTextContentTransferEncoding();

	/**
	 * @see #withCalendarTextContentTransferEncoding(ContentTransferEncoding)
	 * @see #clearCalendarTextContentTransferEncoding()
	 */
	@Nullable
	ContentTransferEncoding getCalendarTextContentTransferEncoding();

	/**
	 * Primes this email for signing with a DKIM domain key. Actual signing is done when sending using a <code>Mailer</code>.
	 * <p>
	 * <strong>Note:</strong> this only works in combination with the {@value org.simplejavamail.internal.modules.DKIMModule#NAME}.
	 * <p>
	 * <strong>Note:</strong> for more fine-grained control over the DKIM signing process, use {@link EmailPopulatingBuilder#signWithDomainKey(DkimConfig)} instead.
	 *
	 * @param dkimPrivateKey                            De key content used to sign for the sending party.
	 * @param signingDomain                             The domain being authorized to send.
	 * @param dkimSelector                              Additional domain specifier.
	 * @param excludedHeadersFromDkimDefaultSigningList Headers that a known downstream relay rewrites, such as {@code Message-ID} or {@code Date}.
	 *                                                 The DKIM module rejects exclusion of {@code From} when signing, since that header must be covered.
	 *
	 * @see <a href="https://postmarkapp.com/guides/dkim">more on DKIM 1</a>
	 * @see <a href="https://github.com/markenwerk/java-utils-mail-dkim">more on DKIM 2</a>
	 * @see <a href="http://www.gettingemaildelivered.com/dkim-explained-how-to-set-up-and-use-domainkeys-identified-mail-effectively">more on DKIM 3</a>
	 * @see <a href="https://en.wikipedia.org/wiki/DomainKeys_Identified_Mail">more on DKIM 4</a>
	 * @see <a href="https://github.com/bbottema/dkim-verify">List of explanations all tags in a <em>DKIM-Signature</em> header</a>
	 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
	 */
	@SuppressWarnings("unused")
	EmailPopulatingBuilder signWithDomainKey(byte@NotNull[] dkimPrivateKey, @NotNull String signingDomain, @NotNull String dkimSelector, @Nullable @Cli.Optional Set<String> excludedHeadersFromDkimDefaultSigningList);

	/**
	 * Delegates to {@link EmailPopulatingBuilder#signWithSmime(SmimeSigningConfig)}.
	 * <p>
	 * <strong>Note:</strong> this only works in combination with the {@value org.simplejavamail.internal.modules.SMIMEModule#NAME}.
	 *
	 * @param pkcs12StoreFile The key store file to use to find the indicated key
	 * @param storePassword The store's password
     * @param keyAlias The name of the certificate in the key store to use
     * @param keyPassword The password of the certificate
	 * @param signatureAlgorithm The signature algorithm to use for signing. If omitted, the default algorithm is used.
	 *                           See {@link SmimeSigningConfig} for details.
	 *
     * @see SmimeSigningConfig
     */
	EmailPopulatingBuilder signWithSmime(@NotNull File pkcs12StoreFile, @NotNull String storePassword, @NotNull String keyAlias, @NotNull String keyPassword, @Nullable @Cli.Optional String signatureAlgorithm);

	/**
	 * Delegates to {@link EmailPopulatingBuilder#signWithSmime(SmimeSigningConfig)} after reading and closing the PKCS12 store at the supplied path.
	 *
	 * @param pkcs12StorePath The key store path to use to find the indicated key.
	 * @param storePassword The store's password.
	 * @param keyAlias The name of the certificate in the key store to use.
	 * @param keyPassword The password of the certificate.
	 * @param signatureAlgorithm The signature algorithm to use for signing, or {@code null} for the default.
	 * @return This builder.
	 */
	@Cli.ExcludeApi(reason = "The File overload already provides the same CLI file conversion")
	EmailPopulatingBuilder signWithSmime(@NotNull Path pkcs12StorePath, @NotNull String storePassword, @NotNull String keyAlias,
			@NotNull String keyPassword, @Nullable @Cli.Optional String signatureAlgorithm);

	/**
	 * Delegates to {@link EmailPopulatingBuilder#encryptWithSmime(SmimeEncryptionConfig)} using the provided PEM file.
	 * <p>
	 * <strong>Note:</strong> this only works in combination with the {@value org.simplejavamail.internal.modules.SMIMEModule#NAME}.
	 *
	 * @param pemFile The recipient's public key to use for encryption.
	 * @param keyEncapsulationAlgorithm The key encapsulation algorithm to use for encryption. See {@link SmimeEncryptionConfig} for available values.
	 * @param cipherAlgorithm The cipher algorithm to use for encryption. See {@link SmimeEncryptionConfig} for available values.
	 *
	 * @see SmimeEncryptionConfig
	 */
	EmailPopulatingBuilder encryptWithSmime(@NotNull File pemFile, @Nullable @Cli.Optional String keyEncapsulationAlgorithm, @Nullable @Cli.Optional String cipherAlgorithm);

	/**
	 * Delegates to {@link EmailPopulatingBuilder#encryptWithSmime(SmimeEncryptionConfig)} after reading and closing the PEM certificate at the supplied path.
	 *
	 * @param pemPath The recipient's public certificate path to use for encryption.
	 * @param keyEncapsulationAlgorithm The key encapsulation algorithm to use for encryption.
	 * @param cipherAlgorithm The cipher algorithm to use for encryption.
	 * @return This builder.
	 */
	@Cli.ExcludeApi(reason = "The File overload already provides the same CLI file conversion")
	EmailPopulatingBuilder encryptWithSmime(@NotNull Path pemPath, @Nullable @Cli.Optional String keyEncapsulationAlgorithm,
			@Nullable @Cli.Optional String cipherAlgorithm);
}
