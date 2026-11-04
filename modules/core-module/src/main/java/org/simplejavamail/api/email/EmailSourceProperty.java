package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One reader-exposed native property. Scalar/text values are the reader's representation, not a promise of original storage bytes.
 * Large body/binary values follow the same selective-retention contract as source parts.
 *
 * @since 10.0.0
 */
public interface EmailSourceProperty extends EmailSourceElement {

	/** Native name or identifier exposed by the reader. */
	@NotNull String getName();

	/** Native type description when available. */
	@Nullable String getType();

	/** Captured scalar/text representation, or null when unavailable or not retained. */
	@Nullable String getValue();

	/** Retained captured bytes, or null when unavailable or not retained. */
	@Nullable EmailSourceContent getContent();
}
