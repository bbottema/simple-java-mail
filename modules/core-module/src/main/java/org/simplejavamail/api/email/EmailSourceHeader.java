package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
/**
 * One received header occurrence. Its value is not decoded, unfolded or validated for sending.
 *
 * @since 10.0.0
 */
public interface EmailSourceHeader extends EmailSourceElement {
	/** Original field-name spelling exposed by the source reader. */
	@NotNull String getName();

	/** Original value exposed by the source reader, including a provider-supplied null value. */
	@Nullable String getRawValue();
}
