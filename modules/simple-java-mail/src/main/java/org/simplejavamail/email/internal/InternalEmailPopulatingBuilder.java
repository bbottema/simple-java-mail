package org.simplejavamail.email.internal;

import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.DeliveryStatusReport;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.internal.clisupport.CliEmailRecipientBuilder;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * This interface is only there to improve readability there where internal builder API is used.
 */
@SuppressWarnings("UnusedReturnValue")
public interface InternalEmailPopulatingBuilder extends EmailPopulatingBuilder, CliEmailRecipientBuilder, org.simplejavamail.api.internal.clisupport.CliEmailConfigurationBuilder, org.simplejavamail.internal.email.EmailInspectionBuilder {
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
	@NotNull <T> InternalEmailPopulatingBuilder withHeaders(@NotNull Map<String, ? extends Collection<? extends T>> headers, boolean ignoreSmimeMessageId);
	@NotNull InternalEmailPopulatingBuilder clearDecryptedAttachments();
	@NotNull InternalEmailPopulatingBuilder withDecryptedAttachments(List<AttachmentResource> decryptedAttachments);
	@NotNull InternalEmailPopulatingBuilder withSmimeSignedEmail(@NotNull Email smimeSignedEmail);
	@NotNull InternalEmailPopulatingBuilder withOriginalSmimeDetails(@NotNull OriginalSmimeDetails originalSmimeDetails);
	@NotNull InternalEmailPopulatingBuilder withOriginalOpenPgpDetails(@NotNull OriginalOpenPgpDetails originalOpenPgpDetails);
	@NotNull InternalEmailPopulatingBuilder withDeliveryStatusReport(@Nullable DeliveryStatusReport deliveryStatusReport);

	/** Retains the exact request state when copying or governing existing snapshots, including legacy explicit false choices. */
	@NotNull InternalEmailPopulatingBuilder withDispositionNotificationToState(@Nullable Boolean requested, @Nullable Collection<Recipient> recipients);
	/** Retains the exact request state when copying or governing existing snapshots, including legacy explicit false choices. */
	@NotNull InternalEmailPopulatingBuilder withReturnReceiptToState(@Nullable Boolean requested, @Nullable Collection<Recipient> recipients);
}
