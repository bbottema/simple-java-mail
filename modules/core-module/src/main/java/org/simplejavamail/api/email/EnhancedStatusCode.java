package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

/**
 * An enhanced mail status such as {@code 5.1.1}, already checked and split into its three numeric parts.
 * SMTP replies and received delivery notifications use the same value type.
 * The status class has three possible values; unfamiliar subject/detail combinations remain readable rather than being discarded.
 * For a delivery notification, read the recipient's {@link DeliveryAction} too: a temporary problem can end in either delay or abandoned delivery.
 */
public interface EnhancedStatusCode extends Serializable {

	/** The broad result: {@code 2} for success, {@code 4} for temporary failure or {@code 5} for permanent failure. */
	int getStatusClass();

	/** The middle part, from zero through 999, describing the area of the problem. For {@code 5.1.1}, this is {@code 1}. */
	int getSubject();

	/** The final part, from zero through 999, describing the particular condition within that area. */
	int getDetail();

	/** Whether the code belongs to the success class. This is a reported result, not independently verified delivery. */
	boolean isSuccess();

	/** Whether the code describes a temporary problem. This alone is not permission for the application to resend. */
	boolean isTemporaryFailure();

	/** Whether the code describes a permanent problem with sending the message in its current form. */
	boolean isPermanentFailure();

	/** The complete code, such as {@code 5.1.1}, with no diagnostic prose. */
	@Override
	@NotNull String toString();
}
