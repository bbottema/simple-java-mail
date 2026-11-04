package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.UUID;

/**
 * Immutable identity of an inspected input. Copies retain it even after their outgoing Message-ID changes.
 * An input has this identity whether or not processing it produced a problem.
 *
 * @since 10.0.0
 */
public interface EmailSourceIdentity extends Serializable {

	/** Stable diagnostic reference, independent of missing, reused or generated email identifiers. */
	@NotNull UUID getReference();

	/** Observed input Message-ID, or null when absent; not a uniqueness, validity or authenticity guarantee. */
	@Nullable String getMessageId();
}
