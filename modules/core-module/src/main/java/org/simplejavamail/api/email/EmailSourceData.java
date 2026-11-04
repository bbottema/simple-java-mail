package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.Serializable;
import java.util.List;

/**
 * Immutable, enumerable source evidence, independent of outgoing composition and processing problems.
 * Part metadata includes successful content too; additional payloads are retained only when the root Email does not expose the
 * received content, excluding equivalent representations such as successful Base64 decoding. For example, original RTF may be retained
 * alongside converted HTML without a processing problem. Reading this view does not reopen resources, decode content or run crypto.
 *
 * @since 10.0.0
 */
public interface EmailSourceData extends Serializable {
	/** Format actually exposed by the source reader, not a generated MIME structure attributed to an MSG input. */
	enum Format { MIME, OUTLOOK_MSG }

	/** Relationship of this view to the input being inspected. */
	enum Representation { RECEIVED, EXTRACTED, GENERATED }

	/** Input identity, or null for an empty view with no captured source. */
	@Nullable EmailSourceIdentity getOrigin();

	/** Captured source format, or null for an empty view. */
	@Nullable Format getFormat();

	/** Captured view relationship, or null for an empty view. */
	@Nullable Representation getRepresentation();

	/** Available root-header occurrences in source order, with original spelling and raw values. */
	@NotNull List<EmailSourceHeader> getHeaders();

	/** Reader-exposed native properties in their available order; never null. */
	@NotNull List<EmailSourceProperty> getProperties();

	/** Immediate source content components; containers expose their own children. Never null. */
	@NotNull List<EmailSourcePart> getParts();

	/** Extracted/generated views actually materialized by processing; enumeration starts no further processing. Never null. */
	@NotNull List<EmailSourceData> getDerivedSources();
}
