package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.time.Instant;

/**
 * Delivery feedback for one recipient occurrence. Addresses and diagnostics retain their reported types;
 * they are not necessarily Internet mailboxes or SMTP replies.
 */
public interface RecipientDeliveryReport extends Serializable {

	/** The original recipient and its address format, when supplied by the delivery route. Case and spelling are preserved. */
	@Nullable ReportedRecipient getOriginalRecipient();

	/** The final recipient and its address format. It may differ from the original recipient or be unavailable in a malformed report. */
	@Nullable ReportedRecipient getFinalRecipient();

	/** What the server says happened. A delayed report and a later failure can share a status code; do not infer this action from that code. */
	@NotNull DeliveryAction getAction();

	/** The reported enhanced status, such as {@code 4.4.1}, or {@code null} when missing, invalid or contradictory. */
	@Nullable EnhancedStatusCode getStatusCode();

	/** The server's explanation and its format together, when reported. Automatic rendering does not include its text. */
	@Nullable DeliveryDiagnostic getDiagnostic();

	/** The reported last delivery-attempt time, or {@code null} when missing or invalid. */
	@Nullable Instant getLastAttemptAt();

	/** When the reporting server says it will stop trying, if supplied. This is not an application retry schedule. */
	@Nullable Instant getRetryUntil();

	/** Summarizes the action and status without addresses or diagnostic text. */
	@Override
	@NotNull String toString();
}
