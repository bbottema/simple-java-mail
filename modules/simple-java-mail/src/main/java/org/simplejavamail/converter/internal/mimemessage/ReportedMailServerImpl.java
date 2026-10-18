package org.simplejavamail.converter.internal.mimemessage;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import org.simplejavamail.api.email.ReportedMailServer;

/** Reported naming data, not a resolved connection endpoint. */
@Value
@Builder
class ReportedMailServerImpl implements ReportedMailServer {

	private static final long serialVersionUID = 1L;

	@NonNull String type;
	@NonNull String name;

	@Override
	public String toString() {
		return "ReportedMailServer{identifier omitted}";
	}
}
