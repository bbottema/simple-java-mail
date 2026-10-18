package org.simplejavamail.converter.internal.mimemessage;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.DeliveryAction;
import org.simplejavamail.api.email.DeliveryStatusReport;
import org.simplejavamail.api.email.RecipientDeliveryReport;
import org.simplejavamail.api.email.ReportedMailServer;

import java.util.List;

/** Library-owned source snapshot. Collections are detached from the parsing attempt. */
@Getter
@EqualsAndHashCode
final class DeliveryStatusReportImpl implements DeliveryStatusReport {

	private static final long serialVersionUID = 1L;

	@Nullable private final String envelopeId;
	@Nullable private final ReportedMailServer reportingServer;
	private final List<RecipientDeliveryReport> recipients;
	private final List<String> warnings;

	@Builder
	private DeliveryStatusReportImpl(@Nullable final String envelopeId, @Nullable final ReportedMailServer reportingServer,
			final List<RecipientDeliveryReport> recipients, final List<String> warnings) {
		this.envelopeId = envelopeId;
		this.reportingServer = reportingServer;
		this.recipients = List.copyOf(recipients);
		this.warnings = List.copyOf(warnings);
	}

	@Override
	public String toString() {
		final StringBuilder summary = new StringBuilder("DeliveryStatusReport{recipients=").append(recipients.size());
		for (final DeliveryAction action : DeliveryAction.values()) {
			final long count = recipients.stream().filter(recipient -> recipient.getAction() == action).count();
			if (count != 0) {
				summary.append(", ").append(action.name().toLowerCase(java.util.Locale.ROOT)).append('=').append(count);
			}
		}
		return summary.append(", warnings=").append(warnings.size()).append('}').toString();
	}
}
