package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A captured source component, which may be a container, body, attachment or embedded image.
 * Successful unchanged content can have metadata here without a duplicate payload. Original content retained after a successful
 * transformation need not have a corresponding {@link EmailProblem}.
 *
 * @since 10.0.0
 */
public interface EmailSourcePart extends EmailSourceElement {

	/** Available header occurrences belonging to this component; never null. */
	@NotNull List<EmailSourceHeader> getHeaders();

	/** Available reader-native properties belonging to this component; never null. */
	@NotNull List<EmailSourceProperty> getProperties();

	/** Available immediate children, or an empty list for a leaf. Enumeration performs no further reads. */
	@NotNull List<EmailSourcePart> getChildren();

	/** Retained text already supplied by the source reader, or null; no decoder runs to supply this value. */
	@Nullable String getText();

	/** Retained captured bytes, or null when unavailable, not acquired or deliberately not retained. */
	@Nullable EmailSourceContent getContent();
}
