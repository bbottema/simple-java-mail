package org.simplejavamail.converter.internal.mimemessage;

import lombok.Builder;
import lombok.Value;
import lombok.NonNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.DeliveryAction;
import org.simplejavamail.api.email.RecipientDeliveryReport;
import org.simplejavamail.api.email.DeliveryDiagnostic;
import org.simplejavamail.api.email.EnhancedStatusCode;
import org.simplejavamail.api.email.ReportedRecipient;

import java.time.Instant;

/** Immutable conversion-time facts; no MIME objects or streams survive assembly. */
@Value
@Builder
class RecipientDeliveryReportImpl implements RecipientDeliveryReport {

	private static final long serialVersionUID = 1L;

	@Nullable ReportedRecipient originalRecipient;
	@Nullable ReportedRecipient finalRecipient;
	@NonNull DeliveryAction action;
	@Nullable EnhancedStatusCode statusCode;
	@Nullable DeliveryDiagnostic diagnostic;
	@Nullable Instant lastAttemptAt;
	@Nullable Instant retryUntil;

	@Override
	public String toString() {
		return "RecipientDeliveryReport{action=" + action + ", status=" + statusCode + "}";
	}
}
