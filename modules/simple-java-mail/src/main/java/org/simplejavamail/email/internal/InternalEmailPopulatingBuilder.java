package org.simplejavamail.email.internal;

import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.DeliveryStatusReport;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.internal.clisupport.CliEmailRecipientBuilder;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * This interface is only there to improve readability there where internal builder API is used.
 */
@SuppressWarnings("UnusedReturnValue")
public interface InternalEmailPopulatingBuilder extends EmailPopulatingBuilder, CliEmailRecipientBuilder, org.simplejavamail.api.internal.clisupport.CliEmailConfigurationBuilder, org.simplejavamail.internal.email.EmailInspectionBuilder, org.simplejavamail.internal.email.HeaderCompositionBuilder {
	ConfidentialContentState getConfidentialContentState();
	void retainConfidentialContentState(ConfidentialContentState state);
	String getPublicSubject();
	void retainPublicSubject(String subject);
	void retainOriginalMessageProtection(org.simplejavamail.internal.email.OriginalMessageProtection protection);
	org.simplejavamail.internal.email.OriginalMessageProtection getOriginalMessageProtection();
	void retainExplicitHeaderNames(java.util.Set<String> names);
	void retainConfidentialHeaderNames(java.util.Set<String> names);
	void retainCapturedReceiptGroups(java.util.Set<String> names);
	void retainReceivedHeaders(Map<String, ? extends Collection<String>> headers);
	/** Keeps available received HTML only until the first build can determine whether configured resolution actually changes it. */
	void retainReceivedHtml(String value, List<org.simplejavamail.api.email.EmailSourcePart> originalParts);

	@Override
	@NotNull InternalEmailPopulatingBuilder withRecipients(@Nullable String name, boolean fixedName,
			@Nullable jakarta.mail.Message.RecipientType recipientType, @NotNull String @NotNull ... oneOrMoreAddressesEach);
	@Override
	@NotNull InternalEmailPopulatingBuilder withRecipients(@NotNull Recipient @NotNull ... recipients);
	@Override
	@NotNull InternalEmailPopulatingBuilder withRecipients(@NotNull Collection<Recipient> recipients);
	@NotNull InternalEmailPopulatingBuilder withForward(@Nullable MimeMessage emailMessageToForward);
	@NotNull InternalEmailPopulatingBuilder withDeliveryStatusReport(@Nullable DeliveryStatusReport deliveryStatusReport);

	/** Retains the exact request state when copying or governing existing snapshots, including legacy explicit false choices. */
	@NotNull InternalEmailPopulatingBuilder withDispositionNotificationToState(@Nullable Boolean requested, @Nullable Collection<Recipient> recipients);
	/** Retains the exact request state when copying or governing existing snapshots, including legacy explicit false choices. */
	@NotNull InternalEmailPopulatingBuilder withReturnReceiptToState(@Nullable Boolean requested, @Nullable Collection<Recipient> recipients);
}
