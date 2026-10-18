package org.simplejavamail.internal.email;

import lombok.Builder;
import lombok.Value;
import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.email.EnhancedStatusCode;

/** Shared value behind SMTP replies and received delivery reports; unfamiliar numeric subject/detail combinations remain usable. */
@ApiStatus.Internal
@Value
public class EnhancedStatusCodeImpl implements EnhancedStatusCode {

	private static final long serialVersionUID = 1L;

	int statusClass;
	int subject;
	int detail;

	@Builder
	private EnhancedStatusCodeImpl(final int statusClass, final int subject, final int detail) {
		if (statusClass != 2 && statusClass != 4 && statusClass != 5) {
			throw new IllegalArgumentException("A delivery status class must be 2, 4 or 5");
		}
		if (subject < 0 || subject > 999 || detail < 0 || detail > 999) {
			throw new IllegalArgumentException("Delivery status subject and detail must each be between zero and 999");
		}
		this.statusClass = statusClass;
		this.subject = subject;
		this.detail = detail;
	}

	@Override
	public boolean isSuccess() {
		return statusClass == 2;
	}

	@Override
	public boolean isTemporaryFailure() {
		return statusClass == 4;
	}

	@Override
	public boolean isPermanentFailure() {
		return statusClass == 5;
	}

	@Override
	public String toString() {
		return statusClass + "." + subject + "." + detail;
	}
}
