package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

/**
 * One captured source occurrence. A problem can refer directly to this value without retaining a parser or ancestral Email.
 *
 * @since 10.0.0
 */
public interface EmailSourceElement extends Serializable {

	/** Identity of the input exposing this occurrence. */
	@NotNull EmailSourceIdentity getOrigin();

	/** Diagnostic location within the identified input; use problem source references rather than parsing this label. */
	@NotNull String getLocation();
}
