package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.List;

/**
 * Delivery feedback extracted from a received notification Email. Convert MIME/EML through the usual converter and read
 * {@link Email#getDeliveryStatusReport()}; applications do not construct these snapshots.
 *
 * <p>These are the received server's claims, not authenticated proof, a read receipt or instructions to resend. Correlation and business decisions
 * remain application-owned. A missing notification does not establish delivery. Optional facts are absent when missing or unusable; warnings explain
 * malformed recognized reports without discarding their usable facts. Unambiguous formatting can be recovered with a warning; if optional
 * report reading stops, completed facts remain available with an incomplete-extraction warning. Required message-content reads can still fail conversion.</p>
 */
public interface DeliveryStatusReport extends Serializable {

	/** The original submission's envelope identifier, when reported. It is separate from Message-ID and is not matched to application records automatically. */
	@Nullable String getEnvelopeId();

	/** The reporting server and its naming format together, or {@code null} when missing or unusable. */
	@Nullable ReportedMailServer getReportingServer();

	/** Recipient reports in their received order, including duplicate occurrences. The returned list is immutable and never {@code null}. */
	@NotNull List<RecipientDeliveryReport> getRecipients();

	/** Human-readable parsing cautions, not machine-readable error codes or a protocol transcript. The returned list is immutable and never {@code null}. */
	@NotNull List<String> getWarnings();

	/** Summarizes recipient actions and warning counts without identifiers, addresses or diagnostic text. */
	@Override
	@NotNull String toString();
}
